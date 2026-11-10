package com.andrerinas.openheadunit.lite

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.text.HtmlCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.audio.AionLinkAudioSink
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.Settings

/**
 * The only screen of the lite edition. There is nothing to configure ([LiteDefaults] fixes the settings):
 * it shows whether the phone is ready for AionLink Sonda, offers only the steps still missing (safety notice,
 * permissions) and can stop a running session. Sessions are started by AionLink Sonda through the surface bridge.
 * Colours match AionLink Sonda's dark theme.
 */
class LiteActivity : Activity() {
    private val dp get() = resources.displayMetrics.density
    private lateinit var dot: View
    private lateinit var stateTitle: TextView
    private lateinit var stateDetail: TextView
    private lateinit var checklist: LinearLayout
    private lateinit var stop: Button
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = px(20)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        column.addView(text(getString(R.string.lite_title), 26f, ON_BACKGROUND, bold = true))
        column.addView(text(getString(R.string.lite_subtitle), 14f, MUTED).apply { setPadding(0, px(4), 0, px(20)) })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad); background = rounded(CARD, 24)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(PRIMARY) } }
        header.addView(dot, LinearLayout.LayoutParams(px(14), px(14)).apply { marginEnd = px(12) })
        stateTitle = text("", 20f, ON_BACKGROUND, bold = true)
        header.addView(stateTitle)
        card.addView(header)
        stateDetail = text("", 14f, MUTED).apply { setPadding(0, px(8), 0, 0) }
        card.addView(stateDetail)
        column.addView(card)

        checklist = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, px(16), pad, px(16)); background = rounded(CHECKLIST, 24)
        }
        column.addView(checklist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(16) })

        column.addView(button(getString(R.string.lite_aa_settings), filled = true) { openAndroidAuto() },
            LinearLayout.LayoutParams(-1, px(56)).apply { topMargin = px(20) })
        column.addView(text(getString(R.string.lite_hint_server), 13f, MUTED).apply { setPadding(px(4), px(8), px(4), 0) })
        stop = button(getString(R.string.lite_stop), filled = false) {
            startService(Intent(this, AapService::class.java).setAction(AapService.ACTION_STOP_SERVICE))
            handler.postDelayed({ refresh() }, 500)
        }
        column.addView(stop, LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(12) })

        column.addView(text(getString(R.string.lite_license, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA), 11f, MUTED).apply {
            setPadding(px(4), px(28), px(4), 0)
        })
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; setBackgroundColor(BACKGROUND); addView(column) })
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val accepted = Settings(this).hasAcceptedDisclaimer
        val missing = AppPermissions.missingNormalPermissions(this)
        val androidAuto = installed(ANDROID_AUTO)
        val sonda = installed(AIONLINK_SONDA)
        val live = App.provide(this).commManager.connectionState.value is CommManager.ConnectionState.TransportStarted
        val ready = accepted && missing.isEmpty() && androidAuto && sonda
        val (color, title, detail) = when {
            live && AionLinkAudioSink.attached -> Triple(LIVE, R.string.lite_state_live, R.string.lite_state_live_detail)
            live -> Triple(LIVE, R.string.lite_state_running, R.string.lite_state_running_detail)
            ready -> Triple(PRIMARY, R.string.lite_state_ready, R.string.lite_state_ready_detail)
            else -> Triple(BUSY, R.string.lite_state_setup, R.string.lite_state_setup_detail)
        }
        (dot.background as GradientDrawable).setColor(color)
        stateTitle.setText(title)
        stateDetail.setText(detail)
        stop.visibility = if (live) View.VISIBLE else View.GONE

        checklist.removeAllViews()
        checklist.addView(text(getString(R.string.lite_checklist), 15f, ON_CHECKLIST, bold = true).apply { setPadding(0, 0, 0, px(4)) })
        row(getString(R.string.lite_item_notice), accepted, getString(R.string.lite_action_read)) { showNotice() }
        row(getString(R.string.lite_item_permissions), missing.isEmpty(), getString(R.string.lite_action_allow)) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
        row(getString(R.string.lite_item_aa), androidAuto, null, null)
        row(getString(R.string.lite_item_sonda), sonda, null, null)
    }

    private fun row(label: String, done: Boolean, action: String?, onClick: (() -> Unit)?) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(10), 0, 0)
        }
        line.addView(text(if (done) "✓" else "•", 18f, if (done) LIVE else ON_CHECKLIST, bold = true), LinearLayout.LayoutParams(px(28), -2))
        line.addView(text(label, 15f, ON_CHECKLIST), LinearLayout.LayoutParams(0, -2, 1f))
        if (!done && action != null && onClick != null) line.addView(button(action, filled = true, compact = true) { onClick() })
        checklist.addView(line)
    }

    private fun showNotice() {
        AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_header)
            .setMessage(HtmlCompat.fromHtml(getString(R.string.disclaimer_text), HtmlCompat.FROM_HTML_MODE_COMPACT))
            .setPositiveButton(R.string.disclaimer_accept) { _, _ -> Settings(this).hasAcceptedDisclaimer = true; refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Android Auto's own settings hold "Start head unit server"; many phones show no launcher icon for it. */
    private fun openAndroidAuto() {
        val settings = Intent().setClassName(ANDROID_AUTO, "$ANDROID_AUTO.companion.settings.DefaultSettingsActivity")
        val intent = if (settings.resolveActivity(packageManager) != null) settings
            else packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
        try { intent?.let { startActivity(it) } } catch (_: RuntimeException) { }
    }

    private fun installed(name: String) = try {
        packageManager.getPackageInfo(name, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private fun px(value: Int) = (value * dp).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius * dp }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, filled: Boolean, compact: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = if (compact) 13f else 16f; stateListAnimator = null
        setTextColor(if (filled) ON_PRIMARY else STOP)
        background = GradientDrawable().apply {
            cornerRadius = 28 * dp
            if (filled) setColor(PRIMARY) else { setColor(Color.TRANSPARENT); setStroke(px(1), STOP) }
        }
        backgroundTintList = null as ColorStateList?
        if (compact) { minHeight = px(36); minimumHeight = px(36); setPadding(px(16), 0, px(16), 0) }
        setOnClickListener { onClick() }
    }

    private companion object {
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"
        const val AIONLINK_SONDA = "org.aionlink.open.lab"
        // Same palette as AionLink Sonda's dark theme (HomeUi.kt in AionLink Open).
        val BACKGROUND = 0xFF0B1220.toInt()
        val CARD = 0xFF1C2841.toInt()
        val CHECKLIST = 0xFF14315F.toInt()
        val ON_BACKGROUND = 0xFFE2E6EE.toInt()
        val ON_CHECKLIST = 0xFFD8E2FF.toInt()
        val MUTED = 0xFFB7C0D2.toInt()
        val PRIMARY = 0xFF8AB4FF.toInt()
        val ON_PRIMARY = 0xFF002E6B.toInt()
        val LIVE = 0xFF2FBF71.toInt()
        val BUSY = 0xFFF2A93B.toInt()
        val STOP = 0xFFFF8A80.toInt()
    }
}
