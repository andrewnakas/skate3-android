package com.nakas.skate3

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Where the game's own files live, and whether they are all there yet.
 *
 * Everything sits under the app's external files directory
 * (/storage/emulated/0/Android/data/com.nakas.skate3/files). That is visible
 * to any file manager and to `adb push`, needs no storage permission, and is
 * removed when the app is uninstalled. The native side derives the same paths
 * from SDL, so the two agree without either being told.
 */
object GameData {

    /** The disc dump takes about 6 GB; refuse to start an install without room. */
    const val REQUIRED_FREE_BYTES = 7L * 1024 * 1024 * 1024

    /**
     * The app's own folder, created if it is not already there.
     *
     * getExternalFilesDir is documented to create the directory, and usually
     * does, but it can hand back a path it did not create - external storage
     * not mounted yet at the moment of the call, or the folder removed
     * underneath the app by a file manager. Everything downstream then fails
     * in a way that points somewhere else entirely: the title update download
     * dies with ENOENT on its own temporary file, and the free-space reading
     * throws and reports 0.0 GB, which reads as a full disk rather than a
     * missing folder.
     */
    fun root(context: Context): File {
        val external = usable(try { context.getExternalFilesDir(null) } catch (_: Exception) { null })
        val internal = usable(context.filesDir)

        // Whichever one already holds the game wins, and it is checked before
        // anything else. External storage can be missing at one launch and
        // back at the next, and silently changing roots underneath an existing
        // 6 GB install would report the game as missing and ask for the disc
        // image again.
        external?.let { if (File(it, "game/default.xex").isFile) return it }
        internal?.let { if (File(it, "game/default.xex").isFile) return it }

        // Nothing installed yet: external by preference, because it is visible
        // to a file manager and to adb, and is reclaimed on uninstall.
        //
        // The fallback is not theoretical. A LineageOS device reported the app
        // unable to add any files, and the launcher was showing "free space
        // unknown" - StatFs throwing because this directory did not exist and
        // could not be created. Android/data is restricted ground on recent
        // versions, and an app that cannot make its own folder there has no
        // way to report it that means anything to the player. Internal storage
        // always works; the engine is told which one was chosen, so both sides
        // agree.
        return external ?: internal ?: context.filesDir
    }

    /** The directory if it exists or can be made, and can be written to. */
    private fun usable(dir: File?): File? {
        if (dir == null) return null
        return try {
            if (!dir.isDirectory) dir.mkdirs()
            if (dir.isDirectory && dir.canWrite()) dir else null
        } catch (_: Exception) {
            null
        }
    }

    /** True when the game lives on internal storage because external failed. */
    fun usingInternalFallback(context: Context): Boolean =
        root(context).absolutePath.startsWith(context.filesDir.absolutePath)

    fun gameDir(context: Context): File = File(root(context), "game")
    fun userDir(context: Context): File = File(root(context), "user")

    /** The disc has been extracted: the executable the recompiler was built from. */
    fun isGameInstalled(context: Context): Boolean =
        File(gameDir(context), "default.xex").isFile

    /**
     * The title update is staged. This build executes TU3 code, so the two
     * patch payloads have to be beside the game files or it cannot boot.
     */
    fun isTitleUpdateInstalled(context: Context): Boolean {
        val game = gameDir(context)
        return File(game, "default.xexp").isFile &&
            File(game, "data/webkit/EAWebkit.xexp").isFile
    }

    fun isReadyToPlay(context: Context): Boolean =
        isGameInstalled(context) && isTitleUpdateInstalled(context)

    /** Free bytes where the game data goes, or -1 when it cannot be read. */
    fun freeBytes(context: Context): Long =
        try {
            val stat = StatFs(root(context).absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            // Distinguished from zero on purpose: "0.0 GB free" sends someone
            // off deleting photos to solve a problem that is not about space.
            -1L
        }

    /**
     * A marker file that exists only while a session is running.
     *
     * Android kills a backgrounded 3 GB process without telling anybody: no
     * crash report, no native fault, no log line. The launcher simply appears
     * again next time, which is exactly what an ordinary quit looks like - a
     * tester reported it as "either frozen or crashed... it appears to just
     * crash most of the time" and had no way to know which. The game clears
     * this on its way out, so finding it on the next launch means the process
     * did not leave under its own power.
     */
    private fun sessionMarker(context: Context) = File(userDir(context), "session.running")

    fun markSessionRunning(context: Context, running: Boolean) {
        try {
            val marker = sessionMarker(context)
            if (running) {
                marker.parentFile?.mkdirs()
                marker.writeText(System.currentTimeMillis().toString())
            } else {
                marker.delete()
            }
        } catch (_: Exception) {
            // Best effort. A marker that cannot be written costs an
            // explanation, never a session.
        }
    }

    /** True when the last session ended without the game shutting down. */
    fun lastSessionWasKilled(context: Context): Boolean = try {
        sessionMarker(context).isFile
    } catch (_: Exception) {
        false
    }

    fun describeFree(context: Context): String {
        val bytes = freeBytes(context)
        if (bytes < 0) return "free space unknown"
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        return String.format("%.1f GB free", gb)
    }
}
