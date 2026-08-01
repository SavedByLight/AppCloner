package com.savedbylight.appcloner

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlVisitor
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import pxb.android.axml.ValueWrapper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class CloneEngine(private val context: Context) {

    fun cloneApp(app: InstalledApp): List<File> {
        val pkgName = app.packageName
        val targetPackageName = "$pkgName.clone1"
        val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
        return cloneApp(appInfo, targetPackageName)
    }

    fun cloneApp(appInfo: ApplicationInfo): List<File> {
        val targetPackageName = "${appInfo.packageName}.clone1"
        return cloneApp(appInfo, targetPackageName)
    }

    fun cloneApp(appInfo: ApplicationInfo, targetPackageName: String): List<File> {
        val workDir = File(context.cacheDir, "clone_work_$targetPackageName").apply { mkdirs() }
        val clonedApkFiles = mutableListOf<File>()

        val oldPackageName = appInfo.packageName

        val baseSourceFile = File(appInfo.sourceDir)
        val baseTargetFile = File(workDir, "base_signed.apk")
        Logger.log("Processing Base APK: ${baseSourceFile.name}")
        processSingleApk(baseSourceFile, baseTargetFile, oldPackageName, targetPackageName, badgeIcon = true)
        clonedApkFiles.add(baseTargetFile)

        val splitDirs = appInfo.splitSourceDirs
        if (!splitDirs.isNullOrEmpty()) {
            Logger.log("Found ${splitDirs.size} split APKs. Processing splits...")
            splitDirs.forEachIndexed { index, splitPath ->
                val splitSourceFile = File(splitPath)
                val splitTargetFile = File(workDir, "split_${index}_signed.apk")
                Logger.log("Processing Split APK [$index]: ${splitSourceFile.name}")
                processSingleApk(splitSourceFile, splitTargetFile, oldPackageName, targetPackageName, badgeIcon = false)
                clonedApkFiles.add(splitTargetFile)
            }
        }

        return clonedApkFiles
    }

    fun launchInstall(apkFiles: List<File>) {
        launchInstall(null, apkFiles)
    }

    fun launchInstall(targetPackageName: String?, apkFiles: List<File>) {
        if (!RootInstaller.isRootAvailable()) {
            Logger.log("Root not available — cannot install without the system installer UI")
            throw IllegalStateException(
                "Root access is required to install clones. This app installs directly " +
                    "as its own installer and does not fall back to the system Package " +
                    "Installer; grant root (su) access and try again."
            )
        }
        Logger.log("Installing ${apkFiles.size} APK(s) via root shell")
        RootInstaller.installApksAsRoot(targetPackageName, apkFiles)
    }

    fun cloneAndInstallApp(appInfo: ApplicationInfo, targetPackageName: String) {
        val files = cloneApp(appInfo, targetPackageName)
        launchInstall(targetPackageName, files)
    }

    fun installViaThirdPartyInstaller(
        context: Context,
        apkFile: File,
        installerPackageName: String?
    ) {
        if (!apkFile.exists()) {
            throw IOException("APK file does not exist: ${apkFile.absolutePath}")
        }

        val apkUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!installerPackageName.isNullOrEmpty()) {
                setPackage(installerPackageName)
            }
        }

        if (!installerPackageName.isNullOrEmpty() &&
            intent.resolveActivity(context.packageManager) == null
        ) {
            throw IllegalStateException(
                "Configured installer package '$installerPackageName' has no activity " +
                    "that handles ACTION_VIEW for an APK — is it installed?"
            )
        }

        Logger.log(
            "Dispatching ACTION_VIEW install intent for ${apkFile.name} " +
                if (installerPackageName.isNullOrEmpty()) "(chooser)" else "(target: $installerPackageName)"
        )
        context.startActivity(intent)
    }

    private fun processSingleApk(
        source: File,
        destination: File,
        oldPackageName: String,
        newPackageName: String,
        badgeIcon: Boolean
    ) {
        val tempUnsigned = File.createTempFile("temp_unsigned", ".apk", context.cacheDir)
        try {
            source.copyTo(tempUnsigned, overwrite = true)
            rewriteApkContents(tempUnsigned, oldPackageName, newPackageName, badgeIcon)
            signApkBinary(tempUnsigned, destination)
            if (!destination.exists() || destination.length() == 0L) {
                throw IOException("Failed to generate destination APK at: ${destination.absolutePath}")
            }
        } finally {
            if (tempUnsigned.exists()) {
                tempUnsigned.delete()
            }
        }
    }

    private fun rewriteApkContents(
        apkFile: File,
        oldPackageName: String,
        newPackageName: String,
        badgeIcon: Boolean
    ) {
        val rewrittenApk = File.createTempFile("apk_rewrite", ".apk", context.cacheDir)
        try {
            ZipFile(apkFile).use { zipIn ->
                ZipOutputStream(FileOutputStream(rewrittenApk)).use { zipOut ->
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                        val outBytes = when {
                            entry.name == "AndroidManifest.xml" ->
                                rewritePackageInAxml(bytes, oldPackageName, newPackageName)
                            badgeIcon && isLauncherIconEntry(entry.name) ->
                                badgeLauncherIcon(entry.name, bytes)
                            else -> bytes
                        }

                        val outEntry = ZipEntry(entry.name)
                        if (entry.method == ZipEntry.STORED) {
                            outEntry.method = ZipEntry.STORED
                            outEntry.size = outBytes.size.toLong()
                            outEntry.compressedSize = outBytes.size.toLong()
                            outEntry.crc = CRC32().apply { update(outBytes) }.value
                        } else {
                            outEntry.method = ZipEntry.DEFLATED
                        }

                        zipOut.putNextEntry(outEntry)
                        zipOut.write(outBytes)
                        zipOut.closeEntry()
                    }
                }
            }
            rewrittenApk.copyTo(apkFile, overwrite = true)
        } finally {
            rewrittenApk.delete()
        }
    }

    private fun isLauncherIconEntry(entryName: String): Boolean {
        val lower = entryName.lowercase()
        if (!(lower.startsWith("res/mipmap") || lower.startsWith("res/drawable"))) return false
        if (!lower.endsWith(".png") && !lower.endsWith(".webp")) return false
        return lower.contains("ic_launcher")
    }

    private fun badgeLauncherIcon(entryName: String, bytes: ByteArray): ByteArray {
        return try {
            val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val badged = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(badged)
            val w = badged.width.toFloat()
            val h = badged.height.toFloat()
            val radius = w * 0.22f
            val cx = w - radius * 0.85f
            val cy = h - radius * 0.85f

            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF6D00")
            }
            canvas.drawCircle(cx, cy, radius, badgePaint)

            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = radius * 0.12f
            }
            canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = radius * 1.15f
                isFakeBoldText = true
            }
            val fm = textPaint.fontMetrics
            val textY = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText("C", cx, textY, textPaint)

            ByteArrayOutputStream().use { stream ->
                badged.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
        } catch (e: Exception) {
            Logger.log("Icon badge failed for $entryName: ${e.javaClass.simpleName}: ${e.message} — using original icon unmodified")
            bytes
        }
    }

    private fun signApkBinary(inputFile: File, outputFile: File) {
        val (privateKey, certChain) = SigningIdentity.getOrCreate(context)
        val signerConfig = ApkSigner.SignerConfig.Builder("CloneKey", privateKey, certChain).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputFile)
            .setOutputApk(outputFile)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }

    private fun rewritePackageInAxml(manifestBytes: ByteArray, oldPkg: String, newPkg: String): ByteArray {
        Logger.log("AXML: parsing manifest (${manifestBytes.size} bytes)")
        val reader = AxmlReader(manifestBytes)
        val writer = AxmlWriter()

        Logger.log("AXML: walking nodes to rewrite package/authorities")
        reader.accept(object : AxmlVisitor(writer) {
            override fun child(ns: String?, name: String?): NodeVisitor {
                val safeName = name ?: run {
                    Logger.log("AXML: root child element with null tag name — substituting empty string")
                    ""
                }
                val superVisitor = super.child(ns, safeName)
                return RewritingNodeVisitor(superVisitor, safeName, oldPkg, newPkg)
            }

            override fun ns(prefix: String?, uri: String?, ln: Int) {
                val safeUri = uri ?: run {
                    Logger.log("AXML: namespace declaration with null uri (prefix=$prefix) — substituting empty string")
                    ""
                }
                super.ns(prefix, safeUri, ln)
            }
        })

        Logger.log("AXML: serializing rewritten manifest")
        return writer.toByteArray()
    }

    private inner class RewritingNodeVisitor(
        parent: NodeVisitor,
        private val tag: String?,
        private val oldPkg: String,
        private val newPkg: String
    ) : NodeVisitor(parent) {

        override fun attr(ns: String?, name: String?, resourceId: Int, type: Int, value: Any?) {
            var newValue = value
            if (value is String) {
                newValue = when {
                    tag == "manifest" && name == "package" && value == oldPkg ->
                        newPkg

                    tag == "provider" && name == "authorities" ->
                        value.split(";").joinToString(";") { authority ->
                            if (authority.contains(oldPkg)) {
                                authority.replace(oldPkg, newPkg)
                            } else {
                                // Authority has no relation to the app's own package name
                                // (e.g. a hardcoded SDK constant) — suffix it with the new
                                // package to guarantee uniqueness and avoid
                                // INSTALL_FAILED_CONFLICTING_PROVIDER against the original app.
                                "$authority.$newPkg"
                            }
                        }

                    tag == "activity-alias" && name == "targetActivity" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    name == "parentActivityName" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    // Use outer class's companion constants
                    tag in CloneEngine.PERMISSION_DECLARATION_TAGS && name == "name" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    tag == "uses-permission" && name == "name" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    name in CloneEngine.PERMISSION_REFERENCE_ATTRS && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    tag in CloneEngine.COMPONENT_TAGS_FOR_PROCESS && name == "process" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    else -> value
                }
            }

            // Patch ValueWrapper.raw if null (reflection hack)
            if (newValue is ValueWrapper && newValue.raw == null) {
                Logger.log("AXML: ValueWrapper with null raw text for attr '$name' on <$tag> (resourceId=0x${resourceId.toString(16)}, type=$type) — substituting empty string")
                try {
                    val rawField = ValueWrapper::class.java.getDeclaredField("raw")
                    rawField.isAccessible = true
                    rawField.set(newValue, "")
                } catch (e: Exception) {
                    Logger.log("AXML: reflection patch of ValueWrapper.raw failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            val safeName = name ?: run {
                Logger.log("AXML: attribute with null name at resourceId=0x${resourceId.toString(16)} (type=$type) — substituting empty string")
                ""
            }
            var safeValue = newValue
            if (type == AxmlVisitor.TYPE_STRING && safeValue == null) {
                Logger.log("AXML: null string value for attr '$safeName' on <$tag> (resourceId=0x${resourceId.toString(16)}) — substituting empty string")
                safeValue = ""
            }

            super.attr(ns, safeName, resourceId, type, safeValue)
        }

        override fun child(ns: String?, name: String?): NodeVisitor {
            val safeName = name ?: run {
                Logger.log("AXML: child element with null tag name under <$tag> — substituting empty string")
                ""
            }
            return RewritingNodeVisitor(super.child(ns, safeName), safeName, oldPkg, newPkg)
        }

        override fun text(lineNumber: Int, value: String?) {
            val safeValue = value ?: run {
                Logger.log("AXML: text node with null value under <$tag> at line $lineNumber — substituting empty string")
                ""
            }
            super.text(lineNumber, safeValue)
        }
    }

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

    companion object {
        private val COMPONENT_TAGS_FOR_PROCESS = setOf(
            "application", "activity", "activity-alias",
            "service", "receiver", "provider"
        )

        private val PERMISSION_DECLARATION_TAGS = setOf("permission", "permission-group", "permission-tree")
        private val PERMISSION_REFERENCE_ATTRS = setOf("permission", "readPermission", "writePermission")

        private const val PREFS_NAME = "app_cloner_prefs"
        private const val PREF_KEY_INSTALLER_PACKAGE = "preferred_installer_package"

        fun setPreferredInstallerPackage(context: Context, packageName: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    if (packageName.isNullOrBlank()) remove(PREF_KEY_INSTALLER_PACKAGE)
                    else putString(PREF_KEY_INSTALLER_PACKAGE, packageName)
                }
                .apply()
        }

        fun getPreferredInstallerPackage(context: Context): String? {
            val pkg = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_INSTALLER_PACKAGE, null)
            return if (pkg.isNullOrBlank()) null else pkg
        }
    }
}
