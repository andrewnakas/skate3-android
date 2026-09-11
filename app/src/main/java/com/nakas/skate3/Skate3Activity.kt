package com.nakas.skate3

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import org.libsdl.app.SDLActivity

/**
 * The game itself.
 *
 * SDL owns the window, the event loop and the native thread; this class only
 * supplies the things SDL asks its host for (which library holds SDL_main,
 * what arguments to pass it) and the two services the engine calls back into
 * Java for: the system document picker, and relaunching after a setting change.
 */
class Skate3Activity : SDLActivity() {

    /**
     * One shared object. SDL3 and the rexglue runtime are linked statically
     * into libmain.so, so there is no libSDL3.so and no librexruntime.so to
     * load first. SDL derives the library holding SDL_main from the last name
     * in this list.
     */
    override fun getLibraries(): Array<String> = arrayOf("main")

    /**
     * Passed to SDL_main as argv[1..]. Only ever real cvar names: the argument
     * parser rejects an option it does not know, and that rejection is not
     * local - it discards every compiled-in default along with the bad line.
     * The engine treats anything named here as explicitly set by the operator,
     * so these replace its own defaults rather than sitting beside them.
     */
    override fun getArguments(): Array<String> =
        intent.getStringArrayExtra(EXTRA_ARGUMENTS) ?: emptyArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SDLActivity.mBrokenLibraries) return
        instance = this

        // Cover the black surface while the disc unpacks. The engine extracts
        // before it renders anything, so without this the player sees minutes
        // of black and reasonably assumes it has frozen.
        if (arguments.any { it.startsWith("--skate3_install_iso=") }) {
            InstallOverlay.attach(this)
        }

        // A controller-driven game sends no touch events for minutes at a
        // time, which reads to the system as an idle screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ask for the steady clock rather than the boost one. Skate 3 is a
        // sustained load: a few fast minutes followed by thermal throttling is
        // worse to play than a flat frame time from the start.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val power = getSystemService(POWER_SERVICE) as? PowerManager
            if (power?.isSustainedPerformanceModeSupported == true) {
                window.setSustainedPerformanceMode(true)
                Log.i(TAG, "sustained performance mode on")
            }
        }
        selectSixtyHertzDisplayMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // No onTrimMemory override here on purpose. SDLActivity already overrides
    // it and calls nativeLowMemory() at every level, so the engine's cache
    // release is reached through super - adding a second path would only fire
    // the same handler twice.

    /**
     * Notes that a session was running, so the launcher can tell a crash from
     * an ordinary exit.
     *
     * Being killed in the background leaves nothing behind: no crash report, no
     * native fault, just the launcher on screen next time. That is
     * indistinguishable from having quit on purpose, which is why the report
     * for it reads as "either frozen or crashed, it appears to just crash most
     * of the time" - the tester could not tell either.
     */
    override fun onResume() {
        super.onResume()
        GameData.markSessionRunning(this, true)
    }

    override fun onDestroy() {
        GameData.markSessionRunning(this, false)
        super.onDestroy()
    }

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    /**
     * Pin the panel to 60 Hz.
     *
     * The presenter runs FIFO, so a present is shown at the next refresh
     * whatever that rate is. On a 120 Hz panel a frame that misses its slot
     * waits 8.3 ms rather than 16.7, which sounds better and is not: the guest
     * is capped at 60, so half the refreshes have nothing new to show and the
     * cadence alternates between one and two refreshes per frame, which reads
     * as judder. A 60 Hz mode gives every frame exactly one refresh.
     */
    private fun selectSixtyHertzDisplayMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay
        } ?: return
        val current = display.mode ?: return
        val best = display.supportedModes
            ?.filter {
                it.physicalWidth == current.physicalWidth &&
                    it.physicalHeight == current.physicalHeight &&
                    it.refreshRate >= 59.0f && it.refreshRate < 61.0f
            }
            ?.minByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        Log.i(TAG, "preferred display mode ${best.modeId} at ${best.refreshRate} Hz")
    }

    // ---- Called from native code (see src/skate3_android_bridge.cpp) -------

    companion object {
        private const val TAG = "skate3"
        const val EXTRA_ARGUMENTS = "com.nakas.skate3.ARGUMENTS"

        @Volatile private var instance: Skate3Activity? = null

        /**
         * Refuses, deliberately, and says why.
         *
         * This used to open the system picker and block the calling thread
         * until the player chose. That thread is the one SDL runs the whole
         * game on, and opening the picker pauses this activity and destroys
         * its rendering surface - so the event that would release that surface
         * could never be handled, because the thread that handles it was the
         * one waiting. The renderer was left holding a window Android had
         * already freed, and the app died at the moment the picker appeared.
         * Reported from the field on more than one device.
         *
         * Files are chosen in SetupActivity now, before the game starts, where
         * there is no surface to lose and no thread to block. The engine
         * receives the choice as --skate3_install_iso / --skate3_install_tu.
         * Returning -1 makes it report that nothing was selected, which is
         * true and is a far better outcome than the crash.
         */
        @JvmStatic
        fun pickDocument(title: String): Int {
            Log.w(TAG, "in-game document picker declined ($title); files are chosen on the setup screen")
            return -1
        }

        /**
         * Downloads a file for the engine, which cannot do it itself on
         * Android. Returns null when the file arrived, or the reason it did
         * not - the engine shows that text to the player.
         *
         * Called from the engine's own thread and blocks it, which is what
         * that code already expects of its curl call.
         */
        @JvmStatic
        fun downloadTo(url: String, destination: String): String? = try {
            TitleUpdate.downloadTo(url, java.io.File(destination))
            Log.i(TAG, "downloaded $url -> $destination")
            null
        } catch (e: Exception) {
            Log.e(TAG, "download failed: $url", e)
            e.message ?: e.toString()
        }

        /** Schedules a relaunch; the caller still has to quit. */
        @JvmStatic
        fun requestRestart(): Boolean {
            val activity = instance ?: return false
            return try {
                activity.startActivity(
                    Intent(activity, RestartActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (e: Exception) {
                Log.e(TAG, "could not schedule a restart", e)
                false
            }
        }
    }
}
