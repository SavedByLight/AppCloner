package com.savedbylight.appclonerclassic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Trampoline for home-screen shortcuts.
 * Standalone clones: forwards to the clone package's launcher.
 * ROM profile clones: root am start --user <id>.
 */
class CloneShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE)?.trim().orEmpty()
        val userId = intent?.getIntExtra(EXTRA_USER_ID, -1) ?: -1
        if (pkg.isEmpty()) {
            Toast.makeText(this, "Invalid shortcut", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        try {
            if (userId > 0) {
                val ok = CloneProfileManager(this).launchApp(
                    pkg,
                    CloneProfile(userId, "profile $userId", true)
                )
                if (!ok) {
                    Toast.makeText(
                        this,
                        "Could not open $pkg in profile $userId",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                } else {
                    Toast.makeText(this, "No launcher for $pkg", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Logger.log("CloneShortcutActivity failed: ${e.message}")
            Toast.makeText(this, e.message ?: "Launch failed", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_USER_ID = "user_id"
    }
}
