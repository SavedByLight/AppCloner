package com.savedbylight.appclonerclassic

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Applies Material 3 dynamic color (Material You) when the device supports it
 * (API 31+). Brand accent from Theme.AppCloner is used as fallback on older
 * APIs or when the user has dynamic color disabled system-wide.
 */
class AppClonerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Overlay system wallpaper-derived colors onto Theme.AppCloner activities.
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
