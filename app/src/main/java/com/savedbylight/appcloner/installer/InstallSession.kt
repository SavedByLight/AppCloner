package com.savedbylight.appcloner.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.savedbylight.appcloner.Logger
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class InstallSession(private val context: Context) {

    fun commitInstall(
        appLabel: String?,
        originalPackage: String?,
        targetPackage: String?,
        apkPaths: List<String>
    ) {
        if (apkPaths.isEmpty()) {
            throw IllegalArgumentException("No APK files provided for installation.")
        }

        val apkFiles = apkPaths.map { File(it) }
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (!targetPackage.isNullOrBlank()) {
            params.setAppPackageName(targetPackage)
        }

        val sessionId = packageInstaller.createSession(params)
        var session: PackageInstaller.Session? = null

        try {
            session = packageInstaller.openSession(sessionId)

            apkFiles.forEachIndexed { index, file ->
                if (!file.exists()) {
                    throw IOException("APK file does not exist: ${file.absolutePath}")
                }

                val stagedName = if (index == 0) "base.apk" else "split_$index.apk"
                val currentSession = session ?: throw IllegalStateException("Install session was not opened")
                FileInputStream(file).use { input ->
                    currentSession.openWrite(stagedName, 0, file.length()).use { output ->
                        input.copyTo(output)
                        currentSession.fsync(output)
                    }
                }

                Logger.log("Staged $stagedName (${file.length()} bytes) for install session $sessionId")
            }

            val callbackIntent = Intent(context, InstallerActivity::class.java).apply {
                action = InstallContracts.ACTION_INSTALL_STATUS
                putExtra(InstallContracts.EXTRA_APP_LABEL, appLabel)
                putExtra(InstallContracts.EXTRA_ORIGINAL_PACKAGE, originalPackage)
                putExtra(InstallContracts.EXTRA_TARGET_PACKAGE, targetPackage)
                putExtra(InstallContracts.EXTRA_APK_PATHS, apkPaths.toTypedArray())
                putExtra(InstallContracts.EXTRA_SESSION_ID, sessionId)
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId,
                callbackIntent,
                pendingIntentFlags
            )

            Logger.log("Committing install session $sessionId")
            val currentSession = session ?: throw IllegalStateException("Install session was not opened")
            currentSession.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            session?.abandon()
            Logger.log("Session $sessionId failed: ${e.message}")
            throw e
        } finally {
            session?.close()
        }
    }
}
