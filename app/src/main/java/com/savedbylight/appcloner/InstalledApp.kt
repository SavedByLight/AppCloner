package com.savedbylight.appcloner

import android.graphics.drawable.Drawable

data class InstalledApp(
    val label: String,
    val packageName: String,
    val sourceApkPath: String,
    val icon: Drawable?
)
