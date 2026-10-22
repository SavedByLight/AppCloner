package com.savedbylight.appclonerclassic

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast

object ShortcutHelper {

    /**
     * Requests pinning a home-screen shortcut for [clone].
     * On API 26+ uses ShortcutManager; older devices use the legacy INSTALL_SHORTCUT broadcast.
     */
    fun pinCloneShortcut(context: Context, clone: CloneInfo) {
        val label = if (clone.isRomProfileClone) {
            "${clone.label} (${clone.cloneSuffix})"
        } else {
            "${clone.label} (${clone.cloneSuffix})"
        }.take(40)

        val launchIntent = Intent(context, CloneShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(CloneShortcutActivity.EXTRA_PACKAGE, clone.packageName)
            putExtra(CloneShortcutActivity.EXTRA_USER_ID, clone.profileUserId ?: -1)
        }

        val icon = try {
            Icon.createWithBitmap(
                drawableToBitmap(clone.icon, 192)
            )
        } catch (_: Exception) {
            Icon.createWithResource(context, R.mipmap.ic_launcher)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = context.getSystemService(ShortcutManager::class.java)
            if (sm == null || !sm.isRequestPinShortcutSupported) {
                Toast.makeText(context, "This launcher doesn't support pinned shortcuts", Toast.LENGTH_LONG).show()
                return
            }
            val id = "clone_${clone.packageName}_${clone.profileUserId ?: 0}"
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(64)
            val info = ShortcutInfo.Builder(context, id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(icon)
                .setIntent(launchIntent)
                .build()
            val ok = sm.requestPinShortcut(info, null)
            Toast.makeText(
                context,
                if (ok) "Confirm the shortcut on your home screen"
                else "Could not request shortcut",
                Toast.LENGTH_LONG
            ).show()
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
                putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
                putExtra(Intent.EXTRA_SHORTCUT_ICON, drawableToBitmap(clone.icon, 96))
                putExtra("duplicate", false)
            }
            context.sendBroadcast(intent)
            Toast.makeText(context, "Shortcut requested", Toast.LENGTH_SHORT).show()
        }
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable, size: Int): android.graphics.Bitmap {
        if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
            return android.graphics.Bitmap.createScaledBitmap(drawable.bitmap, size, size, true)
        }
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }
}
