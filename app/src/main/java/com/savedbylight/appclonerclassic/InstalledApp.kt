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
    val cloneSuffix: String,
    val icon: Drawable,
    /** Non-null when this entry is a ROM clone-profile install (same package, other user). */
    val profileUserId: Int? = null
) {
    val isRomProfileClone: Boolean get() = profileUserId != null
}
