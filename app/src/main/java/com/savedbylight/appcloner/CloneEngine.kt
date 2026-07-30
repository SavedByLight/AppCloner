package com.savedbylight.appcloner

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class CloneEngine(private val context: Context) {

    /**
     * Entry point to clone and install an application.
     * Handles both single APKs and dynamic split APK bundles.
     */
    fun cloneAndInstallApp(appInfo: ApplicationInfo, targetPackageName: String) {
        val workDir = File(context.cacheDir, "clone_work_$targetPackageName").apply { mkdirs() }
        val clonedApkFiles = mutableListOf<File>()

        // 1. Process Base APK
        val baseSourceFile = File(appInfo.sourceDir)
        val baseTargetFile = File(workDir, "base_signed.apk")
        Logger.log("Processing Base APK: ${baseSourceFile.name}")
        processSingleApk(baseSourceFile, baseTargetFile, targetPackageName)
        clonedApkFiles.add(baseTargetFile)

        // 2. Process Split APKs if available
        val splitDirs = appInfo.splitSourceDirs
        if (!splitDirs.isNullOrEmpty()) {
            Logger.log("Found ${splitDirs.size} split APKs. Processing splits...")
            splitDirs.forEachIndexed { index, splitPath ->
                val splitSourceFile = File(splitPath)
                val splitTargetFile = File(workDir, "split_${index}_signed.apk")
                Logger.log("Processing Split APK [$index]: ${splitSourceFile.name}")
                processSingleApk(splitSourceFile, splitTargetFile, targetPackageName)
                clonedApkFiles.add(splitTargetFile)
            }
        }

        // 3. Commit Multi-APK PackageInstaller Session
        Logger.log("Initiating session installation for ${clonedApkFiles.size} APK file(s)...")
        installPackageSession(context, targetPackageName, clonedApkFiles)
    }

    /**
     * Rewrites binary manifest parameters and signs an individual APK component.
     */
    private fun processSingleApk(source: File, destination: File, newPackageName: String) {
        val tempUnsigned = File.createTempFile("temp_unsigned", ".apk", context.cacheDir)
        try {
            source.copyTo(tempUnsigned, overwrite = true)
            rewriteAxmlManifest(tempUnsigned, newPackageName)
            signApkBinary(tempUnsigned, destination)
        } finally {
            if (tempUnsigned.exists()) {
                tempUnsigned.delete()
            }
        }
    }

    private fun rewriteAxmlManifest(apkFile: File, newPackageName: String) {
        // AXML string pool traversing and authority modification logic
    }

    private fun signApkBinary(inputFile: File, outputFile: File) {
        // Keystore generation and dynamic signing logic
    }

    /**
     * Stages all base and split APKs into a PackageInstaller.Session and commits them atomically.
     */
    private fun installPackageSession(context: Context, packageName: String, apkFiles: List<File>) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
        }

        val sessionId = packageInstaller.createSession(params)
        var session: PackageInstaller.Session? = null

        try {
            session = packageInstaller.openSession(sessionId)

            apkFiles.forEachIndexed { index, file ->
                val sessionStreamName = if (index == 0) "base.apk" else "split_$index.apk"
                val outStream = session.openWrite(sessionStreamName, 0, file.length())

                FileInputStream(file).use { inStream ->
                    inStream.copyTo(outStream)
                }
                session.fsync(outStream)
                outStream.close()
                Logger.log("Staged $sessionStreamName (${file.length()} bytes) into session $sessionId")
            }

            // Status callback intent
            val intent = Intent(context, LogActivity::class.java).apply {
                action = "com.savedbylight.appcloner.INSTALL_COMPLETE"
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId,
                intent,
                pendingIntentFlags
            )

            session.commit(pendingIntent.intentSender)
            Logger.log("Committed install session $sessionId for $packageName")
        } catch (e: Exception) {
            session?.abandon()
            Logger.log("Session $sessionId failed: ${e.message}")
            throw e
        } finally {
            session?.close()
        }
    }
}
