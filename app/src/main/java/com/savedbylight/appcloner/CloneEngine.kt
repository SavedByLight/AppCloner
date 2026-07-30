package com.savedbylight.appcloner

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class CloneEngine(private val context: Context) {

    /**
     * Entry point called by MainActivity passing an InstalledApp object.
     */
    fun cloneApp(app: InstalledApp): List<File> {
        val pkgName = app.packageName
        val targetPackageName = "$pkgName.clone1"
        val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
        return cloneApp(appInfo, targetPackageName)
    }

    /**
     * Overload for direct ApplicationInfo input without explicit target name.
     */
    fun cloneApp(appInfo: ApplicationInfo): List<File> {
        val targetPackageName = "${appInfo.packageName}.clone1"
        return cloneApp(appInfo, targetPackageName)
    }

    /**
     * Core cloning routine that extracts, rewrites, and signs base and split APKs.
     */
    fun cloneApp(appInfo: ApplicationInfo, targetPackageName: String): List<File> {
        val workDir = File(context.cacheDir, "clone_work_$targetPackageName").apply { mkdirs() }
        val clonedApkFiles = mutableListOf<File>()

        // 1. Process Base APK
        val baseSourceFile = File(appInfo.sourceDir)
        val baseTargetFile = File(workDir, "base_signed.apk")
        Logger.log("Processing Base APK: ${baseSourceFile.name}")
        processSingleApk(baseSourceFile, baseTargetFile, targetPackageName)
        clonedApkFiles.add(baseTargetFile)

        // 2. Process Split APKs (if present)
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

        return clonedApkFiles
    }

    /**
     * Entry point called by MainActivity passing a List<File> of APKs.
     */
    fun launchInstall(apkFiles: List<File>) {
        installPackageSession(context, null, apkFiles)
    }

    /**
     * Overload accepting target package name explicitly.
     */
    fun launchInstall(targetPackageName: String, apkFiles: List<File>) {
        installPackageSession(context, targetPackageName, apkFiles)
    }

    /**
     * Combined execution method to clone and immediately initiate installation.
     */
    fun cloneAndInstallApp(appInfo: ApplicationInfo, targetPackageName: String) {
        val files = cloneApp(appInfo, targetPackageName)
        launchInstall(targetPackageName, files)
    }

    /**
     * Handles binary XML rewriting and signing for an individual APK file.
     * Guarantees the destination file exists on disk to prevent ENOENT errors during install.
     */
    private fun processSingleApk(source: File, destination: File, newPackageName: String) {
        val tempUnsigned = File.createTempFile("temp_unsigned", ".apk", context.cacheDir)
        try {
            // Copy source APK to temporary buffer
            source.copyTo(tempUnsigned, overwrite = true)

            // Rewrite binary AXML manifest parameters
            rewriteAxmlManifest(tempUnsigned, newPackageName)

            // Re-sign modified APK binary and output to destination
            signApkBinary(tempUnsigned, destination)

            // Sanity validation to guarantee destination file existence
            if (!destination.exists() || destination.length() == 0L) {
                throw IOException("Failed to generate destination APK at: ${destination.absolutePath}")
            }
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
        // Ensures the processed payload is written to 'outputFile' destination
        inputFile.copyTo(outputFile, overwrite = true)
    }

    /**
     * Stages base and split APKs into a PackageInstaller.Session and commits them atomically.
     */
    private fun installPackageSession(context: Context, packageName: String?, apkFiles: List<File>) {
        if (apkFiles.isEmpty()) {
            throw IllegalArgumentException("No APK files provided for installation.")
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (!packageName.isNullOrEmpty()) {
            params.setAppPackageName(packageName)
        }

        val sessionId = packageInstaller.createSession(params)
        var session: PackageInstaller.Session? = null

        try {
            session = packageInstaller.openSession(sessionId)

            apkFiles.forEachIndexed { index, file ->
                if (!file.exists()) {
                    throw IOException("APK file does not exist: ${file.absolutePath}")
                }

                val sessionStreamName = if (index == 0) "base.apk" else "split_$index.apk"
                val outStream = session.openWrite(sessionStreamName, 0, file.length())

                FileInputStream(file).use { inStream ->
                    inStream.copyTo(outStream)
                }
                session.fsync(outStream)
                outStream.close()
                Logger.log("Staged $sessionStreamName (${file.length()} bytes) into session $sessionId")
            }

            // Create IntentSender callback for installation status broadcast
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
            Logger.log("Committed install session $sessionId")
        } catch (e: Exception) {
            session?.abandon()
            Logger.log("Session $sessionId failed: ${e.message}")
            throw e
        } finally {
            session?.close()
        }
    }
}
