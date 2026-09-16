package com.nakas.skate3

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Which Vulkan driver the engine gets, decided once per process before the
 * engine loads.
 *
 * The engine opens Vulkan by bare name at runtime rather than linking it, so
 * loading our own libvulkan.so out of nativeLibraryDir first means its later
 * dlopen("libvulkan.so") hands back our proxy. See native/PROVENANCE.md.
 *
 * Adapted from AlanConstantino/skate3-pocket. That fork defaults to Turnip;
 * this app defaults to the system driver, so it is tempting to skip the proxy
 * entirely for a system selection and leave the old path untouched. That does
 * not work, and the reason is worth writing down: shipping libvulkan.so in
 * nativeLibraryDir is itself what shadows the platform loader, because the
 * app's own library directory is searched before the system namespace. The
 * engine's dlopen can therefore reach the proxy whether or not anyone called
 * System.load on it, and an uninitialized proxy fails every Vulkan entry point.
 * So the proxy is always loaded and always initialized; the system selection
 * forwards to /system/lib64/libvulkan.so. nativeInit asserts the bare-name
 * lookup really did land on the proxy rather than assuming it.
 */
object DriverBridge {
    const val SYSTEM = "system"
    const val T30 = "t30"
    private const val PREFS = "t30_driver"
    private const val ZIP_HASH = "f65b2d3353fd4aa7190bb5426b94468e99ffea7a58a830bc0c4651db89353227"
    private const val LIB_HASH = "1d80dfa019659b008e4669311db5b1e4a02af59ff1d5458a98e2f5fe18ed013b"
    private const val META_HASH = "bf6c432ffd05a254a9531920f6ec826abb2bcf91c52a0c5467167a0ee1940f73"
    private const val LIBRARY = "vulkan.purple.so"
    @Volatile private var initializedMode: String? = null
    @Volatile private var attemptedMode: String? = null
    @Volatile private var loaded = false
    private var result: JSONObject? = null

    @JvmStatic external fun nativeInit(
        nativeLibraryDir: String, internalDriverDir: String,
        driverFilename: String, custom: Boolean
    ): String

    data class DriverOption(val id: String, val label: String, val detail: String, val imported: Boolean)

