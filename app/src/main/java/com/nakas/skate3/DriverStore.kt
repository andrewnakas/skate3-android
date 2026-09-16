package com.nakas.skate3

import android.content.Context
import android.os.Build
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

data class ImportedDriver(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val minApi: Int,
    val libraryName: String,
    val directory: File,
    val archiveSha256: String,
    val files: Map<String, String>
) {
    val label: String get() = "$name ($version)"
}

/** App-private imports only. Bundled T30 is deliberately outside this store. */
object DriverStore {
    private const val MAX_ARCHIVE = 128L * 1024 * 1024
    private const val MAX_EXPANDED = 256L * 1024 * 1024
    private const val MAX_ENTRIES = 128
    private const val MAX_METADATA = 64 * 1024
    private const val MAX_MANIFEST = 128 * 1024
    private const val MANIFEST = ".driver-store.json"
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val libraryPattern = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]*\\.so(?:\\.[A-Za-z0-9._+\\-]+)?")
    private val temporaryPattern = Regex("\\.(?:staging|archive)-[0-9a-f-]{36}(?:\\.zip)?")
    private val inUse = mutableSetOf<String>()

    private data class Metadata(val name: String, val version: String, val author: String,
                                val minApi: Int, val library: String)
    private data class Entry(val name: String, val directory: Boolean, val size: Long,
                             val compressedSize: Long, val crc: Long, val method: Int,
                             val flags: Int, val localOffset: Long)

    @Synchronized fun list(context: Context): List<ImportedDriver> =
        installedPaths(context).mapNotNull { readOrNull(it) }
            .sortedWith(compareBy<ImportedDriver> { it.label.lowercase(Locale.ROOT) }.thenBy { it.id })

    @Synchronized fun invalidIds(context: Context): List<String> =
        installedPaths(context).filter { readOrNull(it) == null }.map { "imported:${it.name}" }.sorted()

    @Synchronized fun find(context: Context, id: String): ImportedDriver? {
        val hash = id.removePrefix("imported:")
        if (id != "imported:$hash" || !hashPattern.matches(hash)) return null
        val root = root(context, false) ?: return null
        return readOrNull(File(root, hash))
    }

    /** Mark before attempting native initialization, including attempts that may fail. */
    @Synchronized fun markInUse(id: String) {
        idHash(id)
        inUse += id
    }

    @Synchronized fun verify(context: Context, id: String): ImportedDriver {
        val hash = idHash(id)
        val directory = root(context, false)?.let { File(it, hash) }
        return try {
            check(directory != null) { "Driver storage is missing." }
            verifyDirectory(directory)
        } catch (e: Exception) {
            throw IllegalStateException("Imported driver is missing or damaged. Restart the app if it was loaded, remove this driver, and import the ZIP again. ${e.message.orEmpty()}", e)
        }
    }

    @Synchronized fun remove(context: Context, id: String) {
        val hash = idHash(id)
        check(id !in inUse) { "This driver was loaded in this app process. Restart the app before removing it." }
        val parent = root(context, false) ?: return
        // The path comes from a validated ID, not the potentially damaged manifest.
        deleteOwned(File(parent, hash))
    }

    /** Consumes and closes input. No file becomes selectable until the whole import verifies. */
    @Synchronized fun importZip(context: Context, input: InputStream): ImportedDriver {
        var archive: File? = null
        var staging: File? = null
        var archiveCopied = false
        try {
            val parent = root(context, true)!!
            cleanupTemporary(parent)
            val token = UUID.randomUUID().toString()
            archive = File(parent, ".archive-$token.zip")
            val archiveHash = input.use { source ->
                FileOutputStream(archive).use { output -> copyBounded(source, output, MAX_ARCHIVE).first }
            }
            archiveCopied = true
            val id = "imported:$archiveHash"
            val destination = File(parent, archiveHash)
            if (existsNoFollow(destination)) return verify(context, id)

            val entries = inspectZip(archive)
            staging = File(parent, ".staging-$token")
            check(staging.mkdir()) { "Could not create private driver staging storage." }
            val hashes = sortedMapOf<String, String>()
            var expanded = 0L
            ZipFile(archive).use { zip ->
                val actualNames = zip.entries().asSequence().map { it.name }.toList()
                require(actualNames == entries.map { it.name }) { "ZIP directory is inconsistent." }
                for (entry in entries) {
                    val destinationFile = File(staging, entry.name)
                    if (entry.directory) {
                        check(destinationFile.mkdirs() || destinationFile.isDirectory) { "Could not create driver directory." }
                        continue
                    }
                    check(destinationFile.parentFile!!.mkdirs() || destinationFile.parentFile!!.isDirectory) { "Could not create driver directory." }
                    val zipEntry = zip.getEntry(entry.name)
                    require(zipEntry != null && zipEntry.size == entry.size && zipEntry.crc == entry.crc &&
                        zipEntry.compressedSize == entry.compressedSize && zipEntry.method == entry.method) { "ZIP entry metadata is inconsistent." }
                    val limit = minOf(MAX_EXPANDED - expanded, if (entry.name == "meta.json") MAX_METADATA.toLong() else MAX_EXPANDED)
                    val copied = zip.getInputStream(zipEntry).use { source ->
                        FileOutputStream(destinationFile).use { output -> copyBounded(source, output, limit, entry.crc) }
                    }
                    require(copied.second == entry.size) { "ZIP file size is inconsistent: ${entry.name}" }
                    expanded += copied.second
                    hashes[entry.name] = copied.first
                }
            }
            require(hashes.containsKey("meta.json")) { "This driver ZIP needs meta.json at its root." }
            val metadata = metadata(readJson(File(staging, "meta.json"), MAX_METADATA))
            require(metadata.minApi <= Build.VERSION.SDK_INT) { "This driver requires Android API ${metadata.minApi}; this device has API ${Build.VERSION.SDK_INT}." }
            require(hashes.containsKey(metadata.library)) { "Driver ZIP is missing ${metadata.library}." }
            for (name in hashes.keys) {
                val file = File(staging, name)
                if (name == metadata.library || libraryPattern.matches(file.name) || hasElfMagic(file)) {
                    require(!name.contains('/')) { "Native driver libraries must be at the ZIP root: $name" }
                    validateElf(file)
                }
            }
            val record = JSONObject().put("format", 1).put("id", id).put("archiveSha256", archiveHash)
                .put("name", metadata.name).put("version", metadata.version).put("author", metadata.author)
                .put("minApi", metadata.minApi).put("libraryName", metadata.library)
                .put("files", JSONObject(hashes as Map<*, *>))
            FileOutputStream(File(staging, MANIFEST)).use { stream ->
                stream.write(record.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Verify the entire staged installation before an atomic same-filesystem rename.
            verifyDirectory(staging, archiveHash)
            for (name in hashes.keys + MANIFEST) {
                check(File(staging, name).setReadOnly()) { "Could not protect imported driver files." }
            }
            check(!existsNoFollow(destination)) { "Driver storage changed during import. Try again." }
            check(staging.renameTo(destination)) { "Could not activate the imported driver." }
            staging = null
            return readInstalled(destination)
        } catch (e: ZipException) {
            if (!archiveCopied) throw e // A document provider may itself use a ZIP stream.
            throw IllegalArgumentException("This is not a supported driver ZIP: ${e.message.orEmpty()}", e)
        } catch (e: EOFException) {
            if (!archiveCopied) throw e // Preserve document-provider I/O failures.
            throw IllegalArgumentException("The driver ZIP is truncated.", e)
        } catch (e: CharacterCodingException) {
            if (!archiveCopied) throw e
            throw IllegalArgumentException("Driver ZIP file names must use valid UTF-8 text.", e)
        } finally {
            // Also close input when storage setup failed before the copy started.
            runCatching { input.close() }
            archive?.let { runCatching { deleteOwned(it) } }
            staging?.let { runCatching { deleteOwned(it) } }
        }
    }

    private fun idHash(id: String): String {
        require(id.startsWith("imported:") && hashPattern.matches(id.substringAfter(':'))) { "Invalid imported driver ID." }
        return id.substringAfter(':')
    }

    private fun root(context: Context, create: Boolean): File? {
        var current = context.filesDir.canonicalFile
        for (part in listOf("drivers", "imported")) {
            val child = File(current, part)
            check(!Files.isSymbolicLink(child.toPath())) { "Driver storage must not be a symbolic link." }
            if (!child.exists()) {
                if (!create) return null
                check(child.mkdir() || child.isDirectory) { "Could not create driver storage." }
            }
            check(child.isDirectory && child.canonicalFile == child.absoluteFile) { "Driver storage path is invalid." }
            current = child
        }
        return current
    }

    private fun installedPaths(context: Context): List<File> =
        root(context, false)?.listFiles()?.filter { hashPattern.matches(it.name) }.orEmpty()

    private fun readOrNull(directory: File): ImportedDriver? = try {
        readInstalled(directory)
    } catch (_: Exception) { null }

    private fun readInstalled(directory: File, expectedHash: String = directory.name): ImportedDriver {
        require(hashPattern.matches(expectedHash)) { "Invalid driver storage identity." }
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Driver directory is missing or invalid." }
        val record = readJson(File(directory, MANIFEST), MAX_MANIFEST)
        require(integer(record, "format") == 1) { "Unsupported driver storage format." }
        val archiveHash = text(record, "archiveSha256", 64)
        val id = text(record, "id", 73)
        require(archiveHash == expectedHash && id == "imported:$expectedHash") { "Driver storage identity does not match." }
        val meta = metadata(readJson(File(directory, "meta.json"), MAX_METADATA))
        require(meta.name == text(record, "name", 160) && meta.version == text(record, "version", 80) &&
            meta.author == text(record, "author", 160) && meta.minApi == integer(record, "minApi") &&
            meta.library == text(record, "libraryName", 240)) { "Driver metadata does not match its installed record." }
        val fileObject = record.opt("files") as? JSONObject ?: throw IllegalArgumentException("Driver file record is missing.")
        require(fileObject.length() in 2..MAX_ENTRIES) { "Invalid driver file count." }
        val hashes = sortedMapOf<String, String>()
        val names = mutableSetOf<String>()
        for (name in fileObject.keys()) {
            safeName(name, false)
            require(name != MANIFEST && names.add(name.lowercase(Locale.ROOT))) { "Invalid or duplicate installed file name." }
            val hash = text(fileObject, name, 64)
            require(hashPattern.matches(hash)) { "Invalid installed file checksum." }
            hashes[name] = hash
        }
        require(hashes.containsKey("meta.json") && hashes.containsKey(meta.library)) { "Required driver files are missing from the record." }
        return ImportedDriver(id, meta.name, meta.version, meta.author, meta.minApi, meta.library,
            directory, archiveHash, hashes.toMap())
    }

    private fun verifyDirectory(directory: File, expectedHash: String = directory.name): ImportedDriver {
        val driver = readInstalled(directory, expectedHash)
        check(driver.minApi <= Build.VERSION.SDK_INT) { "This driver requires a newer Android version." }
        val found = mutableSetOf<String>()
        var total = 0L
        var count = 0
        fun visit(folder: File, prefix: String, depth: Int) {
            check(depth <= 4) { "Unexpected directory depth in imported driver." }
            val children = folder.listFiles() ?: throw IllegalStateException("Could not read driver files.")
            for (file in children) {
                check(++count <= MAX_ENTRIES * 4 + 1) { "Unexpected extra driver files." }
                check(!Files.isSymbolicLink(file.toPath())) { "Imported driver contains a symbolic link." }
                val name = prefix + file.name
                if (file.isDirectory) visit(file, "$name/", depth + 1)
                else {
                    check(file.isFile && file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Invalid imported driver file." }
                    if (name == MANIFEST) continue
                    val expected = driver.files[name] ?: throw IllegalStateException("Unexpected driver file: $name")
                    total += file.length()
                    check(total <= MAX_EXPANDED) { "Imported driver exceeds the storage limit." }
                    val actual = file.inputStream().use { copyBounded(it, null, MAX_EXPANDED).first }
                    check(actual == expected) { "Checksum failed for $name." }
                    if (name == driver.libraryName || libraryPattern.matches(file.name) || hasElfMagic(file)) {
                        check(!name.contains('/')) { "Native driver libraries must be at the ZIP root." }
                        validateElf(file)
                    }
                    found += name
                }
            }
        }
        visit(directory, "", 0)
        check(found == driver.files.keys) { "Imported driver files are missing." }
        return driver
    }

    private fun metadata(json: JSONObject): Metadata {
        require(integer(json, "schemaVersion") == 1) { "This ZIP uses an unsupported driver metadata schema (expected schemaVersion 1)." }
        val library = text(json, "libraryName", 240)
        require(libraryPattern.matches(library) && !library.contains('/')) { "libraryName must name an Android shared library at the ZIP root." }
        val api = integer(json, "minApi")
        require(api in 1..10000) { "Invalid driver minimum Android API." }
        return Metadata(text(json, "name", 160), text(json, "packageVersion", 80),
            text(json, "author", 160), api, library)
    }

    private fun text(json: JSONObject, key: String, max: Int): String {
        val value = json.opt(key) as? String ?: throw IllegalArgumentException("Driver metadata needs a text field: $key")
        require(value.isNotBlank() && value.length <= max && value.none { it < ' ' || it == '\u007f' }) { "Driver metadata field $key is empty, too long, or contains control characters." }
        return value
    }

    private fun integer(json: JSONObject, key: String): Int {
        val value = json.opt(key)
        require(value is Int || value is Long) { "Driver metadata field $key must be an integer." }
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Driver metadata integer $key is out of range." }
        return number.toInt()
    }

    private fun readJson(file: File, limit: Int): JSONObject {
        require(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 1..limit.toLong()) { "Driver metadata is missing or too large: ${file.name}" }
        // Bound the read itself, even if a damaged file changes after the size check.
        val bytes = ByteArray(limit + 1)
        var count = 0
        file.inputStream().use { input ->
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) {
                    val single = input.read()
                    if (single < 0) break
                    bytes[count++] = single.toByte()
                } else count += read
            }
        }
        require(count <= limit) { "Driver metadata is too large." }
        return try {
            val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, count)).toString()
            JSONObject(decoded)
        } catch (e: JSONException) {
            throw IllegalArgumentException("Driver metadata is not a valid JSON object: ${file.name}", e)
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Driver metadata is not valid UTF-8 text: ${file.name}", e)
        }
    }

    private fun safeName(name: String, directory: Boolean) {
        require(name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 1024 &&
            !name.startsWith('/') && !name.contains('\\') && !name.contains(':') &&
            name.none { it < ' ' || it == '\u007f' }) { "ZIP contains an unsafe file path." }
        val parts = (if (directory) name.removeSuffix("/") else name).split('/')
        require(parts.size <= 4 && parts.all { it.isNotEmpty() && it != "." && it != ".." && it.toByteArray(Charsets.UTF_8).size <= 240 }) { "ZIP contains an unsafe or deeply nested file path." }
        require(parts.none { it.equals(MANIFEST, ignoreCase = true) }) { "ZIP contains a reserved driver-store file name." }
    }

    /** Read central and local headers to reject links/duplicates before creating any output. */
    private fun inspectZip(file: File): List<Entry> = RandomAccessFile(file, "r").use { zip ->
        val length = zip.length()
        require(length >= 22) { "This is not a complete ZIP archive." }
        val tailSize = minOf(length, 65557L).toInt()
        val tail = ByteArray(tailSize)
        zip.seek(length - tailSize); zip.readFully(tail)
        var end = -1
        for (i in tailSize - 22 downTo 0) {
            if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tailSize) { end = i; break }
        }
        require(end >= 0) { "ZIP directory is missing or truncated." }
        val entries = u16(tail, end + 10)
        val centralSize = u32(tail, end + 12)
        val centralOffset = u32(tail, end + 16)
        val endOffset = length - tailSize + end
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0 &&
            u16(tail, end + 8) == entries && entries in 1..MAX_ENTRIES &&
            centralOffset != 0xffffffffL && centralSize != 0xffffffffL &&
            centralOffset + centralSize == endOffset) { "ZIP64, split archives, or excessive ZIP entries are not supported." }
        val result = mutableListOf<Entry>()
        val names = mutableMapOf<String, Boolean>()
        var declared = 0L
        zip.seek(centralOffset)
        repeat(entries) {
            require(zip.filePointer + 46 <= endOffset) { "ZIP directory is truncated." }
            val h = ByteArray(46); zip.readFully(h)
            require(u32(h, 0) == 0x02014b50L) { "Invalid ZIP directory entry." }
            val flags = u16(h, 8)
            val method = u16(h, 10)
            val size = u32(h, 24)
            val compressed = u32(h, 20)
            val nameLength = u16(h, 28)
            val extraLength = u16(h, 30)
            val commentLength = u16(h, 32)
            val attrs = u32(h, 38)
            val offset = u32(h, 42)
            require(flags and 0x2061 == 0 && method in setOf(0, 8) && u16(h, 34) == 0) { "Encrypted, patched, or unsupported ZIP compression is not supported." }
            require(size != 0xffffffffL && compressed != 0xffffffffL && offset != 0xffffffffL &&
                nameLength in 1..1024 && zip.filePointer + nameLength + extraLength + commentLength <= endOffset) { "ZIP entry has invalid lengths or uses ZIP64." }
            val nameBytes = ByteArray(nameLength); zip.readFully(nameBytes)
            val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(nameBytes)).toString()
            val directory = name.endsWith('/')
            safeName(name, directory)
            val type = (attrs ushr 16).toInt() and 0xf000
            require(type == 0 || type == if (directory) 0x4000 else 0x8000) { "ZIP contains a symbolic link or special file." }
            require(attrs and 0x400L == 0L) { "ZIP contains a reparse-point file." }
            val key = name.removeSuffix("/").lowercase(Locale.ROOT)
            require(names.put(key, directory) == null) { "ZIP contains duplicate file names." }
            require(!directory || size == 0L) { "ZIP directory entry contains file data." }
            declared += size
            require(declared <= MAX_EXPANDED && compressed <= MAX_ARCHIVE && offset + 30 <= centralOffset) { "Driver ZIP exceeds the expanded-size limit or has invalid offsets." }
            if (name == "meta.json") require(size <= MAX_METADATA) { "Driver metadata is too large." }
            val resume = zip.filePointer + extraLength + commentLength
            zip.seek(offset)
            val local = ByteArray(30); zip.readFully(local)
            require(u32(local, 0) == 0x04034b50L && u16(local, 6) == flags && u16(local, 8) == method &&
                u16(local, 26) == nameLength && offset + 30 + nameLength + u16(local, 28) + compressed <= centralOffset) { "ZIP local header is inconsistent." }
            val localName = ByteArray(nameLength); zip.readFully(localName)
            require(localName.contentEquals(nameBytes)) { "ZIP local and directory file names do not match." }
            result += Entry(name, directory, size, compressed, u32(h, 16), method, flags, offset)
            zip.seek(resume)
        }
        require(zip.filePointer == endOffset) { "Unexpected ZIP directory data." }
        for (entry in result) {
            val parts = entry.name.removeSuffix("/").lowercase(Locale.ROOT).split('/')
            for (count in 1 until parts.size) require(names[parts.take(count).joinToString("/")] != false) { "ZIP file conflicts with a directory path." }
        }
        require(result.any { it.name == "meta.json" && !it.directory }) { "This driver ZIP needs meta.json at its root." }
        result
    }

    private fun copyBounded(input: InputStream, output: FileOutputStream?, limit: Long, expectedCrc: Long? = null): Pair<String, Long> {
        val hash = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            var count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val single = input.read()
                if (single < 0) break
                buffer[0] = single.toByte()
                count = 1
            }
            total += count
            require(total <= limit) { "Driver ZIP or expanded files exceed the allowed size limit." }
            hash.update(buffer, 0, count); crc.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        require(expectedCrc == null || expectedCrc == crc.value) { "ZIP entry failed its CRC integrity check." }
        output?.fd?.sync()
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } to total
    }

    private fun hasElfMagic(file: File): Boolean = file.inputStream().use {
        val first = ByteArray(4)
        it.read(first) == 4 && first.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
    }

    private fun validateElf(file: File) = RandomAccessFile(file, "r").use { elf ->
        require(elf.length() >= 64) { "Driver library is truncated: ${file.name}" }
        val h = ByteArray(64); elf.readFully(h)
        require(h.take(4) == listOf<Byte>(0x7f, 0x45, 0x4c, 0x46) && h[4].toInt() == 2 &&
            h[5].toInt() == 1 && h[6].toInt() == 1 && u16(h, 16) == 3 && u16(h, 18) == 183 &&
            u32(h, 20) == 1L && u16(h, 52) == 64) { "Driver must be an Android ARM64 little-endian ELF shared library: ${file.name}" }
        val offset = u64(h, 32)
        val count = u16(h, 56)
        require(u16(h, 54) == 56 && count in 1..128 && offset >= 64 && offset <= elf.length() - 56L * count) { "Driver ELF program headers are invalid: ${file.name}" }
        var load = false
        var dynamic = false
        repeat(count) {
            elf.seek(offset + it * 56L)
            val p = ByteArray(56); elf.readFully(p)
            val type = u32(p, 0)
            val start = u64(p, 8)
            val size = u64(p, 32)
            val memory = u64(p, 40)
            require(start >= 0 && size >= 0 && start <= elf.length() && size <= elf.length() - start) { "Driver ELF segment extends beyond its file: ${file.name}" }
            if (type == 1L) {
                require(memory >= size) { "Driver ELF load segment has invalid sizes." }
                load = true
            }
            if (type == 2L) {
                require(!dynamic && size >= 16 && size % 16L == 0L) { "Driver ELF dynamic section is invalid." }
                dynamic = true
            }
        }
        require(load && dynamic) { "Driver ELF has no loadable shared-library image: ${file.name}" }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
    private fun u64(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun existsNoFollow(file: File): Boolean = file.exists() || Files.isSymbolicLink(file.toPath())

    private fun cleanupTemporary(parent: File) {
        parent.listFiles()?.filter { temporaryPattern.matches(it.name) }?.take(16)?.forEach { deleteOwned(it) }
    }

    /** Never follows symbolic links, including in damaged or interrupted imports. */
    private fun deleteOwned(target: File) {
        var visited = 0
        fun remove(file: File, depth: Int) {
            check(++visited <= 2048 && depth <= 8) { "Damaged driver storage has too many files to remove automatically." }
            if (Files.isSymbolicLink(file.toPath())) {
                Files.delete(file.toPath())
                return
            }
            if (!file.exists()) return
            if (file.isDirectory) {
                val children = file.listFiles() ?: throw IllegalStateException("Could not read driver storage for removal.")
                children.forEach { remove(it, depth + 1) }
            }
            check(file.delete()) { "Could not remove driver storage: ${file.name}" }
        }
        remove(target, 0)
    }
}
