package com.nakas.skate3

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects everything needed to work out why the game failed on somebody
 * else's device, into one file they can send.
 *
 * This exists because the useful evidence is spread across four places that a
 * player has no way to reach. The engine writes its own log and, when it
 * catches a fault, a crash report beside it - but both live under
 * `Android/data`, which recent Android versions do not let the file manager
 * open. The system log holds the native side of a hard crash, and is cleared
 * by a reboot. And the device details that decide which code path even runs
 * (SoC, ABI, RAM, Android version) are not in any of them.
 *
 * A report gathered here answers the question "what is different about this
 * device" without another round trip.
 */
object Diagnostics {

    /** Everything, newest first, as one plain-text report. */
    fun collect(context: Context): String = buildString {
        appendLine("Skate 3 for Android - diagnostic report")
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
        appendLine()

        appendLine(section("Build"))
        appendLine("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Engine ${engineVersion(context)}")
        appendLine("Native library ${nativeLibraryDescription(context)}")
        appendLine()

        appendLine(section("Device"))
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
        // The distinction the report is often being read for: a custom ROM
        // reports a different build fingerprint from the vendor's own.
        appendLine("Build ${Build.FINGERPRINT}")
        appendLine("SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("CPUs ${Runtime.getRuntime().availableProcessors()}")
        appendLine(cpuClusters())
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        appendLine("RAM ${info.totalMem / (1024 * 1024)} MB total, " +
            "${info.availMem / (1024 * 1024)} MB available" +
            if (info.lowMemory) " (LOW MEMORY)" else "")
        appendLine()

        appendLine(section("Game files"))
        // Where the folder is supposed to be, and every reason it might not be
        // there. A device that cannot create its own directory under
        // Android/data fails much later and somewhere misleading - the disc
        // image is picked successfully and then has nowhere to be extracted to
        // - so the state is recorded before anything depends on it.
        val external = try {
            context.getExternalFilesDir(null)
        } catch (e: Exception) {
            appendLine("getExternalFilesDir threw: ${e.message}")
            null
        }
        appendLine("External storage state ${Environment.getExternalStorageState()}")
        appendLine("Emulated ${Environment.isExternalStorageEmulated()}, " +
            "removable ${Environment.isExternalStorageRemovable()}")
        appendLine("getExternalFilesDir ${external?.absolutePath ?: "NULL - no external storage for this app"}")
        if (external != null) {
            appendLine("  exists ${external.isDirectory}, writable ${external.canWrite()}, " +
                "mkdirs ${if (external.isDirectory) "not needed" else external.mkdirs().toString()}")
        }
        appendLine("Internal filesDir ${context.filesDir.absolutePath} " +
            "(exists ${context.filesDir.isDirectory}, writable ${context.filesDir.canWrite()})")
        val root = GameData.root(context)
        appendLine("In use: ${root.absolutePath}")
        appendLine("Folder exists ${root.isDirectory}, writable ${root.canWrite()}")
        appendLine("Disc files installed ${GameData.isGameInstalled(context)}")
        appendLine("Title update staged ${GameData.isTitleUpdateInstalled(context)}")
        // Which route the player is on when it is not staged. A package sitting
        // in the folder unstaged means the install failed AFTER the transfer,
        // which is a different bug from never having got the file at all, and
        // the reports could not tell the two apart.
        appendLine("Title update package on disk " +
            (TitleUpdate.alreadyOnDisk(context)?.let { "${it.name} (${it.length()} bytes)" }
                ?: "none"))
        appendLine("Ready to play ${GameData.isReadyToPlay(context)}")
        appendLine(freeSpace(root))
        appendLine()
        appendLine(listing(root))
        appendLine()
        appendLine(mountInfo(root))
        appendLine(openFileLimit())
        appendLine()
        appendLine(userListing(GameData.userDir(context)))
        appendLine()
        appendLine("Map packs")
        append(MapPacks.diagnostics(context))
        appendLine()
        appendLine(discIdentity(context))
        appendLine()
        appendLine("Locale ${Locale.getDefault()}")
        appendLine(userSetting(context, "user_language"))
        appendLine()
        appendLine(section("Engine startup (stderr)"))
        appendLine(tail(File(root, "stderr.log"), STDERR_TAIL_BYTES))
        appendLine()

        appendLine(section("Engine crash report"))
        val crash = File(root, "skate3.log.crash")
        appendLine(if (crash.isFile) crash.readText() else "None. The engine did not catch a fault.")
        appendLine()

        appendLine(section("System log (this app only)"))
        appendLine(logcat())
        appendLine()

        // Last, because it is much the longest and a reader wants the summary
        // above it first.
        appendLine(section("Engine log, last ${LOG_TAIL_BYTES / 1024} KB"))
        appendLine(tail(File(root, "skate3.log"), LOG_TAIL_BYTES))
    }

    /** Writes the report where it can be attached to a message. */
    fun write(context: Context): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        // One name, so repeated reports do not pile up in the cache.
        val file = File(dir, "skate3-report.txt")
        file.writeText(collect(context))
        return file
    }

