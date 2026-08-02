package com.savedbylight.appclonerclassic

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
        val splitDirs = appInfo.splitSourceDirs
        Logger.log(
            "=== Clone started: $oldPackageName -> $targetPackageName " +
                "(base + ${splitDirs?.size ?: 0} split APK(s), workDir=${workDir.absolutePath}) ==="
        )

        try {
            // 1. Process Base APK
            val baseSourceFile = File(appInfo.sourceDir)
            val baseTargetFile = File(workDir, "base_signed.apk")
            Logger.log("Processing Base APK: ${baseSourceFile.name} (${baseSourceFile.length()} bytes)")
            processSingleApk(baseSourceFile, baseTargetFile, oldPackageName, targetPackageName, badgeIcon = true)
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
                    clonedApkFiles.add(splitTargetFile)
                }
            } else {
                Logger.log("No split APKs to process.")
            }

            val totalBytes = clonedApkFiles.sumOf { it.length() }
            Logger.log(
                "=== Clone finished: ${clonedApkFiles.size} APK(s) produced for $targetPackageName " +
                    "($totalBytes bytes total) ==="
            )
            exportForDebugging(clonedApkFiles)
            return clonedApkFiles
        } catch (e: Exception) {
            Logger.log(
                "=== Clone FAILED for $oldPackageName -> $targetPackageName: " +
                    "${e.javaClass.simpleName}: ${e.message} ==="
            )
            throw e
        }
    }

    /**
     * Copies the final signed APKs to app-specific external storage
     * (getExternalFilesDir), which `adb pull` can read without root even on
     * a non-debuggable release build — unlike the private cache dir the
     * clone pipeline normally works in, which requires `run-as` (only
     * available on debuggable builds) or a rooted device.
     *
     * This is purely a debugging aid: it lets you run
     *   adb pull /storage/emulated/0/Android/data/<pkg>/files/clone_debug_export /tmp/clone_debug
     *   cd /tmp/clone_debug && adb install-multiple -r *.apk
     * to install the exact same APKs the app generated directly via the
     * `pm` CLI path, which prints the underlying PackageParserException
     * message straight to the terminal instead of swallowing it the way
     * the GUI PackageInstaller flow does. Failures here are logged but
     * never thrown — this must never block a real clone/install.
     */
    private fun exportForDebugging(apkFiles: List<File>) {
        try {
            val exportDir = File(context.getExternalFilesDir(null), "clone_debug_export")
            exportDir.deleteRecursively()
            exportDir.mkdirs()
            apkFiles.forEach { it.copyTo(File(exportDir, it.name), overwrite = true) }
            Logger.log("Exported ${apkFiles.size} APK(s) for debugging to: ${exportDir.absolutePath}")
        } catch (e: Exception) {
            Logger.log("Debug export failed (non-fatal): ${e.javaClass.simpleName}: ${e.message}")
        }
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
     */
    fun launchInstall(targetPackageName: String?, apkFiles: List<File>) {
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
        Logger.log("cloneAndInstallApp: ${appInfo.packageName} -> $targetPackageName")
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
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.log("FAILED to launch install intent for ${apkFile.name}: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
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
            signApkBinary(tempUnsigned, destination)

            // Sanity validation to guarantee destination file existence
            if (!destination.exists() || destination.length() == 0L) {
                Logger.log("  FAILED: destination APK missing or empty at ${destination.absolutePath}")
                throw IOException("Failed to generate destination APK at: ${destination.absolutePath}")
            }
            Logger.log("  Finished ${destination.name} (${destination.length()} bytes)")
        } catch (e: Exception) {
            Logger.log(
                "  FAILED processing ${source.name} -> ${destination.name}: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            throw e
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
        var entryCount = 0
        var manifestRewritten = false
        var iconsBadged = 0
        var entriesAligned = 0
        try {
            ZipFile(apkFile).use { zipIn ->
                val counting = CountingOutputStream(FileOutputStream(rewrittenApk))
                ZipOutputStream(counting).use { zipOut ->
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        entryCount++
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
                            // STORED entries require exact size/CRC to be
                            // declared up front; DEFLATED entries let
                            // ZipOutputStream compute these on the fly.
                            outEntry.method = ZipEntry.STORED
                            outEntry.size = outBytes.size.toLong()
                            outEntry.compressedSize = outBytes.size.toLong()
                            outEntry.crc = CRC32().apply { update(outBytes) }.value

                            // Re-authoring the zip from scratch discards the
                            // original build tooling's alignment padding.
                            // Modern AGP defaults to extractNativeLibs=false,
                            // which means native .so libraries (and
                            // resources.arsc) are mmap'd directly out of the
                            // zip rather than extracted — that only works if
                            // their data starts on a page boundary. Without
                            // this, PackageManager rejects the APK at
                            // install time with a generic "problem with the
                            // app file" parse failure, even though the zip
                            // and signature are otherwise perfectly valid.
                            // Replicates the same 0xd935 "Android alignment"
                            // extra-field scheme zipalign itself writes, so
                            // it's also detectable by `zipalign -c -v`.
                            val alignment = alignmentFor(entry.name)
                            if (alignment > 1) {
                                val filenameLen = outEntry.name.toByteArray(Charsets.UTF_8).size
                                val dataOffsetSansExtra = counting.count + LOCAL_HEADER_FIXED_SIZE + filenameLen
                                val extra = alignmentExtraField(dataOffsetSansExtra, alignment)
                                if (extra.isNotEmpty()) {
                                    outEntry.setExtra(extra)
                                    entriesAligned++
                                }
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
                "  Rewrote zip contents: $entryCount entries total, " +
                    "manifest rewritten=$manifestRewritten, icons badged=$iconsBadged, " +
                    "entries page-aligned=$entriesAligned"
            )
            if (!manifestRewritten) {
                Logger.log("  WARNING: no AndroidManifest.xml entry found in this APK — package name was NOT rewritten")
            }
        } catch (e: Exception) {
            Logger.log("  FAILED rewriting zip contents after $entryCount entries: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        } finally {
            rewrittenApk.delete()
        }
    }

    /** Alignment (in bytes) required for a STORED entry's data to be safely
     *  mmap-able. Native libraries need page alignment — 16384 satisfies
     *  both the legacy 4096-byte page size and the newer 16 KB page size
     *  Android supports — everything else that's stored uncompressed
     *  (chiefly resources.arsc) only needs the generic 4-byte zip
     *  alignment. Returns 1 (no alignment needed) for anything else. */
    private fun alignmentFor(entryName: String): Int {
        val lower = entryName.lowercase()
        return when {
            lower.startsWith("lib/") && lower.endsWith(".so") -> 16384
            lower == "resources.arsc" -> 4
            else -> 1
        }
    }

    /** Builds a zip "extra field" block that pads a STORED entry's local
     *  header so its data section starts on an [alignment]-byte boundary —
     *  the same technique (and extra-field ID, 0xd935) the standalone
     *  zipalign tool uses. [dataOffsetSansExtra] is the absolute file
     *  offset where the entry's data would start if this entry had a
     *  zero-length extra field (i.e. local header + filename, with no
     *  extra bytes yet) — from there we compute how much padding closes
     *  the gap to the next alignment boundary. Returns an empty array if
     *  no padding is needed. */
    private fun alignmentExtraField(dataOffsetSansExtra: Long, alignment: Int): ByteArray {
        val remainder = (dataOffsetSansExtra % alignment).toInt()
        if (remainder == 0) return ByteArray(0)
        // Total padding (id[2] + size[2] + alignment-value[2] + filler) must
        // be at least 6 bytes to form one valid TLV block; if the raw gap
        // is smaller than that, push to the next alignment multiple.
        var padding = alignment - remainder
        while (padding < 6) padding += alignment
        val dataLen = padding - 4 // bytes following the id/size header fields
        val out = ByteArray(padding)
        out[0] = 0x35 // extra field id 0xd935, little-endian
        out[1] = 0xd9.toByte()
        out[2] = (dataLen and 0xFF).toByte() // extra field data length, little-endian
        out[3] = ((dataLen shr 8) and 0xFF).toByte()
        out[4] = (alignment and 0xFF).toByte() // alignment value, little-endian
        out[5] = ((alignment shr 8) and 0xFF).toByte()
        // Remaining filler bytes stay zero-initialized.
        return out
    }

    /** Thin wrapper that tracks the absolute number of bytes written so far
     *  so we know the exact file offset ZipOutputStream is about to write
     *  the next entry's local header at — needed to compute alignment
     *  padding, since java.util.zip.ZipOutputStream doesn't expose its own
     *  position. ZipOutputStream writes every header/data byte straight
     *  through to its underlying stream (it has to, in order to track
     *  accurate offsets for its own central directory), so this count
     *  stays exactly in sync with the real file position. */
    private class CountingOutputStream(private val out: java.io.OutputStream) : java.io.OutputStream() {
        var count: Long = 0
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
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
        Logger.log("  Signing ${inputFile.name} -> ${outputFile.name} (v1/v2/v3, minSdk=26)")
        try {
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
            Logger.log("  Signed successfully: ${outputFile.name} (${outputFile.length()} bytes)")
        } catch (e: Exception) {
            Logger.log("  SIGNING FAILED for ${inputFile.name}: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
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
     *   - <activity-alias android:targetActivity="...">    — prefix match (FQCN reference)
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
                    // NOTE: android:name on <application>/<activity>/<service>/etc.
                    // and android:targetActivity on <activity-alias> are literal
                    // fully-qualified class names that must match what's actually
                    // compiled into classes.dex. The clone pipeline never touches
                    // the dex, so these must NOT be rewritten to the new package —
                    // doing so points the manifest at a class that doesn't exist
                    // anywhere in the APK set (ClassNotFoundException at launch).
                    // Only android:process is safe to rewrite here, since it's
                    // just an OS process label, not a class reference.
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
     */
    private fun installPackageSession(context: Context, packageName: String?, apkFiles: List<File>) {
        if (apkFiles.isEmpty()) {
            throw IllegalArgumentException("No APK files provided for installation.")
        }

        Logger.log("Creating install session for ${packageName ?: "(unspecified package)"}: ${apkFiles.size} APK(s)")
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (!packageName.isNullOrEmpty()) {
            params.setAppPackageName(packageName)
        }

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

        /** Elements that declare a custom permission by name. */
        private val PERMISSION_DECLARATION_TAGS = setOf("permission", "permission-group", "permission-tree")

        /** Attributes that reference a (possibly custom) permission name to
         *  gate access to a component. */
        private val PERMISSION_REFERENCE_ATTRS = setOf("permission", "readPermission", "writePermission")

        private const val PREFS_NAME = "app_cloner_prefs"
        private const val PREF_KEY_INSTALLER_PACKAGE = "preferred_installer_package"

        /** Fixed byte size of a ZIP local file header, before the variable-
         *  length filename and extra field — used to compute zip-alignment
         *  padding in [rewriteApkContents]. */
        private const val LOCAL_HEADER_FIXED_SIZE = 30L

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
