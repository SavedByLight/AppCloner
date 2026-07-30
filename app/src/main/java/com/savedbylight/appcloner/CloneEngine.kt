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
     * Alias for compatibility with MainActivity calls.
     */
    fun cloneApp(appInfo: ApplicationInfo, targetPackageName: String): List<File> {
        val workDir = File(context.cacheDir, "clone_work_$targetPackageName").apply { mkdirs() }
        val clonedApkFiles = mutableListOf<File>()

        // 1. Base APK
        val baseSourceFile = File(appInfo.sourceDir)
        val baseTargetFile = File(workDir, "base_signed.apk")
        processSingleApk(baseSourceFile, baseTargetFile, targetPackageName)
        clonedApkFiles.add(baseTargetFile)

        // 2. Split APKs
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
     * Alias for compatibility with MainActivity calls to trigger install session.
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
        // AXML manifest rewriting logic
    }

    private fun signApkBinary(inputFile: File, outputFile: File) {
        // Dynamic signing logic
    }

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
