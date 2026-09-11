package com.nakas.skate3

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Custom map packs, and getting one onto the phone without a file manager.
 *
 * A pack is a pair of files - a `.big` holding the content and a `.header`
 * describing it - which the engine stages into the game's own content folder
 * at every launch (StageContentPacks, skate3_app_common.cpp). It looks for
 * them in a folder at the top of the game's storage.
 *
 * Putting them there by hand is what breaks. That folder lives under
 * Android/data, and a file written there by a root or Shizuku file manager is
 * owned by that tool rather than by this app: the game is then refused when it
 * opens the file, having already created the destination folders, so the
 * install looks half-done and the log says only "Could not stage 'X' content:
 * Permission denied". The file manager shows the file, at the right size, the
 * whole time. Nothing about the pack is wrong and there is nothing the player
 * can fix from inside the file manager.
 *
 * So the app copies it, the same way it already takes the disc image: the
 * system picker hands over a folder somewhere ordinary - Download is easiest -
 * and every byte that lands in the game's storage is written by this process.
 * Ownership is then right by construction.
 */
object MapPacks {

    /** A pack the engine would find and stage. */
    class Pack(
        /** The folder holding the two files. */
        val dir: File,
        /** The folder to delete to uninstall - the wrapper, when there is one. */
        val topDir: File,
        /** The name the game knows it by. The folder name IS the name. */
        val name: String,
        /** The readable name out of the header, when it has one. */
        val displayName: String?,
        val bytes: Long,
    )

    /** Anything the player can act on. The message is shown as-is. */
    class PackError(message: String) : Exception(message)

