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
import android.os.Build
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

        // 1. Process Base APK
        val baseSourceFile = File(appInfo.sourceDir)
        val baseTargetFile = File(workDir, "base_signed.apk")
        Logger.log("Processing Base APK: ${baseSourceFile.name}")
        processSingleApk(baseSourceFile, baseTargetFile, oldPackageName, targetPackageName, badgeIcon = true)
        clonedApkFiles.add(baseTargetFile)

        // 2. Process Split APKs (if present)
        val splitDirs = appInfo.splitSourceDirs
        if (!splitDirs.isNullOrEmpty()) {
            Logger.log("Found ${splitDirs.size} split APKs. Processing splits...")
            splitDirs.forEachIndexed { index, splitPath ->
                val splitSourceFile = File(splitPath)
                val splitTargetFile = File(workDir, "split_${index}_signed.apk")
                Logger.log("Processing Split APK [$index]: ${splitSourceFile.name}")
                // Launcher icon assets live in the base APK; splits don't
                // need (and shouldn't get) their own badge pass.
                processSingleApk(splitSourceFile, splitTargetFile, oldPackageName, targetPackageName, badgeIcon = false)
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

            // Rewrite binary AXML manifest parameters and (for the base
            // APK) badge the launcher icon, in place inside the zip
            rewriteApkContents(tempUnsigned, oldPackageName, newPackageName, badgeIcon)

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

        /** Elements that declare a custom permission by name. */
        private val PERMISSION_DECLARATION_TAGS = setOf("permission", "permission-group", "permission-tree")

        /** Attributes that reference a (possibly custom) permission name to
         *  gate access to a component. */
        private val PERMISSION_REFERENCE_ATTRS = setOf("permission", "readPermission", "writePermission")
    }
}