    private fun section(title: String) = "===== $title " + "=".repeat(maxOf(4, 60 - title.length))

    /**
     * The engine build, at the top of the report where it is read.
     *
     * The app version alone is not enough to tell two builds apart. Six APKs
     * were handed out in one day all reporting "0.1.14 (15)", two testers ended
     * up on different ones, and working out which was which meant grepping the
     * log for the presence or absence of individual diagnostic lines. The
     * engine already stamps its git description into the log on every start;
     * this lifts the most recent one into the Build section so a report
     * identifies itself in its first five lines.
     */
    private fun engineVersion(context: Context): String = try {
        val log = File(GameData.root(context), "skate3.log")
        if (!log.isFile) "unknown (no engine log yet)"
        else log.useLines { lines ->
            lines.lastOrNull { it.contains("skate3 starting [") }
                ?.substringAfter("skate3 starting [")?.substringBefore(']')
                ?: "unknown (the engine has not started)"
        }
    } catch (e: Exception) {
        "unknown (${e.message})"
    }

    private fun nativeLibraryDescription(context: Context): String = try {
        val lib = File(context.applicationInfo.nativeLibraryDir, "libmain.so")
        // Uncompressed in the APK (extractNativeLibs=false), so on most
        // installs this path does not exist as a real file and the size is
        // simply unavailable - which is itself worth stating rather than
        // reporting as an error.
        if (lib.isFile) "${lib.length() / (1024 * 1024)} MB at ${lib.absolutePath}"
        else "mapped from the APK (${context.applicationInfo.nativeLibraryDir})"
    } catch (e: Exception) {
        "unknown (${e.message})"
    }

    /**
     * Maximum clock per core. Big/little splits decide how the guest threads
     * are placed, and an all-little device behaves very differently from one
     * with a fast cluster even at the same core count.
     */
    private fun cpuClusters(): String = try {
        val speeds = (0 until Runtime.getRuntime().availableProcessors()).map { cpu ->
            File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                .takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()?.div(1000)
        }
        if (speeds.all { it == null }) "Clocks unreadable"
        else "Clocks " + speeds.mapIndexed { i, mhz -> "cpu$i=${mhz ?: "?"}MHz" }.joinToString(" ")
    } catch (e: Exception) {
        "Clocks unreadable (${e.message})"
    }

    private fun freeSpace(root: File): String = try {
        val fs = StatFs(root.absolutePath)
        val free = fs.availableBlocksLong * fs.blockSizeLong
        val total = fs.blockCountLong * fs.blockSizeLong
        String.format(Locale.US, "Storage %.1f GB free of %.1f GB",
            free / 1e9, total / 1e9)
    } catch (e: Exception) {
        "Storage unreadable (${e.message})"
    }

