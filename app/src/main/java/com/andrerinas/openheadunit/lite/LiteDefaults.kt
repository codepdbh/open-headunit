package com.andrerinas.openheadunit.lite

import android.content.Context
import androidx.core.content.edit
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.main.OnboardingActivity
import com.andrerinas.openheadunit.utils.Settings

/**
 * Fixed settings of the lite edition (flavor "lite"), written on every start so nothing needs configuring.
 * They are the values verified with AionLink Sonda on 2026-11-07/08: Self Mode only, 1080p, H.264, 30 FPS
 * (AionLink's encoder rate), landscape, audio sink on and the AudioTrack backend. AionLink scales the picture
 * to the car's 1920x960. The safety notice is not accepted here: [LiteActivity] asks the user.
 */
object LiteDefaults {
    val enabled: Boolean get() = BuildConfig.FLAVOR == "lite"

    fun apply(context: Context) {
        if (!enabled) return
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit {
            putStringSet("connection-modes", setOf(Settings.ConnectionMode.SELF.key))
            putInt("resolutionId", Settings.Resolution._1920x1080.id)
            putString("video-codec", "H.264")
            putInt("fps-limit", 30)
            putInt(Settings.KEY_SCREEN_ORIENTATION, Settings.ScreenOrientation.LANDSCAPE.value)
            putInt("view-mode", 1)
            putString("display-size-preset", "LARGE_11_PLUS")
            putBoolean("enable-audio-sink", true)
            putBoolean("use-aaudio-output", false)
            putBoolean("auto-start-self-mode", false)
            putBoolean("has-completed-setup-wizard", true)
            putInt("onboarding-version", OnboardingActivity.CURRENT_ONBOARDING_VERSION)
            putBoolean("pending-renderer-confirm", false)
        }
    }
}