    @Synchronized fun selected(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("selected")) {
            check(prefs.edit().putString("selected", SYSTEM).commit()) { "Could not save the default GPU driver." }
        }
        // Preserve an unavailable selection so a missing import is visible;
        // never silently substitute a different driver.
        return prefs.getString("selected", SYSTEM) ?: SYSTEM
    }

    @Synchronized fun select(context: Context, mode: String) {
        require(mode == SYSTEM || mode == T30 || DriverStore.find(context, mode) != null) {
            "That driver is unavailable. Import it again or select another driver."
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("selected", mode).commit()) { "Could not save the GPU driver selection." }
    }

    fun label(context: Context, mode: String): String = when (mode) {
        T30 -> "Turnip T30"
        SYSTEM -> "System"
        else -> DriverStore.find(context, mode)?.label ?: "Unavailable imported driver"
    }

    fun choices(context: Context): List<DriverOption> = buildList {
        add(DriverOption(SYSTEM, "System GPU driver", "Built into your device · default", false))
        add(DriverOption(T30, "Turnip T30", "Bundled · MrPurple · Mesa", false))
        DriverStore.list(context).forEach {
            add(DriverOption(it.id, it.label,
                listOf(it.author, "Imported").filter(String::isNotBlank).joinToString(" · "), true))
        }
        val unavailable = DriverStore.invalidIds(context).toMutableSet()
        val selected = selected(context)
        if (none { it.id == selected }) unavailable.add(selected)
        unavailable.forEach { id ->
            add(DriverOption(id, "Unavailable imported driver",
                "Remove this import and import its ZIP again", id.startsWith("imported:")))
        }
    }

    /**
     * Whether the proxy has already been loaded into this process.
     *
     * Matters because prepareBundle refuses to touch the driver files once it
     * has, so a caller that wants to stage T30 before restarting has to know
     * whether it still can - a fresh process always can.
     */
    fun proxyLoaded(): Boolean = loaded

    /** Loaded native files must stay in place until their process exits. */
    @Synchronized fun removeImported(context: Context, id: String) {
        check(id != selected(context)) { "Select another driver and apply it before removing this one." }
        check(id != attemptedMode) { "Restart the app before removing a driver used in this session." }
        DriverStore.remove(context, id)
    }

    private fun digest(input: InputStream): String = input.use {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
        hash.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }

    private fun valid(file: File, hash: String): Boolean =
        file.isFile && runCatching { digest(file.inputStream()) == hash }.getOrDefault(false)

    /** Extract only the bundled, hash-pinned two-file driver package. */
    @Synchronized fun prepareBundle(context: Context): File {
        val parent = File(context.filesDir, "drivers").apply { mkdirs() }
        val destination = File(parent, "t30-${LIB_HASH.take(12)}")
        if (valid(File(destination, LIBRARY), LIB_HASH) &&
            valid(File(destination, "meta.json"), META_HASH)) return destination
        check(!loaded) { "Driver files changed after loading. Restart the app before preparing them again." }
        val archive = File.createTempFile("turnip-t30-", ".zip", context.cacheDir)
        val staging = File(parent, ".t30-staging-${Process.myPid()}")
        try {
            context.assets.open("drivers/turnip-t30.zip").use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= 32L * 1024 * 1024) { "Bundled driver archive is too large." }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(digest(archive.inputStream()) == ZIP_HASH) { "Bundled T30 archive failed its integrity check." }
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare private driver storage." }
            ZipFile(archive).use { zip ->
                check(zip.entries().asSequence().map { it.name }.toSet() == setOf("meta.json", LIBRARY))
                for ((name, expectedSize, hash) in listOf(
                    Triple("meta.json", 346L, META_HASH), Triple(LIBRARY, 18869912L, LIB_HASH)
                )) {
                    val entry = zip.getEntry(name)
                    check(entry.size == expectedSize) { "Unexpected T30 file size: $name" }
                    val file = File(staging, name)
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    check(valid(file, hash)) { "T30 file failed its integrity check: $name" }
                    check(file.setReadOnly()) { "Could not protect the imported driver file." }
                }
            }
            if (destination.exists()) check(destination.deleteRecursively()) { "Could not replace invalid driver files." }
            check(staging.renameTo(destination)) { "Could not activate the verified T30 files." }
            return destination
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    /** Called before SDL loads libmain, or explicitly from the setup probe. */
    @Synchronized fun initialize(context: Context): JSONObject {
        val mode = selected(context)
        initializedMode?.let {
            check(it == mode) { "The GPU driver changed. Restart the app before starting the game." }
            return result!!
        }
        var nativeRecord: JSONObject? = null
        try {
            attemptedMode?.let {
                check(it == mode) { "The GPU driver changed. Restart the app before starting the game." }
            }
            check(mode != T30 || Build.VERSION.SDK_INT >= 30) {
                "Turnip T30 needs Android 11 or newer. Select System to use this Android version."
            }
            val imported = if (mode != T30 && mode != SYSTEM) DriverStore.verify(context, mode) else null
            val driverDir = when (mode) {
                T30 -> prepareBundle(context)
                SYSTEM -> File(context.filesDir, "drivers")
                else -> imported!!.directory
            }
            val filename = imported?.libraryName ?: if (mode == T30) LIBRARY else ""
            val custom = mode != SYSTEM
            // A native driver can terminate the process before returning an
            // error. Leave a persisted attempt record so the next launcher
            // explains the incomplete check without automatically loading it.
            attemptedMode = mode
            record(context, mode, JSONObject().put("ok", false).put("pending", true)
                .put("error", "The previous driver check did not finish. Select another driver if it keeps closing the app."))
            if (!loaded) {
                System.load(File(context.applicationInfo.nativeLibraryDir, "libvulkan.so").absolutePath)
                loaded = true
            }
            imported?.let { DriverStore.markInUse(it.id) }
            val json = JSONObject(nativeInit(context.applicationInfo.nativeLibraryDir,
                driverDir.absolutePath, filename, custom))
            nativeRecord = json
            record(context, mode, json)
            check(json.optBoolean("ok", false)) {
                json.optString("error", "The selected GPU driver could not be initialized.")
            }
            check(json.optString("mode") == if (custom) "turnip" else "system") {
                "The loaded driver does not match the selection. Restart before trying again."
            }
            initializedMode = mode
            result = json
            return json
        } catch (error: Throwable) {
            val message = error.message ?: error.javaClass.simpleName
            record(context, mode, (nativeRecord ?: JSONObject()).put("ok", false).put("error", message))
            throw IllegalStateException("${label(context, mode)} failed: $message\nSelect another driver and apply it to restart.", error)
        }
    }

    private fun record(context: Context, mode: String, json: JSONObject) {
        val record = JSONObject().put("selection", mode).put("pid", Process.myPid())
            .put("label", label(context, mode)).put("time_ms", System.currentTimeMillis()).put("native", json)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_result", record.toString()).commit()
        Log.i("Skate3Driver", record.toString())
    }

    fun status(context: Context): String {
        val mode = selected(context)
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_result", null)
        val detail = runCatching {
            val record = JSONObject(last ?: "{}")
            val native = record.optJSONObject("native") ?: return@runCatching "Not checked yet."
            val checked = record.optString("label").ifBlank { label(context, record.optString("selection")) }
            if (native.optBoolean("ok")) {
                val device = native.optJSONArray("devices")?.optJSONObject(0)
                val actual = if (native.optString("mode") == "turnip") "Turnip" else "System"
                "Last check: $checked selected; $actual verified.\n" +
                    listOfNotNull(device?.optString("name"), device?.optString("driver_name"))
                        .filter { it.isNotBlank() }.joinToString(" · ")
            }
            else "Last check: $checked failed — ${native.optString("error")}."
        }.getOrDefault("No driver check is available.")
        return "Selected GPU driver: ${label(context, mode)}\n$detail"
    }

    fun diagnostic(context: Context): String = status(context) + "\n" +
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_result", "No native driver result")
}
