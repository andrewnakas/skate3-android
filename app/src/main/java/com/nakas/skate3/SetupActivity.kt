package com.nakas.skate3

import android.app.Activity
import android.app.AlertDialog
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * The launcher. Its whole job is to establish that the game's own files are
 * present before starting the engine, and to say plainly what is missing when
 * they are not.
 *
 * It does not extract anything itself: the engine already contains a disc
 * reader and a title-update stager that the desktop builds use, and running
 * them twice in two languages would be two things to keep correct. All this
 * screen does is hand over the paths.
 */
class SetupActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var playButton: Button
    private lateinit var titleUpdateButton: Button
    private lateinit var pickTitleUpdateButton: Button
    private lateinit var useLocalTuButton: Button
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        // Before refresh(), which clears the marker's meaning by reporting the
        // ordinary state.
        val killed = GameData.lastSessionWasKilled(this)
        GameData.markSessionRunning(this, false)
        refresh()
        if (killed) explainKilledSession()
    }

    /**
     * Says what happened when the last session did not end on purpose.
     *
     * Android stops a backgrounded process this size without a word - no crash
     * report, no fault, nothing in any log - and the player is simply looking
     * at the launcher again. Reported as the game crashing when switching
     * apps, which is accurate about what was seen and misleading about the
     * cause, and there was no way for anyone to tell the difference. Now there
     * is, so say so.
     */
    private fun explainKilledSession() {
        AlertDialog.Builder(this)
            .setTitle("The game was closed by Android")
            .setMessage(
                "The last session did not end on its own. Android reclaims memory " +
                    "from apps in the background, and this one needs about 3 GB, " +
                    "which makes it the first thing to go.\n\n" +
                    "It is not a crash and there is nothing wrong with your install. " +
                    "To make it less likely, close other apps before playing and " +
                    "avoid leaving the game in the background for long.\n\n" +
                    "Your career progress is saved by the game as you play."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * Two columns, because the activity is locked to landscape and a single
     * stacked column runs the buttons off the bottom of a phone held sideways -
     * which hid the one button the player needed. Text on the left, actions on
     * the right, each side scrolling on its own so neither can push the other
     * out of reach.
     */
    private fun buildUi(): View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#101418"))
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = "Skate 3"
            textSize = 30f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#C8CDD4"))
            setPadding(0, pad / 2, 0, 0)
        }
        left.addView(status)
        root.addView(ScrollView(this).apply { addView(left) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, 0, 0)
        }
        playButton = button("Play") { startGame() }
        actions.addView(playButton)
        actions.addView(button("Install from a disc image…") { pick(REQUEST_ISO) })
        titleUpdateButton = button("Download title update") { downloadTitleUpdate() }
        actions.addView(titleUpdateButton)
        useLocalTuButton = button("Use the title update already here") { useLocalTitleUpdate() }
        actions.addView(useLocalTuButton)
        pickTitleUpdateButton = button("Pick the title update file…") { pick(REQUEST_TU) }
        actions.addView(pickTitleUpdateButton)
        actions.addView(button("Map packs…") { showMapPacks() })
        actions.addView(button("Copy the details") { copyDiagnostics() })
        actions.addView(button("Save a diagnostic report") { saveReport() })
        root.addView(ScrollView(this).apply { addView(actions) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        return root
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
    }

    private fun refresh() {
        if (busy) return
        val ready = GameData.isReadyToPlay(this)
        val game = GameData.isGameInstalled(this)
        val tu = GameData.isTitleUpdateInstalled(this)
        // Only worth offering while it is the missing piece.
        titleUpdateButton.visibility = if (tu) View.GONE else View.VISIBLE
        pickTitleUpdateButton.visibility = if (tu) View.GONE else View.VISIBLE
        // Someone who copied the package in with a file manager, or whose
        // download completed before a later step failed, already has it. Asking
        // them to fetch it again from a site that is down is the wrong answer.
        val local = if (tu) null else TitleUpdate.alreadyOnDisk(this)
        useLocalTuButton.visibility = if (local == null) View.GONE else View.VISIBLE
        status.text = buildString {
            appendLine(if (ready) "Ready to play." else "The game's own files are not here yet.")
            appendLine()
            appendLine("Disc files: ${if (game) "installed" else "missing"}")
            appendLine("Title update 3: ${if (tu) "staged" else "missing"}")
            appendLine()
            val packs = MapPacks.installed(this@SetupActivity)
            if (packs.isNotEmpty()) {
                appendLine("Map packs:")
                for (pack in packs) {
                    val label = pack.displayName?.takeIf { it != pack.name }?.let { " ($it)" } ?: ""
                    appendLine("  ${pack.name}$label  ${MapPacks.mb(pack.bytes)}")
                }
                if (packs.size > 1) {
                    appendLine("The game asks which one to load when it starts.")
                }
                appendLine()
            }
            appendLine(GameData.gameDir(this@SetupActivity).absolutePath)
            appendLine(GameData.describeFree(this@SetupActivity))
            if (GameData.usingInternalFallback(this@SetupActivity)) {
                appendLine()
                appendLine(
                    "This device would not let the app create its folder in " +
                        "shared storage, so the game is being kept in the app's " +
                        "own private storage instead. It works, but a file " +
                        "manager cannot see it and uninstalling removes it."
                )
            }
            if (!ready) {
                appendLine()
                appendLine(
                    "Choose \"Install from a disc image\" and pick your own Skate 3 " +
                        "Xbox 360 disc image. Around 6 GB is extracted here. No game " +
                        "content ships inside this app."
                )
                if (!tu) {
                    appendLine()
                    appendLine(
                        "The title update is required to boot. Download it, or pick your " +
                            "own copy with \"Pick the title update file\"."
                    )
                    appendLine()
                    appendLine(
                        "If the download fails, fetch ${TitleUpdate.PACKAGE_NAME} " +
                            "(about 1.7 MB) on a computer, copy it into the folder above, " +
                            "and this screen will offer to use it."
                    )
                }
            }
        }
        playButton.isEnabled = ready
        playButton.alpha = if (ready) 1f else 0.4f
    }

    /**
     * Fetches the title update and lets the engine stage it. The engine checks
     * both patch payloads by hash, so a wrong or truncated file is rejected
     * there rather than turning into a crash once the game is running.
     */
    private fun downloadTitleUpdate() {
        if (busy) return
        busy = true
        titleUpdateButton.isEnabled = false
        status.text = "Downloading the title update…"
        Thread {
            val outcome = try {
                val file = TitleUpdate.download(this) { got, total ->
                    val line = if (total > 0) {
                        "Downloading the title update… ${got * 100 / total}%"
                    } else {
                        "Downloading the title update… ${got / 1024} KB"
                    }
                    runOnUiThread { status.text = line }
                }
                Result.success(file)
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                busy = false
                titleUpdateButton.isEnabled = true
                outcome.fold(
                    onSuccess = { file ->
                        // Hand it straight to the engine, which stages and
                        // verifies it during startup.
                        startGame(listOf("--skate3_install_tu=${file.absolutePath}"))
                    },
                    onFailure = { e ->
                        // Say which of the two working routes to take. The host
                        // is somebody else's and does go down - a Samsung M53
                        // report showed 502 Bad Gateway on every attempt across
                        // four sessions, for a file that downloads fine from a
                        // desk an hour later. "Check the connection" sends
                        // people to look at the wrong thing.
                        status.text = "The title update could not be downloaded.\n\n" +
                            (e.message ?: e.toString()) +
                            "\n\nThe download site is not this project's and is " +
                            "sometimes down. Two ways round it:\n\n" +
                            "\u2022 Wait and try again.\n" +
                            "\u2022 Download it on a computer from\n" +
                            "  xboxunity.net/Resources/Lib/TitleUpdate.php?tuid=21774\n" +
                            "  (about 1.7 MB, named\n  ${TitleUpdate.PACKAGE_NAME})\n" +
                            "  then use \"Pick the title update file\" here."
                    }
                )
            }
        }.start()
    }

    /** Stages the package that is already in the folder. */
    private fun useLocalTitleUpdate() {
        if (busy) return
        val file = TitleUpdate.alreadyOnDisk(this)
        if (file == null) {
            refresh()
            status.text = "There is no title update file in\n" +
                GameData.root(this).absolutePath
            return
        }
        startGame(listOf("--skate3_install_tu=${file.absolutePath}"))
    }

    /**
     * Picks a file here, on the launcher, rather than from inside the running
     * game.
     *
     * The engine has its own installer that used to call back into the game
     * activity to show this picker, and on several devices the app died the
     * instant the picker appeared. The reason is structural: that call blocked
     * the thread SDL runs the game on, and showing the picker pauses the game
     * activity and destroys its rendering surface, so the event that releases
     * that surface could never be handled - the thread that handles it was the
     * one waiting for the picker. The renderer kept using a window Android had
     * freed.
     *
     * Here there is no surface to lose and no thread to block. The choice is
     * handed to the engine as a command-line argument instead, which is the
     * same path the desktop builds use.
     */
    private fun pick(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        val title = if (request == REQUEST_ISO) "Select the Skate 3 disc image"
                    else "Select the title update file"
        try {
            startActivityForResult(Intent.createChooser(intent, title), request)
        } catch (e: Exception) {
            // No document provider at all: rare, but it is a plain message
            // rather than a crash.
            Log.e(TAG, "no document picker available", e)
            status.text = "This device has no file picker available.\n\n" +
                "Copy the file into\n${GameData.root(this).absolutePath}\nand try again."
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ISO && requestCode != REQUEST_TU &&
            requestCode != REQUEST_PACK) {
            return
        }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            refresh()
            return
        }
        // A map pack is a folder rather than a file, and this side does the
        // copying itself - there is no descriptor to hand the engine.
        if (requestCode == REQUEST_PACK) {
            installMapPack(uri)
            return
        }
        // The title update is copied; the disc image is read where it lies.
        //
        // Not an inconsistency - the engine's two readers differ. Its ISO
        // reader was taught to read a descriptor in place, so a 7 GB image
        // never has to be duplicated. Its title-update reader was not: that one
        // still opens the path it is handed, which re-runs a permission check
        // the app fails, and a Samsung M53 report shows it dying on exactly
        // that ("Unable to open /proc/self/fd/123") the instant the file is
        // chosen. The package is 1.7 MB, so copying it is free and gives the
        // engine an ordinary path.
        if (requestCode == REQUEST_TU) {
            stageTitleUpdate(uri)
            return
        }
        // detachFd hands the descriptor to the process, so it outlives this
        // activity and stays readable from the game as /proc/self/fd/<n>.
        // Both activities run in the same process - see the manifest, only
        // RestartActivity is given one of its own.
        val fd = try {
            contentResolver.openFileDescriptor(uri, "r")?.detachFd() ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "could not open the selected file", e)
            -1
        }
        if (fd < 0) {
            status.text = "That file could not be opened.\n\n" +
                "If it is on a USB drive or an SD card, try copying it to the " +
                "device's own storage first."
            return
        }
        confirmLongInstall(listOf("--skate3_install_iso=/proc/self/fd/$fd"))
    }

    /**
     * Copies the picked package in, then hands the engine its path to stage and
     * verify. Off the UI thread: it is only 1.7 MB, but it may be coming from a
     * cloud provider that fetches it on demand.
     */
    private fun stageTitleUpdate(uri: Uri) {
        if (busy) return
        busy = true
        status.text = "Copying the title update\u2026"
        Thread {
            val outcome = runCatching { TitleUpdate.stageFromUri(this, uri) }
            runOnUiThread {
                busy = false
                outcome.fold(
                    onSuccess = { file ->
                        startGame(listOf("--skate3_install_tu=${file.absolutePath}"))
                    },
                    onFailure = { e ->
                        refresh()
                        status.text = "That file could not be used.\n\n" +
                            (e.message ?: e.toString()) +
                            "\n\nThe title update is a single file of about 1.7 MB, " +
                            "usually named\n${TitleUpdate.PACKAGE_NAME}"
                    }
                )
            }
        }.start()
    }

    /**
     * Says what is about to happen before the screen goes black.
     *
     * Unpacking the disc takes minutes, and the engine does it before it draws
     * anything, so the player gets a black screen with no indication that
     * anything is happening. People reasonably conclude it has frozen and
     * force-close it partway through, which leaves a half-extracted game and
     * turns a working install into "the port doesn't work" - it is the single
     * most common report there is, and the install had been succeeding every
     * time.
     *
     * The engine's own installer used to draw a progress bar here; the path
     * that takes the file from this screen skips that wizard entirely. Until
     * that reports progress, saying so plainly beforehand is what stops people
     * interrupting it.
     */
    private fun confirmLongInstall(args: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("This takes several minutes")
            .setMessage(
                "The game is about to unpack around 6 GB from your disc image.\n\n" +
                    "The screen will go BLACK and stay black for the whole time - " +
                    "usually 3 to 15 minutes depending on the device. There is no " +
                    "progress bar yet. It has not frozen.\n\n" +
                    "Do not close the app or let the screen turn off until the game " +
                    "appears, or the install will be left half-finished and you will " +
                    "have to start again."
            )
            .setPositiveButton("Start the install") { _, _ -> startGame(args) }
            .setNegativeButton("Cancel", null)
            .setCancelable(false)
            .show()
    }

    /**
     * Map packs: what is installed, and the way in.
     *
     * Installing one used to mean copying a folder into Android/data with a
     * file manager, and on a phone that is a coin toss - a file written there
     * by a root or Shizuku file manager belongs to that tool, and the game is
     * refused when it opens it. Everything the app writes itself is readable by
     * the game, so the app does the copying.
     */
    private fun showMapPacks() {
        if (busy) return
        val packs = MapPacks.installed(this)
        if (packs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Map packs")
                .setMessage(
                    "No map packs are installed.\n\n" +
                        "A pack is a folder holding a .big file and a .header file. " +
                        "Extract it somewhere ordinary first - the Download folder is " +
                        "easiest - then choose that folder here and the app will copy " +
                        "it in.\n\n" +
                        "Do not copy it into the game's own folder yourself: files put " +
                        "there by a file manager usually belong to the file manager, and " +
                        "the game is not allowed to read them."
                )
                .setPositiveButton("Choose a pack folder…") { _, _ -> pickMapPack() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val labels = packs.map { pack ->
            val name = pack.displayName?.takeIf { it != pack.name }?.let { "${pack.name}  ($it)" }
                ?: pack.name
            "$name\n${MapPacks.mb(pack.bytes)}"
        } + "Install another pack…"
        AlertDialog.Builder(this)
            .setTitle(if (packs.size == 1) "1 map pack installed" else "${packs.size} map packs installed")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == packs.size) pickMapPack() else confirmRemoveMapPack(packs[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickMapPack() {
        // A folder, not a file: the pack is two files that have to stay
        // together, and the folder's name is part of what the game looks for.
        // Launched as itself rather than through a chooser: the folder picker
        // is the system's own, and wrapping it produces a list of document
        // providers to pick from before the player can pick a folder.
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            startActivityForResult(intent, REQUEST_PACK)
        } catch (e: Exception) {
            Log.e(TAG, "no folder picker available", e)
            status.text = "This device has no folder picker available."
        }
    }

    private fun installMapPack(tree: Uri) {
        if (busy) return
        busy = true
        status.text = "Copying the map pack…"
        Thread {
            var lastShown = 0L
            val outcome = runCatching {
                MapPacks.install(this, tree) { copied, total ->
                    // Only when the number on screen would change: the status
                    // view is on the UI thread and a 200 MB pack would post
                    // thousands of identical updates.
                    val percent = if (total > 0) copied * 100 / total else 0
                    if (percent != lastShown) {
                        lastShown = percent
                        runOnUiThread { status.text = "Copying the map pack… $percent%" }
                    }
                }
            }
            runOnUiThread {
                busy = false
                outcome.fold(
                    onSuccess = { pack ->
                        refresh()
                        status.text = buildString {
                            appendLine("Installed ${pack.name}.")
                            pack.displayName?.takeIf { it != pack.name }?.let { appendLine(it) }
                            appendLine()
                            appendLine(
                                "It is staged into the game the next time you press Play. " +
                                    "If more than one pack is installed, the game asks which " +
                                    "one to load."
                            )
                        }
                    },
                    onFailure = { e ->
                        refresh()
                        status.text = "The map pack was not installed.\n\n" +
                            (e.message ?: e.toString())
                    }
                )
            }
        }.start()
    }

    /**
     * Removing a pack takes the staged copy with it. The engine only clears
     * staged packs it can still see in the drop folder, so deleting the folder
     * alone would leave the pack installed and still loading.
     */
    private fun confirmRemoveMapPack(pack: MapPacks.Pack) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${pack.name}?")
            .setMessage(
                "${MapPacks.mb(pack.bytes)} is deleted from this phone, along with the " +
                    "copy staged into the game.\n\nYour own save data is not touched."
            )
            .setPositiveButton("Remove") { _, _ ->
                val removed = runCatching { MapPacks.remove(this, pack) }
                refresh()
                status.text = removed.fold(
                    onSuccess = { "Removed ${pack.name}." },
                    onFailure = { e -> "${pack.name} could not be removed.\n\n${e.message ?: e}" }
                )
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun startGame(extraArgs: List<String> = emptyList()) {
        GameData.userDir(this).mkdirs()
        GameData.gameDir(this).mkdirs()
        val root = GameData.root(this)
        val args = buildList {
            addAll(extraArgs)
            // Tell the engine where the files actually are rather than letting
            // it work it out again. It asks SDL for the external files
            // directory, which is the one that can be missing - and if the two
            // sides disagree the game looks for a disc that was extracted
            // somewhere else. The engine replaces its own default for a key the
            // activity supplies, so these do not collide.
            add("--game_data_root=${File(root, "game").absolutePath}")
            add("--user_data_root=${File(root, "user").absolutePath}")
            add("--log_file=${File(root, "skate3.log").absolutePath}")
            // Only on a first run: the engine writes its own settings after
            // that, and re-applying a preset every launch would silently undo
            // whatever was changed in the menu.
            if (!File(GameData.userDir(this@SetupActivity), "settings.toml").exists()) {
                add("--skate3_performance_profile=${proposedProfile()}")
            }
        }
        startActivity(Intent(this, Skate3Activity::class.java).apply {
            putExtra(Skate3Activity.EXTRA_ARGUMENTS, args.toTypedArray())
        })
    }

    /**
     * A starting quality preset from the amount of RAM, which is the closest
     * proxy Android offers for how much phone this is. The player can change
     * it in the game's own settings afterwards and that choice sticks.
     */
    private fun proposedProfile(): String {
        val info = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val gb = info.totalMem.toDouble() / (1024 * 1024 * 1024)
        return if (gb >= 5.5) "performance" else "potato"
    }

    companion object {
        private const val TAG = "skate3"
        private const val REQUEST_ISO = 0x5301
        private const val REQUEST_TU = 0x5302
        private const val REQUEST_PACK = 0x5303
    }

    /**
     * Gathers the report and offers to send it. Written to the cache and
     * shared through a content URI, because the folder the logs actually live
     * in is under `Android/data`, which recent Android versions will not let
     * a file manager or a messaging app open.
     */
    private fun saveReport() {
        if (busy) return
        busy = true
        status.text = "Gathering the report…"
        Thread {
            val outcome = runCatching { Diagnostics.write(this) }
            runOnUiThread {
                busy = false
                outcome.fold(
                    onSuccess = { file -> shareReport(file) },
                    onFailure = { e ->
                        status.text = "The report could not be written.\n\n${e.message ?: e}"
                    }
                )
            }
        }.start()
    }

    private fun shareReport(file: File) {
        status.text = "Report saved:\n${file.absolutePath}\n\n" +
            String.format("%.1f KB. Send it with the report of the problem.", file.length() / 1024.0)
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.reports", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Skate 3 for Android - diagnostic report")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Send the report"))
        } catch (e: Exception) {
            // The file is written either way, and the path is already on
            // screen, so this is a downgrade rather than a failure.
            Log.e(TAG, "could not share the report", e)
        }
    }

    private fun copyDiagnostics() {
        val info = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val text = buildString {
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            appendLine("SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            appendLine("ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("RAM ${info.totalMem / (1024 * 1024)} MB")
            appendLine("Game files ${GameData.gameDir(this@SetupActivity)}")
            appendLine("Ready ${GameData.isReadyToPlay(this@SetupActivity)}")
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("skate3", text))
        status.text = text
    }
}
