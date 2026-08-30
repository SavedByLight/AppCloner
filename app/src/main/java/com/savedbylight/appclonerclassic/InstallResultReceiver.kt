package com.savedbylight.appclonerclassic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives PackageInstaller session status callbacks.
 *
 * Using a BroadcastReceiver (instead of launching LogActivity directly via
 * PendingIntent.getActivity) is more reliable on OEM skins that aggressively
 * kill background activities after the system install confirmation dialog
 * appears. The receiver logs the outcome and, when the status is
 * STATUS_PENDING_USER_ACTION, forwards the confirmation Intent so the user
 * still sees the install UI.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        if (intent.action != ACTION_INSTALL_COMPLETE) return

        // Ensure Logger is ready even if the process was cold-started by the system.
        Logger.init(context.applicationContext)

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val otherPkg = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)
        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Logger.log("INSTALL SUCCESS: Application cloned and installed successfully!" +
                    (if (!targetPkg.isNullOrEmpty()) " package=$targetPkg" else ""))
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Logger.log("INSTALL PENDING_USER_ACTION: launching system confirmation UI")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(it)
                    } catch (e: Exception) {
                        Logger.log("Failed to launch confirmation UI: ${e.message}")
                    }
                }
            }

            PackageInstaller.STATUS_FAILURE -> {
                Logger.log("INSTALL FAILED (STATUS_FAILURE): $message")
                logManualFallbackHint(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Logger.log("INSTALL ABORTED: $message")
                val msg = (message ?: "").lowercase()
                when {
                    "user rejected" in msg || "rejected permissions" in msg -> {
                        Logger.log(
                            "The system reported that the install was rejected (permissions / confirmation). " +
                                "Common causes: (1) you tapped Cancel on the install dialog, " +
                                "(2) AppClonerClassic does not have \"Install unknown apps\" enabled " +
                                "(Settings → Apps → Special app access → Install unknown apps), " +
                                "(3) an OEM policy (Samsung Auto Blocker, Play Protect, Knox) blocked it."
                        )
                        Logger.log(
                            "Fix: enable Install unknown apps for AppClonerClassic, turn off Auto Blocker / " +
                                "Play Protect scan temporarily, then clone again — or use the manual fallback below."
                        )
                    }
                    "verification" in msg -> {
                        Logger.log(
                            "OEM package verifier (Samsung Auto Blocker, Play Protect, Knox, etc.) rejected " +
                                "a multi-split session from a non-Play-Store installer. Check Settings > Security > " +
                                "Auto Blocker and Play Protect's \"scan before install\" toggle."
                        )
                    }
                    else -> {
                        Logger.log(
                            "Install was aborted. If you did not cancel, check Install unknown apps permission " +
                                "and OEM blockers (Auto Blocker / Play Protect)."
                        )
                    }
                }
                logManualFallbackHint(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Logger.log("INSTALL BLOCKED: Installation blocked by device policy or user settings. $message")
                logManualFallbackHint(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Logger.log("INSTALL FAILED (INVALID APK/MANIFEST): $message")
                logManualFallbackHint(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Logger.log("INSTALL CONFLICT: Package name or provider authority collision. $message")
                if (!otherPkg.isNullOrEmpty()) {
                    Logger.log("Conflicting installed package: $otherPkg")
                }
                Logger.log(
                    "Tip: the cloner now auto-picks the next free .cloneN suffix, so a fresh clone " +
                        "should succeed. If you still see this, uninstall the conflicting package first."
                )
                logManualFallbackHint(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Logger.log("INSTALL INCOMPATIBLE: ABI, SDK version, or signature mismatch. $message")
                Logger.log(
                    "If this is a signature mismatch on an existing .cloneN package, the auto " +
                        "suffix picker should have avoided it — try again or uninstall the old clone."
                )
                logManualFallbackHint(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Logger.log("INSTALL FAILED: Insufficient system storage space. $message")
            }

            else -> {
                if (status != -1) {
                    Logger.log("INSTALL CALLBACK (status=$status, session=$sessionId): $message")
                }
            }
        }
    }

    private fun logManualFallbackHint(targetPkg: String?) {
        if (targetPkg.isNullOrEmpty()) return
        Logger.log(
            "Manual install fallback: the signed APKs were exported to " +
                "Download/AppCloner/$targetPkg/ " +
                "(or /storage/emulated/0/Download/AppCloner/$targetPkg/) — " +
                "use \"adb install-multiple base.apk split_*.apk\" or SAI / APKMirror Installer."
        )
    }

    private fun contextPackage(): String = "com.savedbylight.appclonerclassic"

    companion object {
        const val ACTION_INSTALL_COMPLETE = "com.savedbylight.appclonerclassic.INSTALL_COMPLETE"
        const val EXTRA_TARGET_PACKAGE = "extra_target_package"
        const val EXTRA_SESSION_ID = "extra_session_id"
    }
}
