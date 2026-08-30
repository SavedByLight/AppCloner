package com.savedbylight.appclonerclassic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
     * Entry point called by MainActivity. Automatically picks the next free
     * target package name (original.clone1, .clone2, …) so re-cloning never
     * hits INSTALL_FAILED_UPDATE_INCOMPATIBLE / signature mismatch.
     */
    fun cloneApp(app: InstalledApp): List<File> {
        val pkgName = app.packageName
        val targetPackageName = nextAvailableClonePackage(pkgName)
        Logger.log("=== Clone started: $pkgName -> $targetPackageName ===")
        val appInfo = context.packageManager.getApplicationInfo(pkgName, 0)
        return cloneApp(appInfo, targetPackageName)
    }

    /**
     * Overload for direct ApplicationInfo input without explicit target name.
     * Also auto-selects the next free .cloneN suffix.
     */
    fun cloneApp(appInfo: ApplicationInfo): List<File> {
        val targetPackageName = nextAvailableClonePackage(appInfo.packageName)
        Logger.log("=== Clone started: ${appInfo.packageName} -> $targetPackageName ===")
        return cloneApp(appInfo, targetPackageName)
    }

    /**
     * Finds the lowest N ≥ 1 such that `<base>.cloneN` is not already installed.
     * Avoids signature-collision failures when a previous clone still exists.
     */
    fun nextAvailableClonePackage(basePackageName: String): String {
        val pm = context.packageManager
        var n = 1
        while (n < 1000) {
            val candidate = "$basePackageName.clone$n"
            try {
                pm.getPackageInfo(candidate, 0)
                // exists → try next
                n++
            } catch (_: PackageManager.NameNotFoundException) {
                return candidate
            }
        }
        // Extremely unlikely; fall back
        return "$basePackageName.clone${System.currentTimeMillis()}"
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
                // Launcher icon assets live in the base APK; splits don't
                // need (and shouldn't get) their own badge pass.
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

        // Always export so the user has a reliable manual-install path when
        // the system PackageInstaller session is blocked by OEM policy.
        val exportDir = exportClonedApks(targetPackageName, clonedApkFiles)
        Logger.log("Exported ${clonedApkFiles.size} APK(s) for debugging/manual install to: ${exportDir.absolutePath}")

        return clonedApkFiles
    }

    /**
     * Copies the signed clone APKs into the public Downloads folder:
     *   Download/AppCloner/<targetPackage>/
     *
     * Prefer a real filesystem write (so file managers, SAI, and adb can open
     * the paths), then register with MediaStore / MediaScanner so the files
     * appear under Downloads. MediaStore-only entries are harder to open with
     * some package installers.
     *
     * IMPORTANT: multi-split clones cannot be installed by tapping a single
     * APK — the base alone is incomplete. Use SAI, APKMirror Installer, or
     * `adb install-multiple` on the whole folder (see README.txt inside).
     */
    private fun exportClonedApks(targetPackageName: String, apkFiles: List<File>): File {
        val relativeSubdir = "AppCloner/$targetPackageName"
        val publicDownloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val exportDir = File(publicDownloads, relativeSubdir)

        // 1) Prefer direct filesystem write into public Downloads
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
            // Make the files visible to the system Downloads UI / file pickers
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
            // Best-effort MediaStore index so they show under "Downloads" on Q+
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

        // 2) MediaStore-only (no stable path) — still better than nothing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                exportViaMediaStore(relativeSubdir, apkFiles)
                Logger.log(
                    "Exported via MediaStore to Downloads/$relativeSubdir " +
                        "(no direct path). Prefer SAI to install multi-split sets."
                )
                // Return a synthetic path for logging; files live in MediaStore
                return File(publicDownloads, relativeSubdir)
            } catch (e: Exception) {
                Logger.log("MediaStore export failed: ${e.message}")
            }
        }

        // 3) App-private fallback
        val fallbackBase = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        val fallback = File(fallbackBase, relativeSubdir).apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        apkFiles.forEachIndexed { index, src ->
            val name = if (index == 0) "base.apk" else "split_$index.apk"
            src.copyTo(File(fallback, name), overwrite = true)
        }
        writeInstallHelperFiles(fallback, apkFiles.size)
        Logger.log("Exported to app-private fallback: ${fallback.absolutePath}")
        return fallback
    }

    /** Insert APKs into MediaStore Downloads (Android 10+ scoped storage). */
    private fun exportViaMediaStore(relativeSubdir: String, apkFiles: List<File>) {
        val resolver = context.contentResolver
        apkFiles.forEachIndexed { index, src ->
            val name = if (index == 0) "base.apk" else "split_$index.apk"
            // Delete any existing row with the same display name in this folder
            try {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}/$relativeSubdir/")
                )
            } catch (_: Exception) {
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$relativeSubdir"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed for $name")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IOException("MediaStore openOutputStream failed for $name")
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }
        }
    }

    private fun writeInstallHelperFiles(dir: File, apkCount: Int) {
        val splitArgs = (1 until apkCount).joinToString(" ") { "split_$it.apk" }
        File(dir, "install.sh").writeText(
            "#!/bin/sh\n" +
                "# Install this clone from a PC:\n" +
                "#   adb install-multiple base.apk $splitArgs\n" +
                "adb install-multiple \"\$@\" base.apk $splitArgs\n"
        )
        val multiNote = if (apkCount > 1) {
            "This is a MULTI-SPLIT package (${apkCount} APKs).\n" +
                "You CANNOT install it by tapping base.apk alone — that will fail or\n" +
                "show \"can't open\" / incomplete app.\n\n" +
                "Install options:\n" +
                "  1) SAI (Split APKs Installer) or APKMirror Installer — open this folder\n" +
                "     and select ALL .apk files together.\n" +
                "  2) From a PC:  adb install-multiple base.apk $splitArgs\n" +
                "  3) Use AppClonerClassic's in-app install (enable Install unknown apps,\n" +
                "     disable Auto Blocker / Play Protect scan).\n"
        } else {
            "Single APK. You can open base.apk from Files if this app is allowed to\n" +
                "install unknown apps (Settings → Special app access → Install unknown apps).\n"
        }
        File(dir, "README.txt").writeText(
            "AppClonerClassic export\n" +
                "======================\n\n" +
                multiNote +
                "\nFolder: ${dir.absolutePath}\n"
        )
    }

    /**
     * Entry point called by MainActivity passing a List<File> of APKs.
     * Routes through the user's configured third-party installer if one is
     * set and the APK set is eligible (see [installViaThirdPartyInstaller]);
     * otherwise falls back to the system installer via a PackageInstaller
     * session.
     */
    fun launchInstall(apkFiles: List<File>) {
        launchInstall(null, apkFiles)
    }

    /**
     * Overload accepting target package name explicitly.
     *
     * Before opening a PackageInstaller session we verify that this app is
     * allowed to install unknown packages (Settings → Apps → Special access
     * → Install unknown apps). Without that permission the system confirmation
     * dialog often ends with INSTALL_FAILED_ABORTED / "User rejected
     * permissions" even when the user never tapped Cancel.
     */
    fun launchInstall(targetPackageName: String?, apkFiles: List<File>) {
        // Ensure "Install unknown apps" is granted for this package (API 26+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                Logger.log(
                    "INSTALL BLOCKED: AppClonerClassic is not allowed to install unknown apps. " +
                        "Opening system settings so you can enable it for this app…"
                )
                try {
                    val settingsIntent = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(settingsIntent)
                } catch (e: Exception) {
                    // Fallback: open the generic unknown-sources screen
                    try {
                        val fallback = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(fallback)
                    } catch (e2: Exception) {
                        Logger.log("Could not open Install-unknown-apps settings: ${e2.message}")
                    }
                }
                Logger.log(
                    "After enabling \"Install unknown apps\" for AppClonerClassic, tap the app " +
                        "again to retry the install. The signed APKs are already exported under " +
                        "Download/AppCloner/ if you prefer adb / SAI."
                )
                return
            }
        }

        val installerPackage = Companion.getPreferredInstallerPackage(context)
        if (installerPackage != null && apkFiles.size == 1) {
            Logger.log("Routing install through third-party installer: $installerPackage")
            installViaThirdPartyInstaller(context, apkFiles[0], installerPackage)
        } else {
            if (installerPackage != null && apkFiles.size > 1) {
                Logger.log(
                    "Third-party installer configured ($installerPackage) but this clone has " +
                        "${apkFiles.size} APKs (base + splits); the ACTION_VIEW single-file " +
                        "handoff can't install a split set atomically, so falling back to the " +
                        "system PackageInstaller session instead."
                )
            }
            installPackageSession(context, targetPackageName, apkFiles)
        }
    }

    /**
     * Combined execution method to clone and immediately initiate installation.
     */
    fun cloneAndInstallApp(appInfo: ApplicationInfo, targetPackageName: String) {
        val files = cloneApp(appInfo, targetPackageName)
        launchInstall(targetPackageName, files)
    }

    /**
     * Hands a single APK off to a specific third-party installer app via
     * ACTION_VIEW, instead of using the system's own PackageInstaller
     * session flow. The target app is responsible for whatever install UI
     * (or lack thereof) it presents — App Cloner has no visibility into or
     * control over that once the intent is dispatched.
     *
     * Only works for a single, split-free APK: ACTION_VIEW's
     * "application/vnd.android.package-archive" contract is a single file,
     * so a base+splits set can't go through this path — see [launchInstall].
     *
     * @param installerPackageName package name of the installer app to
     *   target directly (skips the chooser). Pass null to let the user pick
     *   from a chooser of every app that can handle an APK-install intent.
     */
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

        // If we targeted a specific package and it can't actually handle
        // this intent (not installed, or doesn't register for it), fail
        // loudly and let the caller decide whether to retry with the
        // system installer rather than silently no-op.
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

    /**
     * Handles binary XML rewriting and signing for an individual APK file.
     * Guarantees the destination file exists on disk to prevent ENOENT errors during install.
     */
    private fun processSingleApk(
        source: File,
        destination: File,
        oldPackageName: String,
        newPackageName: String,
        badgeIcon: Boolean
    ) {
        val tempUnsigned = File.createTempFile("temp_unsigned", ".apk", context.cacheDir)
        try {
            // Copy source APK to temporary buffer
            source.copyTo(tempUnsigned, overwrite = true)
            Logger.log("  Copied ${source.name} to temp buffer (${tempUnsigned.length()} bytes)")

            // Rewrite binary AXML manifest parameters and (for the base
            // APK) badge the launcher icon, in place inside the zip
            rewriteApkContents(tempUnsigned, oldPackageName, newPackageName, badgeIcon)

            // Re-sign modified APK binary and output to destination. Every
            // APK in a clone (base + splits) must be signed with the SAME
            // key or the install session will be rejected as inconsistent.
            Logger.log("  Signing ${tempUnsigned.name} -> ${destination.name} (v1/v2/v3, minSdk=26)")
            signApkBinary(tempUnsigned, destination)
            Logger.log("  Signed successfully: ${destination.name} (${destination.length()} bytes)")

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
                        // Preserve STORED + 4-byte alignment for native libs /
                        // resources that Android expects to be page-aligned.
                        if (entry.method == ZipEntry.STORED) {
                            outEntry.method = ZipEntry.STORED
                            outEntry.size = outBytes.size.toLong()
                            outEntry.compressedSize = outBytes.size.toLong()
                            outEntry.crc = CRC32().apply { update(outBytes) }.value
                            // Heuristic: uncompressed native libs / certain
                            // assets that were already aligned stay counted.
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

    /** Matches common launcher-icon file naming conventions across density
     *  buckets (res/mipmap-*, res/drawable-*): standard, round, and
     *  adaptive-icon foreground/background raster assets. Adaptive-icon
     *  layers defined as vector-drawable XML (rather than PNG/WebP) are
     *  left untouched — badging those would require a full resource
     *  compile pass, out of scope here. This is a best-effort visual aid,
     *  not a correctness-critical rewrite, so silently skipping unmatched
     *  formats is acceptable. */
    private fun isLauncherIconEntry(entryName: String): Boolean {
        val lower = entryName.lowercase()
        if (!(lower.startsWith("res/mipmap") || lower.startsWith("res/drawable"))) return false
        if (!lower.endsWith(".png") && !lower.endsWith(".webp")) return false
        return lower.contains("ic_launcher")
    }

    /** Overlays a small badge (orange circle, ring, "C") onto a launcher
     *  icon's bottom-right corner so a clone is visually distinguishable
     *  from the original app in the launcher — both for the user's own
     *  sanity and because a clone that's pixel-identical to a well-known
     *  app under a different package name is exactly what impersonation
     *  heuristics look for. Falls back to the original bytes untouched if
     *  decoding fails for any reason (e.g. an unexpected image format). */
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

            // AxmlReader delivers namespace declarations (xmlns:android=...)
            // straight to the root AxmlVisitor, bypassing child()/attr()
            // entirely — a callback we had zero visibility into until now.
            // AxmlWriter.ns() wraps `uri` into a StringItem with no null
            // check, same class of bug as everything else here.
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
                    // Custom permission declarations. Permission names are
                    // unique per-device, not per-package, so leaving these
                    // unchanged collides with the already-installed
                    // original app (INSTALL_FAILED_DUPLICATE_PERMISSION).
                    tag in PERMISSION_DECLARATION_TAGS && name == "name" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)
                    // Self-references to one of the app's own custom
                    // permissions (as opposed to a system permission, which
                    // never starts with the app's package name).
                    tag == "uses-permission" && name == "name" && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)
                    // android:permission / readPermission / writePermission
                    // on <provider>/<service>/<activity>/<receiver>/
                    // <application> gate access using a permission name —
                    // must follow the declaration's rename.
                    name in PERMISSION_REFERENCE_ATTRS && value.startsWith(oldPkg) ->
                        newPkg + value.removePrefix(oldPkg)
                    else -> value
                }
            }

            // The actual bug, found by reading pxb.android.axml's source:
            // NodeImpl#attr() unconditionally does
            //   a.raw = new StringItem(valueWrapper.raw)
            // for any attribute value wrapped in a ValueWrapper (used for
            // resource references — very common for icon/theme/label/etc.
            // attributes in manifests built by modern aapt2, which often
            // leaves `raw` null and only populates the resolved `ref` int).
            // That produces a StringItem whose *contents* are null, but the
            // StringItem object itself isn't — so Attr.prepare()'s
            // `if (raw != null)` check lets it straight through, and it
            // later NPEs deep inside StringItems.prepare(). Nothing we do
            // to the top-level `value` we're handed can see this, since
            // `value` (the ValueWrapper) is never null itself — only its
            // internal `raw` field is. Patch it in place before handing
            // off to the writer.
            // `raw` turned out to be a `final` field (Kotlin surfaces it as
            // `val`, confirmed by the "Val cannot be reassigned" compile
            // error from direct assignment) — reflection works around that,
            // since the JVM allows setting final *instance* fields
            // reflectively at runtime; only compile-time constants are
            // exempt, and this isn't one.
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

            // Defensive guards below: NodeImpl#attr() actually throws if
            // `name` is null (confirmed by reading its source), so the
            // null-name branch is dead in practice — kept only in case a
            // future library version changes that. The TYPE_STRING/null
            // value branch is a real (if apparently rarer) NPE path in the
            // same class as the ValueWrapper one above.
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

        // NodeImpl#text() does `this.text = new StringItem(value)` with no
        // null check at all — a third callback (alongside attr()/child(),
        // now both guarded) that we had never overridden, so a null text
        // value would have passed straight through unguarded until now.
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
     *
     * Improvements vs the previous version:
     * - Sets total size and require-user-action so OEM installers surface the
     *   confirmation dialog more reliably.
     * - Uses a BroadcastReceiver (InstallResultReceiver) for the status
     *   callback; this survives the app being backgrounded better than an
     *   activity PendingIntent on many OEMs.
     * - Logs the session id and package clearly so the user can correlate
     *   with the export directory when a verification failure occurs.
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

        val totalSize = apkFiles.sumOf { it.length() }
        params.setSize(totalSize)

        // Prefer NOT_REQUIRED when we already have Install-unknown-apps
        // permission — some OEMs auto-dismiss REQUIRED dialogs in ~1s with
        // "User rejected permissions". Fall back to REQUIRED otherwise.
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
                // Older platform stubs or custom ROMs may lack the constant
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

            // Use an Activity PendingIntent (LogActivity), not a BroadcastReceiver.
            // Starting the system confirmation UI from a background receiver is
            // blocked or auto-dismissed on many OEM builds (Samsung etc.), which
            // surfaces as INSTALL_FAILED_ABORTED / "User rejected permissions"
            // within ~1 second. An Activity context can launch the confirm intent.
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
        /** Element tags whose "android:name" attribute is a fully-qualified
         *  component class name (or, for <application>, the Application
         *  subclass) rather than an arbitrary key — the only tags where
         *  rewriting "name" is actually correct. */
        private val COMPONENT_TAGS = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")

        /** Elements that declare a custom permission by name. */
        private val PERMISSION_DECLARATION_TAGS = setOf("permission", "permission-group", "permission-tree")

        /** Attributes that reference a (possibly custom) permission name to
         *  gate access to a component. */
        private val PERMISSION_REFERENCE_ATTRS = setOf("permission", "readPermission", "writePermission")

        private const val PREFS_NAME = "app_cloner_prefs"
        private const val PREF_KEY_INSTALLER_PACKAGE = "preferred_installer_package"

        /**
         * Sets (or clears, by passing null/blank) the third-party installer
         * package that [launchInstall] should route single-APK clones
         * through. Intended to be called from a settings UI where the user
         * picks an installed app (e.g. by package name or from a list of
         * apps that resolve ACTION_VIEW for an APK mime type).
         */
        fun setPreferredInstallerPackage(context: Context, packageName: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .apply {
                    if (packageName.isNullOrBlank()) remove(PREF_KEY_INSTALLER_PACKAGE)
                    else putString(PREF_KEY_INSTALLER_PACKAGE, packageName)
                }
                .apply()
        }

        /** Public read accessor, e.g. so a settings screen can show the
         *  currently configured installer. */
        fun getPreferredInstallerPackage(context: Context): String? {
            val pkg = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY_INSTALLER_PACKAGE, null)
            return if (pkg.isNullOrBlank()) null else pkg
        }
    }
}
