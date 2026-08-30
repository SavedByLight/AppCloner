package com.savedbylight.appclonerclassic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var logListView: ListView
    private lateinit var logAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logListView = findViewById(R.id.logListView)
        logAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        logListView.adapter = logAdapter

        findViewById<Button>(R.id.copyLogButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Clone Log", Logger.all().joinToString("\n")))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            Logger.clear()
            updateLogDisplay()
        }

        // Populate list view with accumulated execution logs
        updateLogDisplay()

        // Handle intent if activity was launched directly by installation callback
        handleInstallCallback(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallCallback(intent)
    }

    private fun handleInstallCallback(intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return
        // Accept both legacy and current action strings from PackageInstaller callbacks
        if (action != "com.savedbylight.appclonerclassic.INSTALL_COMPLETE" &&
            action != "com.savedbylight.appcloner.INSTALL_COMPLETE"
        ) {
            return
        }

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val otherPkg = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)
        val targetPkg = intent.getStringExtra("extra_target_package")

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Logger.log(
                    "INSTALL SUCCESS: Application cloned and installed successfully!" +
                        (if (!targetPkg.isNullOrEmpty()) " package=$targetPkg" else "")
                )
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Logger.log("INSTALL PENDING_USER_ACTION: launching system confirmation UI")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    try {
                        // Must be started from an Activity context (this one) so OEMs
                        // do not auto-dismiss it as a background start.
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(confirmIntent)
                    } catch (e: Exception) {
                        Logger.log("Failed to launch confirmation UI: ${e.message}")
                        Logger.log(
                            "Open the install prompt manually if it appears in the notification shade, " +
                                "or use the exported APKs under clone_debug_export/."
                        )
                    }
                } else {
                    Logger.log("PENDING_USER_ACTION but EXTRA_INTENT was null — cannot show confirm UI")
                }
            }

            PackageInstaller.STATUS_FAILURE -> {
                Logger.log("INSTALL FAILED (STATUS_FAILURE): $message")
                logManualFallback(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Logger.log("INSTALL ABORTED: $message")
                val msg = (message ?: "").lowercase()
                if ("user rejected" in msg || "rejected permissions" in msg) {
                    Logger.log(
                        "Rejected in ~1s usually means the confirm dialog was blocked (not a real Cancel). " +
                            "Check: Settings → Apps → Special app access → Install unknown apps → AppClonerClassic = Allowed; " +
                            "turn off Samsung Auto Blocker and Play Protect scan; then retry."
                    )
                } else {
                    Logger.log(
                        "OEM package installer (Samsung Auto Blocker, Play Protect, Knox) may have " +
                            "rejected a multi-split session from a non-Play-Store installer."
                    )
                }
                logManualFallback(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Logger.log("INSTALL BLOCKED: Installation blocked by device policy or user settings. $message")
                logManualFallback(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Logger.log("INSTALL FAILED (INVALID APK/MANIFEST): $message")
                logManualFallback(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Logger.log("INSTALL CONFLICT: Package name or provider authority collision. $message")
                if (!otherPkg.isNullOrEmpty()) {
                    Logger.log("Conflicting installed package: $otherPkg")
                }
                logManualFallback(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Logger.log("INSTALL INCOMPATIBLE: ABI, SDK version, or signature mismatch. $message")
                logManualFallback(targetPkg)
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Logger.log("INSTALL FAILED: Insufficient system storage space. $message")
            }

            else -> {
                if (status != -1) {
                    Logger.log("INSTALL CALLBACK ($status): $message")
                }
            }
        }

        updateLogDisplay()
    }

    private fun logManualFallback(targetPkg: String?) {
        if (targetPkg.isNullOrEmpty()) return
        Logger.log(
            "Manual install fallback: Download/AppCloner/$targetPkg/ " +
                "(or /storage/emulated/0/Download/AppCloner/$targetPkg/) — " +
                "adb install-multiple base.apk split_*.apk or SAI / APKMirror Installer."
        )
    }

    private fun updateLogDisplay() {
        if (::logAdapter.isInitialized) {
            logAdapter.clear()
            logAdapter.addAll(Logger.all())
            logAdapter.notifyDataSetChanged()
        }
    }
}