    /**
     * The engine stages the first pack by name and asks when there are several,
     * so the order here is the order the game sees.
     */
    fun installed(context: Context): List<Pack> {
        val root = GameData.root(context)
        val found = mutableListOf<Pack>()
        for (entry in root.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!entry.isDirectory || entry.name == "user" || entry.name == "game") continue
            val direct = describe(entry, entry)
            if (direct != null) {
                found.add(direct)
                continue
            }
            // A pack is often shipped inside a wrapper folder holding it beside
            // a readme, and dragging that whole folder in is the obvious thing
            // to do. The engine looks one level down for that reason; so do we,
            // or this list would disagree with what the game actually loads.
            val nested = entry.listFiles()?.sortedBy { it.name }
                ?.firstNotNullOfOrNull { if (it.isDirectory) describe(it, entry) else null }
            if (nested != null) found.add(nested)
        }
        return found
    }

    /**
     * Copies a pack out of a picked folder and into the game's storage.
     *
     * Returns the installed pack. `progress` is called with bytes copied so far
     * and the total, from the calling thread.
     */
    fun install(context: Context, tree: Uri, progress: (Long, Long) -> Unit): Pack {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var folderName = displayName(context, tree, rootId) ?: "pack"
        var files = children(context, tree, rootId)

        if (files.none { it.extension == "big" }) {
            val nested = files.filter { it.isDir }
                .firstOrNull { children(context, tree, it.id).any { f -> f.extension == "big" } }
                ?: throw PackError(
                    "There is no .big file in that folder.\n\nPick the folder that " +
                        "holds the pack's .big and .header files. If the pack is still " +
                        "in a zip or a rar, extract it first."
                )
            folderName = nested.name
            files = children(context, tree, nested.id)
        }

        val bigs = files.filter { it.extension == "big" }
        val headers = files.filter { it.extension == "header" }
        if (headers.isEmpty()) {
            throw PackError(
                "That folder has a .big file but no .header file.\n\nThe game needs " +
                    "both: the .header is what describes the pack, and content without " +
                    "it is ignored without a word."
            )
        }
        if (bigs.size > 1 || headers.size > 1) {
            throw PackError(
                "That folder holds more than one pack (${bigs.size} .big files, " +
                    "${headers.size} .header files).\n\nThe game loads one pack at a " +
                    "time. Put each one in its own folder and install them separately."
            )
        }

        // Read the descriptor before copying anything: it says which game the
        // pack belongs to, and what the game will look for it under.
        val header = readHeader(readBytes(context, tree, headers[0].id, HEADER_SIZE))
            ?: throw PackError(
                "That .header file is shorter than the $HEADER_SIZE bytes a whole one " +
                    "is.\n\nIt looks truncated - the download or the extraction did not " +
                    "finish."
            )
        if (header.titleId != 0 && header.titleId != ANY_TITLE && header.titleId != SKATE3_TITLE) {
            throw PackError(
                String.format(
                    "That pack is for another game.%n%nIts header names title %08X, and " +
                        "Skate 3 is %08X.", header.titleId, SKATE3_TITLE
                )
            )
        }

        // The folder name is load-bearing. The game reads the header, takes the
        // name recorded inside it, and looks for a folder of exactly that name;
        // a pack in a folder called anything else is enumerated and then
        // silently resolves to nothing. So the header names the folder, not
        // whatever the picked one happened to be called.
        val name = usableName(header.fileName) ?: usableName(folderName) ?: throw PackError(
            "That pack does not have a usable name, in its header or on its folder."
        )

        val total = files.filter { !it.isDir }.sumOf { it.size }
        val free = GameData.freeBytes(context)
        if (free in 0 until total + SPACE_MARGIN) {
            throw PackError(
                "There is not enough room. The pack is ${mb(total)} and there is " +
                    "${mb(free)} free."
            )
        }

        val dest = File(GameData.root(context), name)
        dest.mkdirs()
        if (!dest.isDirectory) {
            throw PackError("The folder for the pack could not be created:\n${dest.absolutePath}")
        }
        // A copy interrupted last time leaves .part files behind. They are
        // ignored by the engine - it wants a .big and a .header by extension -
        // so they are safe to find, and there is no reason to keep them.
        clearParts(dest)

        var copied = 0L
        val staged = mutableListOf<Pair<File, File>>()
        try {
            for (file in files) {
                if (file.isDir) continue
                // The engine matches these extensions exactly, in lower case.
                // Guest-side lookups are case-insensitive (Entry::GetChild uses
                // utf8_equal_case), so the game finds the file either way.
                val target = File(dest, file.name.dropLast(file.extension.length) + file.extension)
                val part = File(dest, target.name + ".part")
                openIn(context, tree, file.id).use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            progress(copied, total)
                        }
                        output.fd.sync()
                    }
                }
                staged.add(part to target)
            }

            // Replacing a pack of the same name: its old halves go now, so a
            // pack whose .big is named differently cannot leave two behind.
            for (old in dest.listFiles() ?: emptyArray()) {
                if (old.isFile && old.extension.lowercase() in setOf("big", "header")) old.delete()
            }
            // The .big goes in last. The engine treats a folder as a pack the
            // moment it holds a .big beside a header of a plausible size, so
            // renaming it last means a launch that lands in the middle of this
            // sees a folder that is not yet a pack, rather than half of one.
            for ((part, target) in staged.sortedBy { it.second.extension == "big" }) {
                if (!part.renameTo(target)) {
                    throw PackError("The pack could not be finished:\n${target.absolutePath}")
                }
            }
        } catch (e: Exception) {
            clearParts(dest)
            if (dest.listFiles().isNullOrEmpty()) dest.delete()
            throw if (e is PackError) e else PackError(
                "The pack could not be copied.\n\n${e.message ?: e.toString()}"
            )
        }

        return describe(dest, dest) ?: throw PackError(
            "The pack was copied to ${dest.absolutePath} but does not look complete."
        )
    }

    /**
     * Removes a pack, including the copy the engine staged from it.
     *
     * Both halves matter: the engine only clears staged packs it can still see
     * in the drop folder, so deleting just the folder would leave the staged
     * copy installed and the game would go on loading it.
     */
    fun remove(context: Context, pack: Pack) {
        pack.topDir.deleteRecursively()
        val profile = File(GameData.userDir(context), "0000000000000000")
        for (title in profile.listFiles() ?: emptyArray()) {
            if (!title.isDirectory) continue
            File(title, "00000002/${pack.name}").deleteRecursively()
            File(title, "Headers/00000002/${pack.name}.header").delete()
        }
    }

    /**
     * What a report needs: every folder that could be a pack, whether the
     * engine will recognise it, whether the name matches what the header says
     * the game will look for - and whether this app can read the files at all.
     *
     * That last one is the whole reason this exists. A pack copied in by hand
     * can be present, correct and the right size, and still unreadable to the
     * game because it belongs to the tool that wrote it. From the outside that
     * is indistinguishable from a pack that simply did not work, and the report
     * used to show only a folder and an entry count.
     */
    fun diagnostics(context: Context): String = buildString {
        val root = GameData.root(context)
        val candidates = root.listFiles()?.sortedBy { it.name }?.filter {
            it.isDirectory && it.name != "user" && it.name != "game"
        } ?: emptyList()
        if (candidates.isEmpty()) {
            appendLine("  No pack folders.")
            return@buildString
        }
        for (dir in candidates) {
            val pack = describe(dir, dir) ?: dir.listFiles()?.sortedBy { it.name }
                ?.firstNotNullOfOrNull { if (it.isDirectory) describe(it, dir) else null }
            val holder = pack?.dir ?: dir
            appendLine(
                "  ${dir.name}/" +
                    (if (holder != dir) " -> ${holder.name}/" else "") +
                    (if (pack == null) "   NOT A PACK" else "")
            )
            for (file in holder.listFiles()?.sortedBy { it.name } ?: emptyList()) {
                if (file.isDirectory) {
                    appendLine("    ${file.name}/")
                } else {
                    appendLine("    ${file.name}  ${file.length()} bytes  ${readable(file)}")
                }
            }
            if (pack == null) {
                appendLine("    wanted one .big and one .header of $ENGINE_MIN_HEADER bytes or more")
            }
            val header = holder.listFiles()?.firstOrNull { it.isFile && it.extension == "header" }
            val parsed = header?.let { readHeader(head(it, HEADER_SIZE)) }
            if (parsed != null) {
                appendLine(
                    String.format(
                        "    header: name '%s', title %08X%s",
                        parsed.fileName, parsed.titleId,
                        if (parsed.fileName.isNotEmpty() && parsed.fileName != holder.name) {
                            "   MISMATCH - the folder has to be named '${parsed.fileName}'"
                        } else {
                            ""
                        }
                    )
                )
            } else if (header != null) {
                appendLine("    header: unreadable or shorter than $HEADER_SIZE bytes")
            }
            appendLine("    staged: ${stagedState(context, holder.name)}")
        }
    }

    /** Proof, rather than a permission bit that can lie on this filesystem. */
    private fun readable(file: File): String = try {
        file.inputStream().use { it.read() }
        "readable"
    } catch (e: Exception) {
        "NOT READABLE by this app: ${e.message}"
    }

    private fun stagedState(context: Context, name: String): String {
        val profile = File(GameData.userDir(context), "0000000000000000")
        for (title in profile.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (!title.isDirectory) continue
            val content = File(title, "00000002/$name")
            val header = File(title, "Headers/00000002/$name.header")
            if (!content.exists() && !header.exists()) continue
            val files = content.listFiles()?.count { it.isFile } ?: 0
            return "${title.name}/00000002/$name - $files file(s), " +
                "header ${if (header.isFile) "${header.length()} bytes" else "MISSING"}"
        }
        return "not staged"
    }

    fun mb(bytes: Long): String =
        if (bytes < 0) "unknown" else String.format("%.1f MB", bytes / (1024.0 * 1024.0))

    // --- the header ------------------------------------------------------
    //
    // XCONTENT_AGGREGATE_DATA, straight off the 360: a fixed 0x148-byte record
    // the content manager reads whole (content_manager.cpp, ReadContentHeaderFile).

    private const val HEADER_SIZE = 0x148
    /** What the engine's own scan settles for. Kept in step deliberately. */
    private const val ENGINE_MIN_HEADER = 256
    private const val OFF_DISPLAY_NAME = 0x08   // 128 UTF-16 characters, big-endian
    private const val OFF_FILE_NAME = 0x108     // 42 bytes, NUL padded
    private const val OFF_TITLE_ID = 0x13C
    private const val SKATE3_TITLE = 0x454108E6
    /** A header may decline to name a title, and 0xFFFFFFFF means "this one". */
    private const val ANY_TITLE = -1
    private const val SPACE_MARGIN = 64L * 1024 * 1024

    private class Header(val fileName: String, val displayName: String?, val titleId: Int)

    private fun readHeader(bytes: ByteArray?): Header? {
        if (bytes == null || bytes.size < HEADER_SIZE) return null
        val fileName = StringBuilder().apply {
            for (i in OFF_FILE_NAME until OFF_FILE_NAME + 42) {
                val c = bytes[i].toInt() and 0xFF
                if (c == 0) break
                append(c.toChar())
            }
        }.toString().trim()
        val display = StringBuilder().apply {
            for (i in 0 until 128) {
                val at = OFF_DISPLAY_NAME + i * 2
                val c = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
                if (c == 0) break
                append(c.toChar())
            }
        }.toString().trim()
        var titleId = 0
        for (i in 0 until 4) titleId = (titleId shl 8) or (bytes[OFF_TITLE_ID + i].toInt() and 0xFF)
        return Header(fileName, display.ifEmpty { null }, titleId)
    }

    /**
     * A name that is safe as a folder, and cannot be mistaken for one of the
     * game's own. Anything else is refused rather than corrected: a pack under
     * a name the game does not expect is not installed, it is just invisible.
     */
    private fun usableName(raw: String?): String? {
        val name = raw?.trim() ?: return null
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name == "user" || name == "game" || name == "dlc") return null
        if (name.startsWith(".")) return null
        if (name.any { it == '/' || it == '\\' || it.code < 0x20 }) return null
        return name
    }

    private fun describe(dir: File, topDir: File): Pack? {
        val files = dir.listFiles() ?: return null
        var big: File? = null
        var header: File? = null
        var bytes = 0L
        for (file in files) {
            if (!file.isFile) continue
            bytes += file.length()
            when (file.extension) {
                "big" -> big = file
                // The engine refuses a header too short to be a descriptor, and
                // so does this: it would stage and then be dropped in silence.
                "header" -> if (file.length() >= ENGINE_MIN_HEADER) header = file
            }
        }
        if (big == null || header == null) return null
        return Pack(dir, topDir, dir.name, readHeader(head(header, HEADER_SIZE))?.displayName, bytes)
    }

    private fun head(file: File, count: Int): ByteArray? = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(count)
            var got = 0
            while (got < count) {
                val read = input.read(buffer, got, count - got)
                if (read <= 0) break
                got += read
            }
            if (got < count) null else buffer
        }
    } catch (_: Exception) {
        null
    }

    // --- the picked folder -----------------------------------------------

    private class Doc(val id: String, val name: String, val size: Long, val isDir: Boolean) {
        val extension: String = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
    }

    private fun children(context: Context, tree: Uri, parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = mutableListOf<Doc>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val mime = cursor.getString(3)
                out.add(
                    Doc(
                        id = cursor.getString(0),
                        name = cursor.getString(1) ?: continue,
                        size = if (cursor.isNull(2)) 0L else cursor.getLong(2),
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                )
            }
        }
        return out.sortedBy { it.name }
    }

    private fun displayName(context: Context, tree: Uri, documentId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        return context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun openIn(context: Context, tree: Uri, documentId: String) =
        context.contentResolver.openInputStream(
            DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        ) ?: throw PackError("A file in that folder could not be opened.")

    private fun readBytes(context: Context, tree: Uri, documentId: String, count: Int): ByteArray? =
        try {
            openIn(context, tree, documentId).use { input ->
                val buffer = ByteArray(count)
                var got = 0
                while (got < count) {
                    val read = input.read(buffer, got, count - got)
                    if (read <= 0) break
                    got += read
                }
                if (got < count) null else buffer
            }
        } catch (_: Exception) {
            null
        }

    private fun clearParts(dir: File) {
        for (file in dir.listFiles() ?: emptyArray()) {
            if (file.isFile && file.name.endsWith(".part")) file.delete()
        }
    }
}
