package com.nakas.skate3

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetching Title Update 3.
 *
 * The game will not boot without it: this build executes title-update code, so
 * the two patch payloads have to be staged beside the game files. The engine
 * already knows how to stage and verify them, and it already carries a
 * download URL for desktop - but that path shells out to curl, which stock
 * Android does not have. So the transfer happens here and the file is handed
 * to the engine, which checks both payloads by hash and refuses anything that
 * does not match. A truncated or wrong download therefore fails loudly at
 * staging rather than becoming a mystery crash later.
 *
 * Nothing about the patch ships inside this app. It is the publisher's data
 * and stays on the player's side of the line, which is the same rule the
 * engine's own build applies when it declines to bundle it.
 */
object TitleUpdate {

    // The address the engine configures for every other platform.
    const val DEFAULT_URL = "https://xboxunity.net/Resources/Lib/TitleUpdate.php?tuid=21774"

    /** What the package is called when someone downloads it on a computer. */
    const val PACKAGE_NAME = "TU_12K2276_000000C000000.00000000000O3"

    /** A container far outside this range is a redirect or an error page. */
    private const val MIN_BYTES = 512L * 1024
    private const val MAX_BYTES = 256L * 1024 * 1024

    /**
     * How many times to ask the server. The host is somebody else's and goes
     * down: a Samsung M53 report showed 502 Bad Gateway on eight consecutive
     * attempts across four sessions, while the same URL answers 200 with
     * 1,773,568 bytes from here. The engine's own wrapper retries twice, which
     * on an intermittent upstream is close to not retrying at all.
     */
    private const val ATTEMPTS = 4

    fun destination(context: Context): File = File(GameData.root(context), "title_update.bin")

    /**
     * Downloads to a temporary file and moves it into place only once complete,
     * so an interrupted transfer cannot leave something that looks finished.
     * Returns the file, or throws with a message worth showing.
     */
    fun download(context: Context, url: String = DEFAULT_URL,
                 onProgress: (Long, Long) -> Unit = { _, _ -> }): File =
        downloadTo(url, destination(context), onProgress)

    /**
     * The same transfer to a caller-chosen file. The engine's own installer
     * needs this too: its usual route shells out to curl, which Android does
     * not have, so it fails on every device with exit code 127 and reads to
     * the player as a connection problem.
     *
     * Retried with backoff, and only for the failures a retry can fix. A 404
     * or a 403 means the address is wrong and asking again four times only
     * makes the player wait longer for the same answer.
     */
    fun downloadTo(url: String, target: File,
                   onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        var last: Exception? = null
        for (attempt in 1..ATTEMPTS) {
            try {
                return attemptDownload(url, target, onProgress)
            } catch (e: Retryable) {
                last = e.cause as? Exception ?: e
                Log.w("skate3", "title update attempt $attempt/$ATTEMPTS failed: ${e.message}")
                if (attempt == ATTEMPTS) break
                // 1s, 2s, 4s. Long enough for a load balancer to hand the next
                // request to a different backend, short enough that nobody
                // decides the app has hung.
                try {
                    Thread.sleep(1000L shl (attempt - 1))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw last ?: IllegalStateException("The title update could not be downloaded.")
    }

    /** Marks a failure worth asking again about, carrying the real one. */
    private class Retryable(cause: Exception) : Exception(cause.message, cause)

    private fun attemptDownload(url: String, target: File,
                                onProgress: (Long, Long) -> Unit): File {
        // The folder may not exist yet; writing the temporary file straight
        // into it fails with ENOENT and looks like a network problem.
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        partial.delete()

        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "skate3-android")
            }
        } catch (e: IOException) {
            throw Retryable(e)
        }
        try {
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw Retryable(e)
            }
            if (code !in 200..299) {
                val failure = IllegalStateException(
                    "The server answered $code ${connection.responseMessage}")
                // 5xx is the server having a bad minute; 4xx is the address
                // being wrong, and no number of retries fixes that.
                throw if (code >= 500) Retryable(failure) else failure
            }
            val expected = connection.contentLengthLong
            if (expected in 1 until MIN_BYTES || expected > MAX_BYTES) {
                throw IllegalStateException("That is not the title update: $expected bytes")
            }
            var written = 0L
            try {
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            written += n
                            if (written > MAX_BYTES) {
                                throw IllegalStateException(
                                    "The download kept going past $MAX_BYTES bytes")
                            }
                            onProgress(written, expected)
                        }
                    }
                }
            } catch (e: IOException) {
                // A connection dropped part way through is the other thing a
                // retry genuinely fixes.
                throw Retryable(e)
            }
            if (written < MIN_BYTES) {
                throw Retryable(IllegalStateException(
                    "Only $written bytes arrived; the download was cut short"))
            }
            target.delete()
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Could not put the download in place")
            }
            Log.i("skate3", "title update downloaded: $written bytes -> $target")
            return target
        } finally {
            partial.delete()
            connection.disconnect()
        }
    }

    /**
     * Copies a picked file into the app's own folder and returns the real path.
     *
     * The disc image is handed to the engine as /proc/self/fd/<n>, because it
     * is several gigabytes and copying it would be absurd. That does not work
     * for this file, and the reason is specific: the engine's ISO reader was
     * taught to read a descriptor in place (see skate3_iso_installer.cpp), but
     * its title-update reader was not - that one still calls file_size() and
     * opens an ifstream on the path it is given, and both of those re-open the
     * underlying file and run a fresh permission check that the app fails. A
     * Samsung M53 report shows exactly that: "Unable to open /proc/self/fd/123",
     * after which the engine gives up and quits, which the player sees as the
     * app crashing the moment they choose the file.
     *
     * The package is 1.7 MB. Copying it costs nothing and sidesteps the whole
     * problem, so the engine gets an ordinary path it can open any way it likes.
     */
    fun stageFromUri(context: Context, uri: Uri): File {
        val target = destination(context)
        target.parentFile?.mkdirs()
        val partial = File(target.parentFile, target.name + ".part")
        partial.delete()

        val input: InputStream = try {
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("That file could not be opened.")
        } catch (e: SecurityException) {
            throw IllegalStateException(
                "This app is not allowed to read that file. Try copying it to the " +
                    "Download folder first.", e)
        }
        var written = 0L
        try {
            input.use { source ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        if (written > MAX_BYTES) {
                            throw IllegalStateException(
                                "That file is far too large to be the title update.")
                        }
                    }
                }
            }
            // The same bounds the download is held to. The engine verifies both
            // payloads by hash afterwards, so this only catches the obvious
            // mistake - a photo, a zip, the wrong download - early enough to
            // say something useful about it.
            if (written < MIN_BYTES) {
                throw IllegalStateException(
                    "That file is only $written bytes. The title update is about 1.7 MB.")
            }
            target.delete()
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Could not put the file in place")
            }
            Log.i("skate3", "title update staged from picker: $written bytes -> $target")
            return target
        } finally {
            partial.delete()
        }
    }

    /**
     * The package sitting in the game folder already, if someone put it there
     * by hand. Named as it comes off xboxunity, and also under the name this
     * app itself writes.
     */
    fun alreadyOnDisk(context: Context): File? {
        val root = GameData.root(context)
        for (name in listOf(PACKAGE_NAME, "title_update.bin")) {
            val file = File(root, name)
            if (file.isFile && file.length() in MIN_BYTES..MAX_BYTES) return file
        }
        return null
    }
}
