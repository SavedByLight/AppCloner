package com.savedbylight.appcloner

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.os.Build
import com.android.apksig.ApkSigner
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlVisitor
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

        val oldPackageName = appInfo.packageName

        // 1. Process Base APK
        val baseSourceFile = File(appInfo.sourceDir)
        val baseTargetFile = File(workDir, "base_signed.apk")
        Logger.log("Processing Base APK: ${baseSourceFile.name}")
        processSingleApk(baseSourceFile, baseTargetFile, oldPackageName, targetPackageName)
        clonedApkFiles.add(baseTargetFile)

        // 2. Process Split APKs (if present)
        val splitDirs = appInfo.splitSourceDirs
        if (!splitDirs.isNullOrEmpty()) {
            Logger.log("Found ${splitDirs.size} split APKs. Processing splits...")
            splitDirs.forEachIndexed { index, splitPath ->
                val splitSourceFile = File(splitPath)
                val splitTargetFile = File(workDir, "split_${index}_signed.apk")
                Logger.log("Processing Split APK [$index]: ${splitSourceFile.name}")
                processSingleApk(splitSourceFile, splitTargetFile, oldPackageName, targetPackageName)
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
    private fun processSingleApk(source: File, destination: File, oldPackageName: String, newPackageName: String) {
        val tempUnsigned = File.createTempFile("temp_unsigned", ".apk", context.cacheDir)
        try {
            // Copy source APK to temporary buffer
            source.copyTo(tempUnsigned, overwrite = true)

            // Rewrite binary AXML manifest parameters, in place inside the zip
            rewriteAxmlManifest(tempUnsigned, oldPackageName, newPackageName)

            // Re-sign modified APK binary and output to destination. Every
            // APK in a clone (base + splits) must be signed with the SAME
            // key or the install session will be rejected as inconsistent.
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

    /** Replaces the AndroidManifest.xml entry inside apkFile's zip with a
     *  package-rewritten version, leaving every other entry (resources.arsc,
     *  classes.dex, native libs, etc.) byte-for-byte untouched.
     *
     *  Note: java.nio.file's "jar:" FileSystemProvider (used in an earlier
     *  version of this method) is a desktop-JDK-only feature — it compiles
     *  fine against the Android SDK stubs but throws
     *  ProviderNotFoundException at runtime, since Android doesn't ship
     *  ZipFileSystemProvider. java.util.zip, used here instead, is fully
     *  supported on-device. */
    private fun rewriteAxmlManifest(apkFile: File, oldPackageName: String, newPackageName: String) {
        val rewrittenApk = File.createTempFile("manifest_rewrite", ".apk", context.cacheDir)
        try {
            ZipFile(apkFile).use { zipIn ->
                ZipOutputStream(FileOutputStream(rewrittenApk)).use { zipOut ->
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                        val outBytes = if (entry.name == "AndroidManifest.xml") {
                            rewritePackageInAxml(bytes, oldPackageName, newPackageName)
                        } else {
                            bytes
                        }

                        val outEntry = ZipEntry(entry.name)
                        if (entry.method == ZipEntry.STORED) {
                            // STORED entries require exact size/CRC to be
                            // declared up front; DEFLATED entries let
                            // ZipOutputStream compute these on the fly.
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

    /** Element tags whose "android:name" attribute is a fully-qualified
     *  component class name (or, for <application>, the Application subclass)
     *  rather than an arbitrary key — the only tags where rewriting "name"
     *  is actually correct. */
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
        })

        Logger.log("AXML: serializing rewritten manifest")
        return writer.toByteArray()
    }

    /** Recursively walks manifest nodes, tracking each element's tag name so
     *  attribute rewrites can be scoped to where a package-name substitution
     *  is actually correct:
     *   - <manifest package="...">                        — exact match
     *   - <provider android:authorities="...">             — substring (can be a comma list)
     *   - <application/activity/activity-alias/service/receiver/provider
     *     android:name="...">                              — prefix match (FQCN)
     *   - android:process="..." on any component            — prefix match
     *  Everything else (meta-data keys/values, intent-filter data, etc.) is
     *  left untouched, even if it happens to contain the package substring. */
    private class RewritingNodeVisitor(
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
                    tag == "provider" && name == "authorities" && value.contains(oldPkg) ->
                        value.replace(oldPkg, newPkg)
                    tag in COMPONENT_TAGS && name == "name" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)
                    tag in COMPONENT_TAGS && name == "process" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)
                    else -> value
                }
            }

            // AxmlWriter unconditionally wraps `name`, and `value` when
            // type == TYPE_STRING, into a StringItem with no null check.
            // Some attributes in manifests built by newer aapt2 resolve to
            // null here even though the reader still reports TYPE_STRING;
            // left as null they get silently added to the writer's string
            // pool and blow up much later in StringItems.prepare() with an
            // unhelpful NPE. Substitute empty strings instead so the entry
            // is at least well-formed; this only affects attributes whose
            // value the reader couldn't resolve in the first place.
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

    companion object {
        /** Element tags whose "android:name" attribute is a fully-qualified
         *  component class name (or, for <application>, the Application
         *  subclass) rather than an arbitrary key — the only tags where
         *  rewriting "name" is actually correct. */
        private val COMPONENT_TAGS = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
    }
}
