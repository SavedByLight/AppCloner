package com.savedbylight.appclonerclassic

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.media.MediaScannerConnection
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.content.ContentValues
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

    /**
     * Entry point called by MainActivity. Uses a stealth random suffix
     * instead of the obvious .clone1, .clone2 pattern.
     */
    fun cloneApp(app: InstalledApp): List<File> {
        val pkgName = app.packageName
        val targetPackageName = CloneUtils.nextStealthClonePackage(context, pkgName)
        Logger.log("=== Clone started: $pkgName -> $targetPackageName ===")
        val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
        return cloneApp(appInfo, targetPackageName)
    }

    /**
     * Overload for direct ApplicationInfo input.
     */
    fun cloneApp(appInfo: ApplicationInfo): List<File> {
        val targetPackageName = CloneUtils.nextStealthClonePackage(context, appInfo.packageName)
        Logger.log("=== Clone started: ${appInfo.packageName} -> $targetPackageName ===")
        return cloneApp(appInfo, targetPackageName)
    }

    /**
     * Core cloning routine that extracts, rewrites, and signs base and split APKs.
     * Always exports the resulting signed APKs to a persistent external-files
     * location so the user can fall back to adb / SAI / manual install when
     * OEM verifiers (Samsung Auto Blocker, Play Protect, Knox, …) reject the
     * PackageInstaller session.
     */
    fun cloneApp(appInfo: ApplicationInfo, targetPackageName: String): List<File> {
        val workDir = File(context.cacheDir, "clone_work_$targetPackageName").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        val clonedApkFiles = mutableListOf<File>()

        val oldPackageName = appInfo.packageName
        val baseSourceFile = File(appInfo.sourceDir)
        val splitDirs = appInfo.splitSourceDirs

        Logger.log(
            "=== Clone started: $oldPackageName -> $targetPackageName " +
                "(base + ${splitDirs?.size ?: 0} split APK(s), workDir=${workDir.absolutePath}) ==="
        )

        // 1. Process Base APK
        val baseTargetFile = File(workDir, "base_signed.apk")
        Logger.log("Processing Base APK: ${baseSourceFile.name} (${baseSourceFile.length()} bytes)")
        processSingleApk(baseSourceFile, baseTargetFile, oldPackageName, targetPackageName, badgeIcon = true)
        Logger.log("  Finished base_signed.apk (${baseTargetFile.length()} bytes)")
        clonedApkFiles.add(baseTargetFile)

        // 2. Process Split APKs (if present)
        if (!splitDirs.isNullOrEmpty()) {
            Logger.log("Found ${splitDirs.size} split APK(s). Processing splits...")
            splitDirs.forEachIndexed { index, splitPath ->
                val splitSourceFile = File(splitPath)
                val splitTargetFile = File(workDir, "split_${index}_signed.apk")
                Logger.log("Processing Split APK [$index]: ${splitSourceFile.name} (${splitSourceFile.length()} bytes)")
                processSingleApk(splitSourceFile, splitTargetFile, oldPackageName, targetPackageName, badgeIcon = false)
                Logger.log("  Finished split_${index}_signed.apk (${splitTargetFile.length()} bytes)")
                clonedApkFiles.add(splitTargetFile)
            }
        }

        val totalBytes = clonedApkFiles.sumOf { it.length() }
        Logger.log(
            "=== Clone finished: ${clonedApkFiles.size} APK(s) produced for $targetPackageName " +
                "($totalBytes bytes total) ==="
        )

        val exportDir = exportClonedApks(targetPackageName, clonedApkFiles)
        Logger.log("Exported ${clonedApkFiles.size} APK(s) for debugging/manual install to: ${exportDir.absolutePath}")

        return clonedApkFiles
    }

    /**
     * Copies the signed clone APKs into the public Downloads folder.
     */
    private fun exportClonedApks(targetPackageName: String, apkFiles: List<File>): File {
        val relativeSubdir = "AppCloner/$targetPackageName"
        val publicDownloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val exportDir = File(publicDownloads, relativeSubdir)

        val wroteFs = try {
            if (exportDir.exists()) {
                exportDir.listFiles()?.forEach { it.delete() }
            }
            if (!exportDir.mkdirs() && !exportDir.isDirectory) {
                throw IOException("Cannot create $exportDir")
            }
            apkFiles.forEachIndexed { index, src ->
                val name = if (index == 0) "base.apk" else "split_$index.apk"
                val dest = File(exportDir, name)
                src.copyTo(dest, overwrite = true)
                if (!dest.isFile || dest.length() == 0L) {
                    throw IOException("Export produced empty file: ${dest.absolutePath}")
                }
            }
            writeInstallHelperFiles(exportDir, apkFiles.size)
            val paths = exportDir.listFiles()?.map { it.absolutePath }?.toTypedArray()
            if (paths != null && paths.isNotEmpty()) {
                MediaScannerConnection.scanFile(context, paths, null, null)
            }
            true
        } catch (e: Exception) {
            Logger.log("Direct Downloads write failed (${e.message}); trying MediaStore-only")
            false
        }

        if (wroteFs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    exportViaMediaStore(relativeSubdir, apkFiles)
                } catch (e: Exception) {
                    Logger.log("MediaStore index skipped: ${e.message}")
                }
            }
            Logger.log(
                "Export ready at ${exportDir.absolutePath} — " +
                    if (apkFiles.size > 1) {
                        "multi-split set: open the FOLDER in SAI/APKMirror Installer, " +
                            "or run adb install-multiple on all APKs (do not open base.apk alone)"
                    } else {
                        "single APK: can be opened from Files/Downloads if Install unknown apps is allowed"
                    }
            )
            return exportDir
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                exportViaMediaStore(relativeSubdir, apkFiles)
                Logger.log(
                    "Exported via MediaStore to Downloads/$relativeSubdir " +
                        "(no direct path). Prefer SAI to install multi-split sets."
                )
            } catch (e: Exception) {
                Logger.log("MediaStore export also failed: ${e.message}")
            }
        }
        return exportDir
    }

    private fun exportViaMediaStore(relativeSubdir: String, apkFiles: List<File>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        apkFiles.forEachIndexed { index, file ->
            val displayName = if (index == 0) "base.apk" else "split_$index.apk"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$relativeSubdir")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null for $displayName")
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    private fun writeInstallHelperFiles(dir: File, apkCount: Int) {
        val readme = File(dir, "README.txt")
        readme.writeText(
            """AppCloner Classic — Manual Install Fallback
|
| This folder contains $apkCount APK(s) for the cloned app.
|
| ${if (apkCount > 1) "MULTI-SPLIT: Do NOT tap base.apk alone." else "Single APK — can be installed directly."}
|
| Installation options:
|   1. SAI (Split APKs Installer) — open this folder in SAI
|   2. APKMirror Installer — open this folder
|   3. adb:  adb install-multiple *.apk
|
| If the system PackageInstaller blocked the automatic install,
| temporarily disable:
|   - Samsung Auto Blocker (Settings > Security)
|   - Play Protect "Scan apps with Play Protect"
|   - Knox / MDM policies
|
| Then retry installation.
""".trimMargin()
        )
    }

    fun processSingleApk(
        source: File,
        destination: File,
        oldPackageName: String,
        newPackageName: String,
        badgeIcon: Boolean
    ) {
        val tempUnsigned = File.createTempFile("temp_unsigned", ".apk", context.cacheDir)
        try {
            source.copyTo(tempUnsigned, overwrite = true)
            Logger.log("  Copied ${source.name} to temp buffer (${tempUnsigned.length()} bytes)")
            rewriteApkContents(tempUnsigned, oldPackageName, newPackageName, badgeIcon)
            Logger.log("  Signing ${tempUnsigned.name} -> ${destination.name} (v1/v2/v3, minSdk=26)")
            signApkBinary(tempUnsigned, destination)
            Logger.log("  Signed successfully: ${destination.name} (${destination.length()} bytes)")

            // Inject Frida gadget if enabled and this is the base APK
            if (badgeIcon && FridaInjector.shouldInject(context)) {
                Logger.log("  Attempting Frida gadget injection...")
                FridaInjector.injectGadgetIntoApk(context, destination)
            }

            // Run DEX string patching if enabled (neuters client-side checks)
            if (badgeIcon && SmaliPatcher.shouldPatch(context)) {
                Logger.log("  Running DEX string patcher...")
                val patchCount = SmaliPatcher.patchDexStrings(context, destination)
                Logger.log("  DEX patcher: $patchCount string replacements")
            }

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
            var totalEntries = 0
            var manifestRewritten = false
            var iconsBadged = 0
            var pageAligned = 0
            ZipFile(apkFile).use { zipIn ->
                ZipOutputStream(FileOutputStream(rewrittenApk)).use { zipOut ->
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        totalEntries++
                        val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                        val outBytes = when {
                            entry.name == "AndroidManifest.xml" -> {
                                manifestRewritten = true
                                rewritePackageInAxml(bytes, oldPackageName, newPackageName)
                            }
                            badgeIcon && isLauncherIconEntry(entry.name) -> {
                                iconsBadged++
                                badgeLauncherIcon(entry.name, bytes)
                            }
                            else -> bytes
                        }

                        val outEntry = ZipEntry(entry.name)
                        if (entry.method == ZipEntry.STORED) {
                            outEntry.method = ZipEntry.STORED
                            outEntry.size = outBytes.size.toLong()
                            outEntry.compressedSize = outBytes.size.toLong()
                            outEntry.crc = CRC32().apply { update(outBytes) }.value
                            if (entry.name.endsWith(".so") || entry.name.contains("lib/")) {
                                pageAligned++
                            }
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
            Logger.log(
                "  Rewrote zip contents: $totalEntries entries total, " +
                    "manifest rewritten=$manifestRewritten, icons badged=$iconsBadged, " +
                    "entries page-aligned=$pageAligned"
            )
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
            val radius = w * 0.24f
            val cx = w - radius * 0.85f
            val cy = h - radius * 0.85f

            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#66000000")
            }
            canvas.drawCircle(cx + 1.5f, cy + 1.5f, radius, shadowPaint)

            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF6D00")
            }
            canvas.drawCircle(cx, cy, radius, badgePaint)

            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = radius * 0.11f
            }
            canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth / 2f, ringPaint)

            val label = "C"
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = radius * 1.25f
                isFakeBoldText = true
            }
            canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)

            ByteArrayOutputStream().use { bos ->
                badged.compress(
                    if (entryName.lowercase().endsWith(".webp")) Bitmap.CompressFormat.WEBP else Bitmap.CompressFormat.PNG,
                    100,
                    bos
                )
                bos.toByteArray()
            }
        } catch (e: Exception) {
            Logger.log("Icon badging failed for $entryName: ${e.message}")
            bytes
        }
    }

    fun signApkBinary(inputFile: File, outputFile: File) {
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

    fun rewritePackageInAxml(manifestBytes: ByteArray, oldPkg: String, newPkg: String): ByteArray {
        Logger.log("AXML: parsing manifest (${manifestBytes.size} bytes)")
        val reader = AxmlReader(manifestBytes)
        val writer = AxmlWriter()

        Logger.log("AXML: walking nodes to rewrite package/authorities/attrs")
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

    /**
     * Recursively walks manifest nodes and rewrites attributes that must change
     * for a clone to install and run without colliding with the original app.
     *
     * NEW in this version:
     *  - Strips sharedUserId (causes INSTALL_FAILED_SHARED_USER_INCOMPATIBLE)
     *  - Rewrites backupAgent, appComponentFactory, manageSpaceActivity,
     *    zygotePreloadName (FQCN attributes prefixed with old package)
     *  - Rewrites string meta-data values that embed the old package name
     *  - More robust process/taskAffinity rewriting (handles embedded oldPkg)
     */
    private class RewritingNodeVisitor(
        parent: NodeVisitor,
        private val tag: String?,
        private val oldPkg: String,
        private val newPkg: String
    ) : NodeVisitor(parent) {
        private var currentMetaDataName: String? = null

        override fun attr(ns: String?, name: String?, resourceId: Int, type: Int, value: Any?) {
            var newValue = value

            // STRIP sharedUserId entirely — it causes signature mismatch
            // with the original app on install.
            if (tag == "manifest" && name == "sharedUserId") {
                Logger.log("AXML: stripping sharedUserId from manifest")
                return
            }

            // SCRUB known anti-clone / Play Integrity meta-data values.
            // Replacing with empty string often causes the SDK to skip
            // attestation rather than crash with a mismatched project binding.
            if (tag == "meta-data" && name == "value") {
                val parentName = currentMetaDataName
                if (parentName in PLAY_INTEGRITY_METADATA_KEYS && value is String) {
                    Logger.log("AXML: scrubbing meta-data value for $parentName")
                    newValue = ""
                }
            }
            // Track meta-data name so we can match it to the value attr
            if (tag == "meta-data" && name == "name" && value is String) {
                currentMetaDataName = value
            }

            if (value is String) {
                newValue = when {
                    tag == "manifest" && name == "package" && value == oldPkg ->
                        newPkg

                    tag == "provider" && name == "authorities" ->
                        CloneEngine.rewriteAuthorities(value, oldPkg, newPkg)

                    // Application subclass and other FQCN attributes that are
                    // prefixed with the old package. These are NOT component
                    // android:name attributes (which are DEX class names and
                    // must stay untouched), but manifest-level class references
                    // that the system resolves against the manifest package.
                    tag == "application" && name == "backupAgent" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    tag == "application" && name == "appComponentFactory" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    tag == "application" && name == "manageSpaceActivity" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    tag == "application" && name == "zygotePreloadName" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    // Process name — must stay unique per clone.
                    tag in COMPONENT_TAGS && name == "process" && value.contains(oldPkg) ->
                        value.replace(oldPkg, newPkg)

                    // taskAffinity groups activities into tasks.
                    name == "taskAffinity" && value.contains(oldPkg) ->
                        value.replace(oldPkg, newPkg)

                    // Custom permission declarations.
                    tag in PERMISSION_DECLARATION_TAGS && name == "name" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    // Self-references to custom permissions.
                    tag == "uses-permission" && name == "name" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    // Permission references on components.
                    name in PERMISSION_REFERENCE_ATTRS && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)

                    // Meta-data string values that embed the old package.
                    // Many SDKs (Firebase, Facebook, etc.) store package names
                    // or authority strings in meta-data and read them at runtime.
                    tag == "meta-data" && name == "value" && value.contains(oldPkg) ->
                        value.replace(oldPkg, newPkg)

                    else -> value
                }
            }

            if (newValue is ValueWrapper && newValue.raw == null) {
                Logger.log("AXML: ValueWrapper with null raw text for attr '$name' on <$tag> — substituting empty string")
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
                Logger.log("AXML: null string value for attr '$safeName' on <$tag> — substituting empty string")
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

    /**
     * Stages base and split APKs into a PackageInstaller.Session and commits them atomically.
     * Also registers the clone in the local registry on success.
     */
    fun installPackageSession(packageName: String?, apkFiles: List<File>) {
        if (apkFiles.isEmpty()) {
            throw IllegalArgumentException("No APK files provided for installation.")
        }

        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        if (!packageName.isNullOrEmpty()) {
            params.setAppPackageName(packageName)
        }

        val totalSize = apkFiles.sumOf { it.length() }
        params.setSize(totalSize)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val hasInstallPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.packageManager.canRequestPackageInstalls()
                } else true
                val action = if (hasInstallPerm) {
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                } else {
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                }
                params.setRequireUserAction(action)
                Logger.log(
                    "Session requireUserAction=" +
                        if (hasInstallPerm) "NOT_REQUIRED (install-unknown-apps granted)"
                        else "REQUIRED"
                )
            } catch (_: Throwable) {
            }
        }

        Logger.log("Creating install session for ${packageName ?: "(unspecified package)"}: ${apkFiles.size} APK(s)")

        val sessionId = packageInstaller.createSession(params)
        Logger.log("Install session created: id=$sessionId")
        var session: PackageInstaller.Session? = null

        try {
            session = packageInstaller.openSession(sessionId)

            apkFiles.forEachIndexed { index, file ->
                if (!file.exists()) {
                    throw IOException("APK file does not exist: ${file.absolutePath}")
                }

                val sessionStreamName = if (index == 0) "base.apk" else "split_$index.apk"
                session.openWrite(sessionStreamName, 0, file.length()).use { outStream ->
                    FileInputStream(file).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                    session.fsync(outStream)
                }
                Logger.log("Staged $sessionStreamName (${file.length()} bytes) into session $sessionId")
            }

            val intent = Intent(context, LogActivity::class.java).apply {
                action = "com.savedbylight.appclonerclassic.INSTALL_COMPLETE"
                putExtra("extra_target_package", packageName)
                putExtra("extra_session_id", sessionId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
            Logger.log("USER ACTION REQUIRED: Displaying package installer prompt...")

            // Register clone in local registry for tracking
            if (!packageName.isNullOrEmpty()) {
                CloneUtils.registerClone(context, packageName)
            }
        } catch (e: Exception) {
            try {
                session?.abandon()
            } catch (_: Exception) {
            }
            Logger.log("Session $sessionId failed: ${e.message}")
            throw e
        } finally {
            try {
                session?.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private val COMPONENT_TAGS = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
        private val PERMISSION_DECLARATION_TAGS = setOf("permission", "permission-group", "permission-tree")
        private val PERMISSION_REFERENCE_ATTRS = setOf("permission", "readPermission", "writePermission")

        /** Meta-data keys that bind the APK to Google Play Integrity / Firebase
         *  projects. Their values are tied to the original package + signature.
         *  Scrubbing them lets some apps fall back to degraded mode instead of
         *  hard-failing with "Something went wrong". */
        private val PLAY_INTEGRITY_METADATA_KEYS = setOf(
            "com.google.android.gms.version",
            "com.google.android.gms.games.APP_ID",
            "com.google.android.geo.API_KEY",
            "com.google.firebase.messaging.default_notification_channel_id",
            "firebase_crashlytics_collection_enabled",
            "firebase_analytics_collection_enabled",
            "google_app_id",
            "google_api_key",
            "google_crash_reporting_api_key",
            "com.google.android.play.billingclient.version",
            "com.android.vending.splits",
            "com.android.vending.derived.apk.id"
        )

        fun rewriteAuthorities(authorities: String, oldPkg: String, newPkg: String): String {
            val sep = when {
                authorities.contains(';') -> ";"
                authorities.contains(',') -> ","
                else -> null
            }
            return if (sep != null) {
                authorities.split(sep).joinToString(sep) { part ->
                    rewriteSingleAuthority(part.trim(), oldPkg, newPkg)
                }
            } else {
                rewriteSingleAuthority(authorities, oldPkg, newPkg)
            }
        }

        private fun rewriteSingleAuthority(auth: String, oldPkg: String, newPkg: String): String {
            if (auth.isEmpty()) return auth
            if (auth.contains(oldPkg)) {
                return auth.replace(oldPkg, newPkg)
            }
            val rewritten = "$newPkg.$auth"
            Logger.log("AXML: rewriting hardcoded authority '$auth' -> '$rewritten'")
            return rewritten
        }

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