    /**
     * Which filesystem actually serves the game folder. Three QCS8550 handhelds
     * show corrupted static data with file inputs proven identical to a working
     * phone's; the one thing no report could say was whether their game
     * directory is served by FUSE (where a read may return short or be
     * interrupted) or bind-mounted straight from the lower filesystem. This is
     * read from the app's own mount namespace, which is the only one that counts.
     */
    private fun mountInfo(dir: File): String = try {
        val path = dir.canonicalPath
        val best = File("/proc/self/mounts").readLines()
            .map { it.split(" ") }
            .filter { it.size >= 4 && (path == it[1] || path.startsWith(it[1].trimEnd('/') + "/")) }
            .maxByOrNull { it[1].length }
        val st = android.system.Os.statvfs(path)
        val passthrough = runCatching {
            ProcessBuilder("getprop", "persist.sys.fuse.passthrough.enable").start()
                .inputStream.bufferedReader().readText().trim().ifEmpty { "unset" }
        }.getOrDefault("?")
        buildString {
            appendLine("Mount ${best?.getOrNull(1) ?: "?"} type ${best?.getOrNull(2) ?: "?"} " +
                "from ${best?.getOrNull(0) ?: "?"} (${best?.getOrNull(3) ?: ""})")
            appendLine("statvfs bsize=${st.f_bsize} blocks=${st.f_blocks} bavail=${st.f_bavail} " +
                "flag=${st.f_flag} namemax=${st.f_namemax}")
            append("fuse passthrough prop $passthrough")
        }
    } catch (e: Exception) {
        "Mount info unreadable (${e.message})"
    }

    /** The per-process open-file limit; a low soft limit turns file opens into silent failures. */
    private fun openFileLimit(): String = try {
        File("/proc/self/limits").readLines()
            .firstOrNull { it.startsWith("Max open files") }
            ?.replace(Regex("\\s+"), " ")
            ?: "Max open files: not listed"
    } catch (e: Exception) {
        "Limits unreadable (${e.message})"
    }

    /**
     * The user folder two levels deep, so a report shows whether a save exists
     * and how old it is. Every failing device so far was on a first boot with
     * no save; the developer's phone always had one.
     */
    private fun userListing(user: File): String = try {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val lines = mutableListOf("User folder ${user.absolutePath}")
        fun walk(dir: File, depth: Int, prefix: String) {
            val entries = dir.listFiles()?.sortedBy { it.name } ?: return
            for (f in entries) {
                if (lines.size >= 200) return
                if (f.isDirectory) {
                    lines.add("  $prefix${f.name}/  (${f.listFiles()?.size ?: 0} entries)")
                    if (depth < 3) walk(f, depth + 1, "$prefix${f.name}/")
                } else {
                    lines.add(String.format(Locale.US, "  %s%s  %d bytes  %s", prefix, f.name,
                        f.length(), stamp.format(Date(f.lastModified()))))
                }
            }
        }
        if (user.isDirectory) walk(user, 0, "") else lines.add("  (missing)")
        lines.joinToString("\n")
    } catch (e: Exception) {
        "User folder could not be listed (${e.message})"
    }

    private fun listing(root: File): String = try {
        val entries = root.listFiles()
            ?: return "The folder could not be listed - it may not exist."
        entries.sortedBy { it.name }.joinToString("\n") {
            if (it.isDirectory) "  ${it.name}/  (${it.listFiles()?.size ?: 0} entries)"
            else String.format(Locale.US, "  %s  %.1f MB", it.name, it.length() / 1e6)
        }.ifEmpty { "The folder is empty." }
    } catch (e: Exception) {
        "The folder could not be listed (${e.message})"
    }

