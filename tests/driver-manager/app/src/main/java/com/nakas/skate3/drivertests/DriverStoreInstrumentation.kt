package com.nakas.skate3.drivertests

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.nakas.skate3.DriverBridge
import com.nakas.skate3.DriverStore
import com.nakas.skate3.ImportedDriver
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.EOFException
import java.io.InputStream
import java.nio.charset.MalformedInputException
import java.security.MessageDigest
import java.util.zip.ZipException

/** Invokes actual production methods with real Android JSON, ZIP, disk and prefs. */
class DriverStoreInstrumentation : Instrumentation() {
    private val cases = JSONArray()
    private val failures = JSONArray()
    private var assertions = 0
    private val ctx: Context get() = targetContext
    private val prefs get() = ctx.getSharedPreferences("t30_driver", Context.MODE_PRIVATE)

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    private fun expect(condition: Boolean, message: String) {
        assertions++
        check(condition) { message }
    }

    private fun rejects(message: String, action: () -> Unit): Exception {
        try { action() } catch (failure: Exception) {
            expect(!failure.message.isNullOrBlank(), "$message: exception must explain failure")
            return failure
        }
        throw AssertionError("$message: unexpectedly accepted")
    }

    private fun reset() {
        expect(prefs.edit().clear().commit(), "clear test preferences")
        ctx.filesDir.listFiles()?.forEach { expect(it.deleteRecursively(), "clear test file ${it.name}") }
        ctx.cacheDir.listFiles()?.forEach { expect(it.deleteRecursively(), "clear test cache ${it.name}") }
    }

    private fun runCase(name: String, action: () -> Unit) {
        val before = assertions
        val record = JSONObject().put("name", name)
        try {
            reset()
            action()
            record.put("pass", true)
        } catch (failure: Throwable) {
            record.put("pass", false).put("exception", android.util.Log.getStackTraceString(failure))
            failures.put("$name: $failure")
        }
        record.put("assertions", assertions - before)
        cases.put(record)
    }

    private fun asset(name: String): InputStream = ctx.assets.open("fixtures/$name")
    private fun imported(name: String = "valid-alpha.zip"): ImportedDriver =
        asset(name).use { DriverStore.importZip(ctx, it) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun tree(directory: File): Set<String> = directory.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(directory).path }.toSet()

    /** A rejected import must preserve the prior install and leave no temp files. */
    private fun rejectionMatrix(names: List<String>) {
        val good = imported()
        val beforeFiles = tree(ctx.filesDir)
        val beforeCache = tree(ctx.cacheDir)
        val original = File(good.directory, good.libraryName).readBytes()
        for (name in names) {
            val failure = rejects(name) { asset(name).use { DriverStore.importZip(ctx, it) } }
            expect(failure is IllegalArgumentException, "$name should be an invalid-input error, got $failure")
            expect(DriverStore.list(ctx).map { it.id } == listOf(good.id), "$name changed installed drivers")
            expect(tree(ctx.filesDir) == beforeFiles, "$name left files or escaped staging")
            expect(tree(ctx.cacheDir) == beforeCache, "$name left archive cache")
            expect(File(good.directory, good.libraryName).readBytes().contentEquals(original), "$name changed prior library")
        }
    }

