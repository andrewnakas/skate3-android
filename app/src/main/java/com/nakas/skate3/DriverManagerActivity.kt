package com.nakas.skate3

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView

/** Driver management stays in the launcher, before the game owns a surface. */
class DriverManagerActivity : Activity() {
    private var pendingId: String? = null
    private var options = emptyList<DriverBridge.DriverOption>()
    private var busy = false
    private lateinit var list: ListView
    private lateinit var message: TextView
    private lateinit var selectedText: TextView
    private lateinit var importButton: Button
    private lateinit var applyButton: Button
    private lateinit var removeButton: Button
    private lateinit var licenceButton: Button
    private lateinit var backButton: Button
    private val rows = object : BaseAdapter() {
        override fun getCount() = options.size
        override fun getItem(position: Int) = options[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val option = getItem(position)
            return LinearLayout(this@DriverManagerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(10), dp(8), dp(10))
                addView(RadioButton(context).apply {
                    isChecked = option.id == pendingId
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(option.label, 16f))
                    addView(text(option.detail, 12f, "#B9C2CA"))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                contentDescription = "${option.label}. ${option.detail}" +
                    if (option.id == pendingId) ". Selected" else ""
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        pendingId = state?.getString("pending_driver") ?: DriverBridge.selected(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(Color.parseColor("#101418"))
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text("GPU drivers", 26f))
        selectedText = text("", 13f, "#9ED8F5").apply { setPadding(0, dp(6), 0, dp(10)) }
        left.addView(selectedText)
        list = ListView(this).apply {
            adapter = rows
            choiceMode = ListView.CHOICE_MODE_SINGLE
            dividerHeight = dp(1)
            setOnItemClickListener { _, _, position, _ ->
                if (!busy) {
                    pendingId = options[position].id
                    rows.notifyDataSetChanged()
                    updateButtons()
                }
            }
        }
        left.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(left, LinearLayout.LayoutParams(0, -1, 1.4f))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        actions.addView(text("Import compatible Android ARM64 Turnip driver ZIPs. Your game files and saves stay in place when you switch drivers.", 13f))
        importButton = button("Import driver ZIP…") { pickZip() }
        applyButton = button("Apply and restart") { applySelection() }
        removeButton = button("Remove highlighted driver") { removeHighlighted() }
        licenceButton = button("Driver licences") { showLicences() }
        backButton = button("Back") { finish() }
        listOf(importButton, applyButton, removeButton, licenceButton, backButton).forEach { actions.addView(it) }
        message = text("", 13f, "#9ED8F5").apply { setPadding(0, dp(12), 0, 0) }
        actions.addView(message)
        root.addView(ScrollView(this).apply { addView(actions) }, LinearLayout.LayoutParams(0, -1, 1f))
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::list.isInitialized && !busy) refresh()
    }

    @Deprecated("Legacy Android back callback")
    override fun onBackPressed() {
        // Match the disabled on-screen Back button while the import owns the
        // store. Opening another manager must not wait for its I/O on the UI.
        if (!busy) super.onBackPressed()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("pending_driver", pendingId)
        super.onSaveInstanceState(out)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: String = "#FFFFFF") = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setOnClickListener { if (!busy) action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun refresh() {
        options = DriverBridge.choices(this)
        if (options.none { it.id == pendingId }) pendingId = DriverBridge.selected(this)
        selectedText.text = "Applied: ${DriverBridge.label(this, DriverBridge.selected(this))}"
        rows.notifyDataSetChanged()
        list.setItemChecked(options.indexOfFirst { it.id == pendingId }, true)
        updateButtons()
    }

    private fun updateButtons() {
        importButton.isEnabled = !busy
        applyButton.isEnabled = !busy && options.any { it.id == pendingId }
        removeButton.isEnabled = !busy && options.any { it.id == pendingId && it.imported }
        licenceButton.isEnabled = !busy
        backButton.isEnabled = !busy
        list.isEnabled = !busy
    }

    private fun pickZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Choose a Turnip driver ZIP"), REQUEST_DRIVER)
        } catch (error: Exception) {
            message.text = "Could not open the file picker: ${error.message}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_DRIVER || resultCode != RESULT_OK) return
        data?.data?.let { importZip(it) }
    }

    private fun importZip(uri: Uri) {
        busy = true
        updateButtons()
        message.text = "Importing and checking the driver ZIP…"
        val appContext = applicationContext
        Thread {
            val outcome = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use {
                    DriverStore.importZip(appContext, it)
                } ?: error("The selected file could not be opened.")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                outcome.fold(onSuccess = {
                    pendingId = it.id
                    refresh()
                    message.text = "${it.label} is ready to select. Tap Apply and restart to use it."
                    list.smoothScrollToPosition(options.indexOfFirst { option -> option.id == it.id })
                }, onFailure = {
                    refresh()
                    message.text = "Import failed: ${it.message}\nYour applied driver has not changed."
                })
            }
        }.start()
    }

    private fun applySelection() {
        val chosen = pendingId ?: return
        // T30 ships as a zip inside the APK and has to be unpacked and hashed
        // before anything can load it - 18 MB of work. Doing it here means a
        // player who never picks Turnip never pays for it, and it cannot be
        // interrupted half way by the process kill below, which is what a
        // background prepare on screen entry would risk. Skipped once a driver
        // check has loaded the proxy into this process, because prepareBundle
        // will not touch driver files after that; the restart stages it.
        if (chosen == DriverBridge.T30 && !busy && !DriverBridge.proxyLoaded()) {
            busy = true
            updateButtons()
            message.text = "Preparing Turnip T30…"
            val appContext = applicationContext
            Thread {
                val prepared = runCatching { DriverBridge.prepareBundle(appContext) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    updateButtons()
                    prepared.fold(
                        onSuccess = { commit(chosen) },
                        onFailure = {
                            message.text = "Could not prepare Turnip T30: ${it.message}\n" +
                                "Your applied driver has not changed."
                        })
                }
            }.start()
            return
        }
        commit(chosen)
    }

    /** Save the selection and bounce the process, which is the only way a driver changes. */
    private fun commit(chosen: String) {
        try {
            DriverBridge.select(this, chosen)
            startActivity(Intent(this, RestartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (error: Exception) {
            message.text = "Could not apply the driver: ${error.message}"
        }
    }

    private fun removeHighlighted() {
        val chosen = options.firstOrNull { it.id == pendingId && it.imported } ?: return
        if (chosen.id == DriverBridge.selected(this)) {
            message.text = "Apply another driver first, then return here to remove ${chosen.label}."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Remove ${chosen.label}?")
            .setMessage("You can import its ZIP again later.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                runCatching { DriverBridge.removeImported(this, chosen.id) }.fold(onSuccess = {
                    pendingId = DriverBridge.selected(this)
                    refresh()
                    message.text = "Driver removed."
                }, onFailure = { message.text = "Could not remove the driver: ${it.message}" })
            }.show()
    }

    private fun showLicences() {
        val notice = runCatching {
            assets.open("third-party/NOTICE.txt").use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrElse { "Could not read the notices: ${it.message}" }
        AlertDialog.Builder(this)
            .setTitle("Driver licences")
            .setMessage(notice)
            .setPositiveButton("Close", null)
            .show()
    }

    companion object { private const val REQUEST_DRIVER = 0x5303 }
}