    /**
     * Android only hands an app the log lines written by its own UID, which is
     * exactly what is wanted here - and it still includes the previous run, so
     * a report gathered after a crash contains the crash.
     */
    private fun logcat(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "1500")
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        text.ifBlank { "Empty. The system log may have been cleared by a reboot." }
    } catch (e: Exception) {
        "Could not be read (${e.message})"
    }

    private fun tail(file: File, bytes: Long): String = try {
        if (!file.isFile) "No log file at ${file.absolutePath}" else
            file.inputStream().use { stream ->
                val skip = (file.length() - bytes).coerceAtLeast(0)
                stream.skip(skip)
                val body = stream.bufferedReader().readText()
                if (skip > 0) "[…${skip / 1024} KB earlier omitted…]\n$body" else body
            }
    } catch (e: Exception) {
        "Could not be read (${e.message})"
    }

    /**
     * Which copy of the game this is.
     *
     * Two Thor reports came back with an audio format the game asks for and
     * this build's registry does not contain, and there was no way to tell
     * from the report whether that was a corrupted queue or simply a different
     * disc - a different region, a later reprint - carrying different codec
     * data. A size and hash settles it in one line, against the developer's
     * own copy, without anyone having to send six gigabytes.
     */
    private fun discIdentity(context: Context): String = buildString {
        appendLine("Disc identity")
        val game = GameData.gameDir(context)
        for (name in listOf(
            "default.xex",
            "default.xexp",
            "data/webkit/EAWebkit.xexp",
            "data/audio/music/World_Stream.mus",
            "data/audio/music/Game_Stream.mus",
            "data/audio/music/Ipod_Stream.mus",
        )) {
            val f = File(game, name)
            // Everything in full, including the .mus streams.
            //
            // Those were sampled at 1 MB on the reasoning that they were being
            // identified rather than verified. That was the wrong call twice
            // over. The same reasoning had already hidden a mismatched region
            // of default.xex by 41,744 bytes, and the failure now under
            // investigation is an AUDIO parser producing negative buffer sizes
            // on devices whose executables are byte-identical to this build -
            // which makes the audio data the most interesting unverified input
            // there is. Hashing 430 MB costs a few seconds in a report the
            // player asks for; guessing costs a round trip per tester.
            appendLine(if (f.isFile) "  $name  ${f.length()} bytes  sha256 ${sha256(f)}"
                       else "  $name  MISSING")
        }
    }

    /**
     * Hash the whole executable, and only sample the giant media files.
     *
     * This hashed the first 1 MB of everything, which was worse than useless
     * for the one question it was added to answer. Two devices disagreed about
     * a byte in the game's codec tag table, at guest 0x8210A310 - which is
     * 0x10A310 into an image that loads at 0x82000000, i.e. **41,744 bytes past
     * the end of what was being hashed**. The report said the discs matched and
     * the region in dispute had never been looked at.
     *
     * default.xex is 6.6 MB and hashes in about a second, so it is hashed in
     * full. The .mus stream files are hundreds of megabytes and are only being
     * identified, not verified, so those keep the cheap prefix - and say so.
     * (default.xexp needs neither: the title update installer already verifies
     * both patch payloads against pinned full-file hashes and refuses a
     * mismatch, so a staged one is identical by construction.)
     */
    private fun sha256(file: File, limit: Long = Long.MAX_VALUE): String = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var read = 0L
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (read < limit) {
                val want = minOf(buffer.size.toLong(), limit - read).toInt()
                val n = stream.read(buffer, 0, want)
                if (n <= 0) break
                digest.update(buffer, 0, n)
                read += n
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }.take(32)
        if (read >= file.length()) "$hex (whole file)" else "$hex (first ${read / (1024 * 1024)} MB)"
    } catch (e: Exception) {
        "unreadable (${e.message})"
    }

    /** One line out of the saved settings, so a report says what was configured. */
    private fun userSetting(context: Context, key: String): String = try {
        val toml = File(GameData.userDir(context), "settings.toml")
        if (!toml.isFile) "$key <no settings.toml>"
        else toml.readLines().firstOrNull { it.trimStart().startsWith(key) }
            ?.trim() ?: "$key <not set>"
    } catch (e: Exception) {
        "$key unreadable (${e.message})"
    }

    private const val LOG_TAIL_BYTES = 256L * 1024

    // Small: this is the argument list and the thread placement, not a log.
    private const val STDERR_TAIL_BYTES = 8L * 1024
}
