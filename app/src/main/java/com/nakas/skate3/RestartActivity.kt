package com.nakas.skate3

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Relaunches the game after a setting that only takes effect at startup.
 *
 * An app process cannot exec itself, and the game process is about to exit, so
 * the relaunch has to be issued from somewhere that outlives it: this activity
 * declares android:process=":restart" and therefore runs in its own process.
 * It starts a fresh task and finishes; by then the old process has gone.
 *
 * It used to wait a flat 750 ms for that. The game does not shut down in
 * 750 ms - a tester timed "Quit" from the settings menu at five to ten seconds,
 * and the same teardown runs here - so the relaunch fired into a process that
 * was still tearing down its renderer, and FLAG_ACTIVITY_CLEAR_TASK then
 * destroyed the surface underneath it. That is the ANR reported against
 * "Apply & Restart": not the restart failing, the restart arriving too early.
 *
 * So this watches for the process to actually go, and only then relaunches.
 */
class RestartActivity : Activity() {

    /** How often to look. Cheap: one binder call against a short list. */
    private val pollMs = 250L

    /**
     * How long to wait before giving up and launching anyway. Well past the
     * ten seconds a slow quit has been measured at, and a relaunch after the
     * ceiling is still better than sitting on a dead screen forever.
     */
    private val ceilingMs = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private var waitedMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        poll()
    }

    private fun poll() {
        if (!gameProcessAlive() || waitedMs >= ceilingMs) {
            Log.i(TAG, "relaunching after ${waitedMs}ms" +
                if (gameProcessAlive()) " (the game process is still up; going anyway)" else "")
            relaunch()
            return
        }
        waitedMs += pollMs
        handler.postDelayed({ poll() }, pollMs)
    }

    /**
     * Whether the game's own process is still running.
     *
     * getRunningAppProcesses only reports this app's own processes on modern
     * Android, which is all that is wanted: this one is ":restart", so anything
     * else in the list under the package name is the game still on its way out.
     * If the list cannot be read, report it gone rather than waiting out the
     * whole ceiling for an answer that is never coming.
     */
    private fun gameProcessAlive(): Boolean = try {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.any { it.processName == packageName } ?: false
    } catch (e: Exception) {
        Log.w(TAG, "could not read the process list", e)
        false
    }

    private fun relaunch() {
        try {
            startActivity(
                Intent(this, SetupActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "could not relaunch", e)
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "skate3"
    }
}
