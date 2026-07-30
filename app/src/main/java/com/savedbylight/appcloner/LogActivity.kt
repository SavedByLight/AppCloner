package com.savedbylight.appcloner

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LogActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tvLog = findViewById(R.id.tvLog)

        // Populate terminal view with accumulated execution logs
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

        if (intent.action == "com.savedbylight.appcloner.INSTALL_COMPLETE") {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val otherPkg = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)

            when (status) {
                PackageInstaller.STATUS_SUCCESS -> {
                    Logger.log("INSTALL SUCCESS: Application cloned and installed successfully!")
                }

                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    Logger.log("USER ACTION REQUIRED: Displaying package installer prompt...")
                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }

                    confirmIntent?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(it)
                    }
                }

                PackageInstaller.STATUS_FAILURE -> {
                    Logger.log("INSTALL FAILED (STATUS_FAILURE): $message")
                }

                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    Logger.log("INSTALL BLOCKED: Installation blocked by device policy or user settings. $message")
                }

                PackageInstaller.STATUS_FAILURE_INVALID -> {
                    Logger.log("INSTALL FAILED (INVALID APK/MANIFEST): $message")
                }

                PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                    Logger.log("INSTALL CONFLICT: Package name or provider authority collision. $message")
                    if (!otherPkg.isNullOrEmpty()) {
                        Logger.log("Conflicting installed package: $otherPkg")
                    }
                }

                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                    Logger.log("INSTALL INCOMPATIBLE: ABI, SDK version, or signature mismatch. $message")
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
    }

    private fun updateLogDisplay() {
        if (::tvLog.isInitialized) {
            tvLog.text = Logger.getLogs()
        }
    }
}
