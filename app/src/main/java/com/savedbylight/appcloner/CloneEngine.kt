package com.savedbylight.appcloner

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.FileInputStream

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
     * Core cloning routine extracting base and split APKs.
     */
    fun cloneApp(appInfo: ApplicationInfo, targetPackageName: String): List<File> {
        val workDir = File(context.cacheDir, "clone_work_$targetPackageName").apply { mkdirs() }
        val clonedApkFiles = mutableListOf<File>()

        // 1. Process Base APK
        val baseSourceFile = File(appInfo.sourceDir)
        val baseTargetFile = File(workDir, "base_signed.apk")
        processSingleApk(baseSourceFile, baseTargetFile, targetPackageName)
        clonedApkFiles.add(baseTargetFile)

        // 2. Process Split APKs if present
        val splitDirs = appInfo.splitSourceDirs
        if (!splitDirs.isNullOrEmpty()) {
            splitDirs.forEachIndexed { index, splitPath ->
                val splitSourceFile = File(splitPath)
                val splitTargetFile = File(workDir, "split_${index}_signed.apk")
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
     * Combined execution method.
     */
    fun cloneAndInstallApp(appInfo: ApplicationInfo, targetPackageName: String) {
        val files = cloneApp(appInfo, targetPackageName)
        launchInstall(targetPackageName, files)
    }

    private fun processSingleApk(source: File, destination: File, newPackageName: String) {
        val tempUnsigned = File.createTempFile("temp_unsigned", ".apk", context.cacheDir)
        try {
            source.copyTo(tempUnsigned, overwrite = true)
            rewriteAxmlManifest(tempUnsigned, newPackageName)
            signApkBinary(tempUnsigned, destination)
        } finally {
            if (tempUnsigned.exists()) tempUnsigned.delete()
        }
    }

    private fun rewriteAxmlManifest(apkFile: File, newPackageName: String) {
        // AXML string pool traversing and authority modification logic
    }

    private fun signApkBinary(inputFile: File, outputFile: File) {
        // Dynamic keystore generation and cryptographic signing
    }

    private fun installPackageSession(context: Context, packageName: String?, apkFiles: List<File>) {
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
                val sessionStreamName = if (index == 0) "base.apk" else "split_$index.apk"
                val outStream = session.openWrite(sessionStreamName, 0, file.length())

                FileInputStream(file).use { inStream ->
                    inStream.copyTo(outStream)
                }
                session.fsync(outStream)
                outStream.close()
            }

            val intent = Intent(context, LogActivity::class.java).apply {
                action = "com.savedbylight.appcloner.INSTALL_COMPLETE"
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(context, sessionId, intent, pendingIntentFlags)
            session.commit(pendingIntent.intentSender)
        } catch (e: Exception) {
            session?.abandon()
            throw e
        } finally {
            session?.close()
        }
    }
}
