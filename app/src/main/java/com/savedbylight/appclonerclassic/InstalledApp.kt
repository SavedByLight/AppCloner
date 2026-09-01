package com.savedbylight.appclonerclassic

import android.graphics.drawable.Drawable

data class InstalledApp(
    val label: String,
    val packageName: String,
    val sourceApkPath: String,
    val icon: Drawable,
    val existingCloneCount: Int = 0
)

data class CloneInfo(
    val label: String,
    val packageName: String,
    val basePackageName: String,
    val cloneNumber: Int,
    val icon: Drawable
)