    override fun onStart() {
        val report = JSONObject().put("scope", "Production DriverStore and DriverBridge Kotlin; real Android storage, ZIP, JSON and preferences. One missing-proxy initialization attempt exercises failure recovery; no native library loads and no game execution.")
            .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
        val output = Bundle()
        try {
            report.put("build_inputs", JSONObject(ctx.assets.open("build-inputs.json").bufferedReader().use { it.readText() }))
            runCase("Fresh selection initializes to the system driver") {
                // This app defaults to the device's own driver; the fork these
                // tests came from defaulted to T30. Turnip is opt-in here.
                expect(DriverBridge.selected(ctx) == DriverBridge.SYSTEM, "fresh default System")
                expect(prefs.getString("selected", null) == DriverBridge.SYSTEM, "default committed")
            }
            runCase("Legacy System selection survives migration") {
                expect(prefs.edit().putString("selected", "system").putString("last_result", "legacy").commit(), "seed System")
                expect(DriverBridge.selected(ctx) == "system", "preserve System")
                expect(prefs.getString("last_result", null) == "legacy", "preserve diagnostic")
            }
            runCase("Legacy T30 selection and verified bundle survive migration") {
                expect(prefs.edit().putString("selected", "t30").commit(), "seed T30")
                val bundle = DriverBridge.prepareBundle(ctx)
                expect(DriverBridge.selected(ctx) == "t30", "preserve T30")
                expect(digest(File(bundle, "vulkan.purple.so").readBytes()) == "1d80dfa019659b008e4669311db5b1e4a02af59ff1d5458a98e2f5fe18ed013b", "pinned bundle library")
                expect(DriverStore.list(ctx).isEmpty(), "bundle not duplicated into import store")
            }
            runCase("Unavailable selected driver is retained without silent fallback") {
                val missing = "imported:" + "a".repeat(64)
                expect(prefs.edit().putString("selected", missing).commit(), "seed missing imported selection")
                expect(DriverBridge.selected(ctx) == missing, "missing selection must remain visible")
                expect(DriverStore.find(ctx, missing) == null, "missing driver absent")
                expect(DriverBridge.label(ctx, missing).isNotBlank(), "unavailable driver has label")
                expect(DriverBridge.choices(ctx).any { it.id == missing }, "unavailable selection remains in manager choices")
                val pending = JSONObject().put("selection", missing).put("label", "Pending fixture")
                    .put("native", JSONObject().put("ok", false).put("pending", true)
                        .put("error", "The previous driver check did not finish."))
                expect(prefs.edit().putString("last_result", pending.toString()).commit(), "persist interrupted-attempt record")
                expect(DriverBridge.status(ctx).contains("previous driver check did not finish"), "interrupted native check explained on next launcher")
                DriverBridge.select(ctx, DriverBridge.SYSTEM)
                expect(DriverBridge.selected(ctx) == DriverBridge.SYSTEM, "System remains selectable")
            }
            runCase("Valid multi-file ARM64 archive imports with exact hashes") {
                val d = imported("valid-extra.zip")
                expect(d.name == "Fixture Alpha" && d.version == "1" && d.author == "Regression", "metadata fields")
                expect(d.minApi == 28 && d.libraryName == "vulkan.test.so", "compatibility metadata")
                expect(d.id == "imported:" + asset("valid-extra.zip").use { digest(it.readBytes()) }, "content-addressed id")
                expect(d.directory.canonicalPath.startsWith(File(ctx.filesDir, "drivers/imported").canonicalPath + "/"), "private import path")
                expect(d.files.keys.containsAll(listOf("meta.json", "vulkan.test.so", "libextra.so", "LICENSE")), "all payload files tracked")
                for ((name, hash) in d.files) expect(digest(File(d.directory, name).readBytes()) == hash, "file hash $name")
                DriverStore.verify(ctx, d.id)
            }
            runCase("Real T30 and R7 release ZIPs import together") {
                val a = imported("release-t30.zip")
                val b = imported("release-r7.zip")
                expect(a.id != b.id && a.libraryName == "vulkan.purple.so" && b.libraryName == "vulkan.ad07xx.so", "distinct actual driver packages")
                DriverStore.verify(ctx, a.id); DriverStore.verify(ctx, b.id)
                expect(DriverStore.list(ctx).map { it.id }.toSet() == setOf(a.id, b.id), "both releases listed")
            }
            runCase("Import selection persists through new Android Context") {
                val a = imported(); val b = imported("valid-beta.zip")
                DriverBridge.select(ctx, b.id)
                val reopened = ctx.createPackageContext(ctx.packageName, 0)
                expect(DriverBridge.selected(reopened) == b.id, "selection persisted")
                val diskPrefs = File(ctx.applicationInfo.dataDir, "shared_prefs/t30_driver.xml")
                expect(diskPrefs.readText().contains(b.id), "committed selection exists on disk")
                expect(DriverStore.list(reopened).map { it.id } == listOf(a.id, b.id), "stable label order from disk")
                expect(DriverStore.find(reopened, a.id)?.archiveSha256 == a.archiveSha256, "metadata reread")
            }
            runCase("Duplicate import deduplicates verified content") {
                val a = imported(); val before = tree(ctx.filesDir)
                val second = imported()
                expect(second.id == a.id && second.directory == a.directory, "stable duplicate identity")
                expect(DriverStore.list(ctx).size == 1 && tree(ctx.filesDir) == before, "no duplicate install")
                expect(tree(ctx.cacheDir).isEmpty(), "no archive temp remains")
            }
            runCase("Selected deletion is refused; another driver deletion persists") {
                val a = imported(); val b = imported("valid-beta.zip")
                DriverBridge.select(ctx, a.id)
                rejects("selected deletion") { DriverBridge.removeImported(ctx, a.id) }
                expect(DriverStore.find(ctx, a.id) != null, "selected kept")
                DriverBridge.removeImported(ctx, b.id)
                expect(DriverStore.find(ctx, b.id) == null && !b.directory.exists(), "other driver removed")
                expect(DriverBridge.selected(ctx) == a.id, "selection unchanged")
                DriverBridge.select(ctx, DriverBridge.SYSTEM)
                DriverBridge.removeImported(ctx, a.id)
                expect(DriverStore.list(ctx).isEmpty(), "selection switch allows removal before load")
            }
            runCase("Corrupted installed library is rejected without silent repair; explicit reimport recovers") {
                val d = imported()
                val library = File(d.directory, d.libraryName)
                expect(library.setWritable(true), "make own test payload writable")
                library.writeBytes(byteArrayOf(1, 2, 3))
                rejects("verify tamper") { DriverStore.verify(ctx, d.id) }
                rejects("dedup must not repair tamper") { imported() }
                expect(library.readBytes().contentEquals(byteArrayOf(1, 2, 3)), "no silent replacement")
                DriverStore.remove(ctx, d.id)
                val restored = imported()
                DriverStore.verify(ctx, restored.id)
                expect(restored.id == d.id, "explicit recovery retains package identity")
            }
            runCase("Unexpected installed payload is rejected and corrupt install can be removed") {
                val d = imported()
                File(d.directory, "unexpected.txt").writeText("tamper")
                rejects("unexpected payload") { DriverStore.verify(ctx, d.id) }
                d.directory.listFiles()?.filter { !it.name.endsWith(".so") }?.forEach {
                    it.setWritable(true); expect(it.delete(), "remove test install metadata ${it.name}")
                }
                expect(DriverStore.find(ctx, d.id) == null, "damaged manifest absent from catalog")
                DriverStore.remove(ctx, d.id)
                expect(!d.directory.exists(), "can remove corrupt install without parsed manifest")
            }
            runCase("Malformed empty and truncated ZIPs roll back") {
                rejectionMatrix(listOf("not-zip.zip", "empty.zip", "truncated.zip", "local-name-mismatch.zip", "bad-crc.zip"))
            }
            runCase("Traversal and noncanonical ZIP paths cannot escape staging") {
                rejectionMatrix(listOf("path-parent.zip", "path-absolute.zip", "path-backslash.zip", "path-drive.zip",
                    "path-dot.zip", "path-empty-part.zip", "path-nested-parent.zip", "symlink.zip", "reserved-manifest.zip"))
            }
            runCase("Duplicate and case-colliding entries are rejected") {
                rejectionMatrix(listOf("duplicate-meta.zip", "duplicate-library.zip", "case-collision.zip", "file-directory-conflict.zip"))
            }
            runCase("Entry count and nesting depth caps are enforced") {
                rejectionMatrix(listOf("entry-count.zip", "path-too-deep.zip"))
            }
            runCase("Metadata syntax schema required fields and length are validated") {
                rejectionMatrix(listOf("meta-json.zip", "meta-utf8.zip", "meta-missing.zip", "meta-schema.zip", "meta-schema-string.zip",
                    "meta-name-empty.zip", "meta-name-long.zip", "meta-version-empty.zip", "meta-author-empty.zip"))
            }
            runCase("Metadata API compatibility and primary library reference are validated") {
                rejectionMatrix(listOf("meta-future-api.zip", "meta-fraction-api.zip", "meta-string-api.zip", "meta-zero-api.zip",
                    "meta-missing-library.zip", "meta-library-path.zip", "meta-library-non-so.zip"))
            }
            runCase("Metadata expanded-byte cap rejects oversized JSON") {
                rejectionMatrix(listOf("meta-large.zip"))
            }
            runCase("Primary library must be ELF64 little-endian AArch64 ET_DYN") {
                rejectionMatrix(listOf("elf-magic.zip", "elf-class.zip", "elf-endian.zip", "elf-machine.zip", "elf-type.zip", "elf-short.zip",
                    "elf-phdr-bounds.zip", "elf-no-dynamic.zip", "elf-segment-bounds.zip"))
            }
            runCase("Invalid secondary shared object rolls back entire install") {
                rejectionMatrix(listOf("bad-extra-elf.zip", "nested-native.zip"))
            }
            runCase("Expanded ZIP bomb is rejected without installing payload") {
                rejectionMatrix(listOf("expanded-bomb.zip"))
            }
            runCase("Archive stream cap bounds compressed input and removes temporary archive") {
                val before = tree(ctx.filesDir)
                val cap = 128L * 1024 * 1024
                var consumed = 0L
                val oversized = object : InputStream() {
                    override fun read(): Int { consumed++; return 0 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = minOf(len.toLong(), cap + 65536 - consumed).toInt()
                        if (n <= 0) return -1
                        b.fill(0, off, off + n); consumed += n; return n
                    }
                }
                rejects("compressed archive cap") { DriverStore.importZip(ctx, oversized) }
                expect(consumed > cap && consumed <= cap + 65536, "input bounded near cap: $consumed")
                expect(tree(ctx.filesDir) == before && tree(ctx.cacheDir).isEmpty(), "cap failure cleans all temporary files")
            }
            runCase("Interrupted document stream leaves prior installation usable") {
                val d = imported(); DriverBridge.select(ctx, d.id)
                val original = tree(ctx.filesDir)
                for (providerError in listOf(IOException("Provider I/O failure"), ZipException("Provider ZIP read failure"),
                    EOFException("Provider ended early"), MalformedInputException(1))) {
                    val broken = object : InputStream() {
                        var sent = false
                        override fun read(): Int = throw providerError
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (sent) throw providerError
                            sent = true; b[off] = 80; return 1
                        }
                    }
                    expect(rejects("document stream interruption") { DriverStore.importZip(ctx, broken) } === providerError,
                        "provider failure type and identity preserved")
                    expect(tree(ctx.filesDir) == original && tree(ctx.cacheDir).isEmpty(), "interrupted import leaves no files")
                }
                DriverStore.verify(ctx, d.id)
                expect(DriverBridge.selected(ctx) == d.id, "failed import preserves selection")
                var singleReads = 0
                asset("valid-beta.zip").use { source ->
                    val occasionallyZero = object : InputStream() {
                        var zero = true
                        override fun read(): Int { singleReads++; return source.read() }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            zero = !zero
                            return if (!zero) 0 else source.read(b, off, len)
                        }
                    }
                    val second = DriverStore.importZip(ctx, occasionallyZero)
                    DriverStore.verify(ctx, second.id)
                    expect(second.name == "Fixture Beta" && singleReads > 0, "zero-byte provider reads make progress without changing bytes")
                }
                DriverBridge.select(ctx, DriverBridge.SYSTEM)
                expect(DriverBridge.selected(ctx) == DriverBridge.SYSTEM, "alternative usable after failure")
            }
            runCase("System bundled and malformed IDs cannot delete driver storage") {
                val d = imported()
                for (id in listOf("system", "t30", "../outside", "imported:../outside", "imported:" + "A".repeat(64)))
                    rejects("invalid deletion id $id") { DriverStore.remove(ctx, id) }
                expect(DriverStore.find(ctx, d.id) != null, "valid install survives invalid delete attempts")
                rejects("unknown selection") { DriverBridge.select(ctx, "imported:" + "f".repeat(64)) }
            }
            runCase("Failed initialization is recorded; attempted or loaded driver stays protected after selection changes") {
                val d = imported()
                expect(!File(ctx.applicationInfo.nativeLibraryDir, "libvulkan.so").exists(), "isolated harness has no native proxy to load")
                DriverBridge.select(ctx, d.id)
                val failure = rejects("actual missing-proxy initialize failure") { DriverBridge.initialize(ctx) }
                expect(failure is IllegalStateException && failure.cause is UnsatisfiedLinkError, "actual initialization reached missing System.load proxy and reported it")
                expect(DriverBridge.selected(ctx) == d.id, "failure did not silently change driver")
                val failureRecord = JSONObject(prefs.getString("last_result", "{}")!!)
                expect(failureRecord.getString("selection") == d.id && !failureRecord.getJSONObject("native").getBoolean("ok"), "failed check persisted against selected driver")
                expect(DriverBridge.status(ctx).contains("failed"), "failed check visible to launcher")
                DriverStore.markInUse(d.id)
                DriverBridge.select(ctx, DriverBridge.SYSTEM)
                rejects("different driver requires restart after native attempt") { DriverBridge.initialize(ctx) }
                expect(DriverBridge.selected(ctx) == DriverBridge.SYSTEM, "alternative selection saved for restart")
                rejects("in-use store removal") { DriverStore.remove(ctx, d.id) }
                rejects("in-use bridge removal") { DriverBridge.removeImported(ctx, d.id) }
                expect(DriverStore.find(ctx, d.id) != null && d.directory.exists(), "loaded bytes retained")
                DriverStore.verify(ctx, d.id)
                expect(imported().id == d.id, "verified duplicate does not unlink loaded bytes")
            }
            report.put("completed", true)
        } catch (failure: Throwable) {
            failures.put(failure.toString())
            report.put("completed", false).put("exception", android.util.Log.getStackTraceString(failure))
        } finally {
            val passed = failures.length() == 0 && cases.length() == 25
            report.put("pass", passed).put("assertions", assertions).put("cases", cases).put("failures", failures)
            val file = File(ctx.filesDir, "driver-regression.json")
            file.writeText(report.toString(2))
            output.putString("result_path", file.absolutePath)
            output.putString("stream", report.toString(2) + "\n")
            output.putString("pass", passed.toString())
            finish(if (passed) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
        }
    }
}
