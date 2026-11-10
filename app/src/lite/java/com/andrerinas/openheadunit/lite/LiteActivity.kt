package com.andrerinas.openheadunit.lite

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import androidx.core.text.HtmlCompat
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.decoder.audio.AionLinkAudioSink
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.Settings

/**
 * The only screen of the lite edition. There is nothing to configure ([LiteDefaults] fixes the settings):
 * it asks once for the safety notice and the permissions, shows whether the pieces AionLink Sonda needs are
 * present, and can stop a running session. Sessions are started by AionLink Sonda through the surface bridge.
 */
class LiteActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var notice: View
    private lateinit var permissions: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        column.addView(TextView(this).apply {
            setText(R.string.lite_title); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(this).apply { setText(R.string.lite_intro); setPadding(0, pad / 2, 0, pad) })

        notice = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setText(R.string.disclaimer_header); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = HtmlCompat.fromHtml(getString(R.string.disclaimer_text), HtmlCompat.FROM_HTML_MODE_COMPACT)
            })
            addView(Button(context).apply {
                setText(R.string.disclaimer_accept)
                setOnClickListener { Settings(this@LiteActivity).hasAcceptedDisclaimer = true; refresh() }
            })
        }
        column.addView(notice)

        permissions = Button(this).apply {
            setText(R.string.lite_grant_permissions)
            setOnClickListener {
                val missing = AppPermissions.missingNormalPermissions(this@LiteActivity)
                if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this@LiteActivity, missing.toTypedArray(), 1)
            }
        }
        column.addView(permissions)
        column.addView(Button(this).apply {
            setText(R.string.lite_open_android_auto)
            setOnClickListener {
                // Android Auto's own settings hold "Start head unit server"; many phones show no launcher icon for it.
                val settings = Intent().setClassName(ANDROID_AUTO, "$ANDROID_AUTO.companion.settings.DefaultSettingsActivity")
                val intent = if (settings.resolveActivity(packageManager) != null) settings
                    else packageManager.getLaunchIntentForPackage(ANDROID_AUTO)
                try { intent?.let { startActivity(it) } } catch (_: RuntimeException) { }
            }
        })
        column.addView(Button(this).apply {
            setText(R.string.lite_stop)
            setOnClickListener {
                startService(Intent(this@LiteActivity, AapService::class.java).setAction(AapService.ACTION_STOP_SERVICE))
                refresh()
            }
        })
        status = TextView(this).apply { setPadding(0, pad, 0, 0); setTextIsSelectable(true) }
        column.addView(status)
        column.addView(TextView(this).apply {
            text = getString(R.string.lite_license, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
            textSize = 12f; setPadding(0, pad, 0, 0)
        })
        // Keeps the title clear of the status bar now that apps draw edge to edge.
        setContentView(ScrollView(this).apply { fitsSystemWindows = true; addView(column) })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val accepted = Settings(this).hasAcceptedDisclaimer
        notice.visibility = if (accepted) View.GONE else View.VISIBLE
        val missing = AppPermissions.missingNormalPermissions(this)
        permissions.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        fun yes(value: Boolean) = getString(if (value) R.string.lite_yes else R.string.lite_no)
        status.text = getString(
            R.string.lite_status,
            yes(accepted),
            if (missing.isEmpty()) getString(R.string.lite_yes) else missing.joinToString { it.substringAfterLast('.') },
            yes(installed(ANDROID_AUTO)),
            yes(installed(AIONLINK_SONDA)),
            yes(AionLinkAudioSink.attached),
        )
    }

    private fun installed(name: String) = try {
        packageManager.getPackageInfo(name, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private companion object {
        const val ANDROID_AUTO = "com.google.android.projection.gearhead"
        const val AIONLINK_SONDA = "org.aionlink.open.lab"
    }
}
