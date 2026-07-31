package com.savedbylight.appcloner.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.savedbylight.appcloner.LogActivity
import com.savedbylight.appcloner.Logger
import com.savedbylight.appcloner.R

class InstallerActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    private lateinit var summaryView: TextView
    private lateinit var detailsView: TextView
    private lateinit var statusView: TextView
    private lateinit var installButton: Button
    private lateinit var logsButton: Button
    private lateinit var progressBar: ProgressBar

    private var appLabel: String = "App"
    private var originalPackage: String = ""
    private var targetPackage: String = ""
    private var apkPaths: List<String> = emptyList()
    private var installStarted = false
    private var autoInstall = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_installer)

        titleView = findViewById(R.id.installerTitle)
        summaryView = findViewById(R.id.installerSummary)
        detailsView = findViewById(R.id.installerDetails)
        statusView = findViewById(R.id.installerStatus)
        installButton = findViewById(R.id.installButton)
        logsButton = findViewById(R.id.openLogsButton)
        progressBar = findViewById(R.id.installProgress)

        logsButton.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        installButton.setOnClickListener {
            startInstall()
        }

        bindRequest(intent)
        renderPendingUi()

        if (intent.action == InstallContracts.ACTION_INSTALL_STATUS) {
            handleStatusIntent(intent)
        } else if (autoInstall) {
            progressBar.post { startInstall() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (intent.action == InstallContracts.ACTION_INSTALL_STATUS) {
            handleStatusIntent(intent)
        } else {
            bindRequest(intent)
            renderPendingUi()
            if (autoInstall) {
                progressBar.post { startInstall() }
            }
        }
    }

    private fun bindRequest(intent: Intent) {
        appLabel = intent.getStringExtra(InstallContracts.EXTRA_APP_LABEL) ?: "App"
        originalPackage = intent.getStringExtra(InstallContracts.EXTRA_ORIGINAL_PACKAGE).orEmpty()
        targetPackage = intent.getStringExtra(InstallContracts.EXTRA_TARGET_PACKAGE).orEmpty()
        apkPaths = intent.getStringArrayExtra(InstallContracts.EXTRA_APK_PATHS)?.toList().orEmpty()
        autoInstall = intent.getBooleanExtra(InstallContracts.EXTRA_AUTO_INSTALL, false)

        titleView.text = "Install $appLabel"
        summaryView.text = if (apkPaths.isEmpty()) {
            "No clone files were supplied."
        } else {
            "Ready to install ${apkPaths.size} APK file${if (apkPaths.size == 1) "" else "s"}."
        }

        val details = buildString {
            if (originalPackage.isNotBlank()) {
                appendLine("Original package: $originalPackage")
            }
            if (targetPackage.isNotBlank()) {
                appendLine("Clone package: $targetPackage")
            }
            if (apkPaths.isNotEmpty()) {
                appendLine()
                appendLine("Staged APKs:")
                apkPaths.forEach { path ->
                    appendLine("• ${path.substringAfterLast('/')}")
                }
            }
        }.trim()

        detailsView.text = details.ifBlank { "The installer will stage and install the cloned APKs from this app." }
    }

    private fun renderPendingUi() {
        progressBar.visibility = View.GONE
        installButton.isEnabled = apkPaths.isNotEmpty()
        installButton.text = if (installStarted) "Retry install" else "Install clone"
        statusView.text = if (apkPaths.isNotEmpty()) {
            "Tap install to hand the cloned APKs to Android's package installer."
        } else {
            "Nothing to install."
        }
    }

    private fun startInstall() {
        if (apkPaths.isEmpty()) {
            statusView.text = "No APK files were provided for this clone."
            return
        }

        installStarted = true
        progressBar.visibility = View.VISIBLE
        installButton.isEnabled = false
        installButton.text = "Installing..."
        statusView.text = "Preparing install session..."

        try {
            InstallSession(this).commitInstall(
                appLabel = appLabel,
                originalPackage = originalPackage,
                targetPackage = targetPackage,
                apkPaths = apkPaths
            )
        } catch (e: Exception) {
            progressBar.visibility = View.GONE
            installButton.isEnabled = true
            installButton.text = "Retry install"
            statusView.text = "Install failed to start: ${e.message}"
            Logger.log("INSTALL START FAILED: ${e.message}")
        }
    }

    private fun handleStatusIntent(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val otherPkg = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Logger.log("INSTALL CALLBACK: user confirmation required")
                statusView.text = "Android needs one more confirmation step."
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
                progressBar.visibility = View.VISIBLE
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Logger.log("INSTALL SUCCESS: $appLabel installed successfully")
                statusView.text = "$appLabel installed successfully."
                progressBar.visibility = View.GONE
                installButton.isEnabled = true
                installButton.text = "Install another clone"
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Logger.log("INSTALL ABORTED: ${message.orEmpty()}")
                statusView.text = message ?: "Install was aborted."
                progressBar.visibility = View.GONE
                installButton.isEnabled = true
                installButton.text = "Retry install"
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Logger.log("INSTALL BLOCKED: ${message.orEmpty()}")
                statusView.text = message ?: "Installation was blocked by device policy."
                progressBar.visibility = View.GONE
                installButton.isEnabled = true
                installButton.text = "Retry install"
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                Logger.log("INSTALL CONFLICT: ${message.orEmpty()}")
                statusView.text = buildString {
                    append(message ?: "Installation conflict.")
                    if (!otherPkg.isNullOrBlank()) {
                        append(" Conflicting package: ")
                        append(otherPkg)
                    }
                }
                progressBar.visibility = View.GONE
                installButton.isEnabled = true
                installButton.text = "Retry install"
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                Logger.log("INSTALL INCOMPATIBLE: ${message.orEmpty()}")
                statusView.text = message ?: "The APK is incompatible with this device."
                progressBar.visibility = View.GONE
                installButton.isEnabled = true
                installButton.text = "Retry install"
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                Logger.log("INSTALL INVALID: ${message.orEmpty()}")
                statusView.text = message ?: "The APK or manifest is invalid."
                progressBar.visibility = View.GONE
                installButton.isEnabled = true
                installButton.text = "Retry install"
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Logger.log("INSTALL STORAGE ERROR: ${message.orEmpty()}")
                statusView.text = message ?: "Not enough storage for the installation."
                progressBar.visibility = View.GONE
                installButton.isEnabled = true
                installButton.text = "Retry install"
            }

            PackageInstaller.STATUS_FAILURE -> {
                Logger.log("INSTALL FAILED: ${message.orEmpty()}")
                statusView.text = message ?: "Installation failed."
                progressBar.visibility = View.GONE
                installButton.isEnabled = true
                installButton.text = "Retry install"
            }

            else -> {
                if (status != -1) {
                    Logger.log("INSTALL CALLBACK ($status): ${message.orEmpty()}")
                    statusView.text = message ?: "Installer returned status $status."
                    progressBar.visibility = View.GONE
                    installButton.isEnabled = true
                    installButton.text = "Retry install"
                }
            }
        }
    }

    companion object {
        fun createIntent(
            context: Context,
            appLabel: String,
            originalPackage: String,
            targetPackage: String,
            apkPaths: List<String>,
            autoInstall: Boolean = true
        ): Intent = Intent(context, InstallerActivity::class.java).apply {
            action = InstallContracts.ACTION_START_INSTALLER
            putExtra(InstallContracts.EXTRA_APP_LABEL, appLabel)
            putExtra(InstallContracts.EXTRA_ORIGINAL_PACKAGE, originalPackage)
            putExtra(InstallContracts.EXTRA_TARGET_PACKAGE, targetPackage)
            putExtra(InstallContracts.EXTRA_APK_PATHS, apkPaths.toTypedArray())
            putExtra(InstallContracts.EXTRA_AUTO_INSTALL, autoInstall)
        }
    }
}
