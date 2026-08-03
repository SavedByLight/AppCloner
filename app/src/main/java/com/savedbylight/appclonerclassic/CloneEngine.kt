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
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.instruction.formats.Instruction31c
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.writer.pool.DexPool
import pxb.android.axml.AxmlReader
import pxb.android.axml.AxmlVisitor
import pxb.android.axml.AxmlWriter
import pxb.android.axml.NodeVisitor
import pxb.android.axml.ValueWrapper
import java.io.ByteArrayInputStream
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
        val splitDirs = appInfo.splitSourceDirs
        Logger.log(
            "=== Clone started: $oldPackageName -> $targetPackageName " +
                "(base + ${splitDirs?.size ?: 0} split APK(s), workDir=${workDir.absolutePath}) ==="
        )

        val dangerousMethods = findDangerousMethods(baseSourceFile, oldPackageName)
        if (dangerousMethods.isNotEmpty()) {
            Logger.log("Found ${dangerousMethods.size} dangerous method(s) – will rewrite package strings unconditionally inside those methods.")
        } else {
            Logger.log("No dangerous methods detected; using safe token‑based rewriting.")
        }

        try {
            val baseTargetFile = File(workDir, "base_signed.apk")
            Logger.log("Processing Base APK: ${baseSourceFile.name} (${baseSourceFile.length()} bytes)")
            processSingleApk(baseSourceFile, baseTargetFile, oldPackageName, targetPackageName,
                badgeIcon = true, dangerousMethods = dangerousMethods)
            clonedApkFiles.add(baseTargetFile)

            if (!splitDirs.isNullOrEmpty()) {
                Logger.log("Found ${splitDirs.size} split APK(s). Processing splits...")
                splitDirs.forEachIndexed { index, splitPath ->
                    val splitSourceFile = File(splitPath)
                    val splitTargetFile = File(workDir, "split_${index}_signed.apk")
                    Logger.log("Processing Split APK [$index]: ${splitSourceFile.name} (${splitSourceFile.length()} bytes)")
                    processSingleApk(splitSourceFile, splitTargetFile, oldPackageName, targetPackageName,
                        badgeIcon = false, dangerousMethods = dangerousMethods)
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

    private fun exportForDebugging(apkFiles: List<File>) {
        try {
            val exportDir = File(context.getExternalFilesDir(null), "clone_debug_export")
            exportDir.deleteRecursively()
            exportDir.mkdirs()
            apkFiles.forEach { it.copyTo(File(exportDir, it.name), overwrite = true) }
            Logger.log("Exported ${apkFiles.size} APK(s) for debugging to: ${exportDir.absolutePath}")
        } catch (e: Exception) {
            Logger.log("Debug export failed (non‑fatal): ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun launchInstall(apkFiles: List<File>) {
        launchInstall(null, apkFiles)
    }

    fun launchInstall(targetPackageName: String?, apkFiles: List<File>) {
        val installerPackage = Companion.getPreferredInstallerPackage(context)
        if (installerPackage != null && apkFiles.size == 1) {
            Logger.log("Routing install through third‑party installer: $installerPackage")
            installViaThirdPartyInstaller(context, apkFiles[0], installerPackage)
        } else {
            if (installerPackage != null && apkFiles.size > 1) {
                Logger.log(
                    "Third‑party installer configured ($installerPackage) but clone has " +
                        "${apkFiles.size} APKs – falling back to system PackageInstaller."
                )
            }
            installPackageSession(context, targetPackageName, apkFiles)
        }
    }

    fun cloneAndInstallApp(appInfo: ApplicationInfo, targetPackageName: String) {
        Logger.log("cloneAndInstallApp: ${appInfo.packageName} -> $targetPackageName")
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
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.log("FAILED to launch install intent for ${apkFile.name}: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    // -------------------------------------------------------------------------
    //  DANGEROUS METHOD SCANNER (fix descriptor building)
    // -------------------------------------------------------------------------

    private fun findDangerousMethods(apk: File, packageName: String): Set<String> {
        val dangerousMethods = mutableSetOf<String>()
        ZipFile(apk).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".dex")) continue

                val dexBytes = zip.getInputStream(entry).use { it.readBytes() }
                val dexFile = try {
                    DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(dexBytes))
                } catch (e: Exception) {
                    Logger.log("Dex scan: failed to parse ${entry.name}, skipping: ${e.message}")
                    continue
                }

                for (classDef in dexFile.classes) {
                    for (method in classDef.methods) {
                        val impl = method.implementation ?: continue
                        var hasToggle = false
                        var hasPackageString = false

                        for (instruction in impl.instructions) {
                            val ref = (instruction as? ReferenceInstruction)?.reference
                            when (ref) {
                                is MethodReference -> {
                                    if (ref.name in TOGGLE_METHOD_NAMES) hasToggle = true
                                }
                                is StringReference -> {
                                    if (ref.string.contains(packageName)) hasPackageString = true
                                }
                                else -> {}
                            }
                            if (hasToggle && hasPackageString) break
                        }

                        if (hasToggle && hasPackageString) {
                            // Build the correct method descriptor
                            val params = method.parameterTypes.joinToString("")
                            val descriptor = "${classDef.type}->${method.name}($params)${method.returnType}"
                            dangerousMethods.add(descriptor)
                            Logger.log("Dangerous method: $descriptor (in ${entry.name})")
                        }
                    }
                }
            }
        }
        return dangerousMethods
    }

    // -------------------------------------------------------------------------
    //  PROCESS SINGLE APK
    // -------------------------------------------------------------------------

    private fun processSingleApk(
        source: File,
        destination: File,
        oldPackageName: String,
        newPackageName: String,
        badgeIcon: Boolean,
        dangerousMethods: Set<String>
    ) {
        val tempUnsigned = File.createTempFile("temp_unsigned", ".apk", context.cacheDir)
        try {
            source.copyTo(tempUnsigned, overwrite = true)
            Logger.log("  Copied ${source.name} to temp buffer (${tempUnsigned.length()} bytes)")

            rewriteApkContents(tempUnsigned, oldPackageName, newPackageName, badgeIcon, dangerousMethods)

            signApkBinary(tempUnsigned, destination)

            if (!destination.exists() || destination.length() == 0L) {
                throw IOException("Failed to generate destination APK at: ${destination.absolutePath}")
            }
            Logger.log("  Finished ${destination.name} (${destination.length()} bytes)")
        } catch (e: Exception) {
            Logger.log("  FAILED processing ${source.name} -> ${destination.name}: ${e.message}")
            throw e
        } finally {
            if (tempUnsigned.exists()) tempUnsigned.delete()
        }
    }

    // -------------------------------------------------------------------------
    //  REWRITE APK CONTENTS – with manifest rewriting and authority extraction
    // -------------------------------------------------------------------------

    private fun rewriteApkContents(
        apkFile: File,
        oldPackageName: String,
        newPackageName: String,
        badgeIcon: Boolean,
        dangerousMethods: Set<String>
    ) {
        val rewrittenApk = File.createTempFile("apk_rewrite", ".apk", context.cacheDir)
        var entryCount = 0
        var manifestRewritten = false
        var iconsBadged = 0
        var entriesAligned = 0
        var dexStringsPatched = 0
        var authorityMap = mapOf<String, String>()

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
                                val (newBytes, map) = rewriteManifestAndExtractAuthorities(bytes, oldPackageName, newPackageName)
                                authorityMap = map
                                newBytes
                            }
                            DEX_ENTRY_PATTERN.matches(entry.name) -> {
                                val (patched, count) = rewriteDexPackageStrings(
                                    entry.name, bytes, oldPackageName, newPackageName,
                                    dangerousMethods, authorityMap
                                )
                                dexStringsPatched += count
                                patched
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
                "  Rewrote zip contents: $entryCount entries, " +
                    "manifest rewritten=$manifestRewritten, icons badged=$iconsBadged, " +
                    "aligned=$entriesAligned, dex string constants patched=$dexStringsPatched" +
                    if (authorityMap.isNotEmpty()) ", ${authorityMap.size} authority mappings extracted" else ""
            )
        } catch (e: Exception) {
            Logger.log("  FAILED rewriting zip contents: ${e.message}")
            throw e
        } finally {
            rewrittenApk.delete()
        }
    }

    /**
     * Rewrites the AndroidManifest.xml binary AXML and returns a map of old authority -> new authority
     * that was applied during the rewrite. This map is used later to rewrite DEX string constants.
     */
    private fun rewriteManifestAndExtractAuthorities(
        manifestBytes: ByteArray,
        oldPkg: String,
        newPkg: String
    ): Pair<ByteArray, MutableMap<String, String>> {
        val authorityMap = mutableMapOf<String, String>()
        val reader = AxmlReader(manifestBytes)
        val writer = AxmlWriter()

        reader.accept(object : AxmlVisitor(writer) {
            override fun child(ns: String?, name: String?): NodeVisitor {
                val safeName = name ?: ""
                val superVisitor = super.child(ns, safeName)
                return CollectingRewritingNodeVisitor(superVisitor, safeName, oldPkg, newPkg, authorityMap)
            }

            override fun ns(prefix: String?, uri: String?, ln: Int) {
                val safeUri = uri ?: ""
                super.ns(prefix, safeUri, ln)
            }
        })

        Logger.log("AXML: serializing rewritten manifest (collected ${authorityMap.size} authorities)")
        return writer.toByteArray() to authorityMap
    }

    // -------------------------------------------------------------------------
    //  COLLECTING REWRITING NODE VISITOR
    // -------------------------------------------------------------------------

    private class CollectingRewritingNodeVisitor(
        parent: NodeVisitor,
        tag: String?,
        oldPkg: String,
        newPkg: String,
        private val authorityMap: MutableMap<String, String>
    ) : RewritingNodeVisitor(parent, tag, oldPkg, newPkg) {

        override fun rewriteAuthorities(value: String): String {
            val rewritten = super.rewriteAuthorities(value)
            if (rewritten != value) {
                val oldParts = value.split(",").map { it.trim() }
                val newParts = rewritten.split(",").map { it.trim() }
                for (i in oldParts.indices) {
                    if (oldParts[i] != newParts[i]) {
                        authorityMap[oldParts[i]] = newParts[i]
                    }
                }
            }
            return rewritten
        }
    }

    // -------------------------------------------------------------------------
    //  REWRITING NODE VISITOR (open for extension)
    // -------------------------------------------------------------------------

    private open class RewritingNodeVisitor(
        parent: NodeVisitor,
        private val tag: String?,
        private val oldPkg: String,
        private val newPkg: String
    ) : NodeVisitor(parent) {

        protected open fun rewriteAuthorities(value: String): String {
            return value.split(",").joinToString(",") { raw ->
                val authority = raw.trim()
                when {
                    authority.isEmpty() -> authority
                    authority.contains(oldPkg) -> authority.replace(oldPkg, newPkg)
                    else -> "$authority.$newPkg"
                }
            }
        }

        override fun attr(ns: String?, name: String?, resourceId: Int, type: Int, value: Any?) {
            var newValue = value

            val rawText: String? = when (value) {
                is String -> value
                is ValueWrapper -> value.raw
                else -> null
            }

            if (rawText != null) {
                val rewritten = when {
                    tag == "manifest" && name == "package" && rawText == oldPkg ->
                        newPkg
                    tag == "provider" && name == "authorities" ->
                        rewriteAuthorities(rawText)
                    tag in COMPONENT_TAGS && name == "process" && rawText.startsWith(oldPkg) ->
                        newPkg + rawText.removePrefix(oldPkg)
                    tag in PERMISSION_DECLARATION_TAGS && name == "name" && rawText.startsWith(oldPkg) ->
                        newPkg + rawText.removePrefix(oldPkg)
                    tag == "uses-permission" && name == "name" && rawText.startsWith(oldPkg) ->
                        newPkg + rawText.removePrefix(oldPkg)
                    name in PERMISSION_REFERENCE_ATTRS && rawText.startsWith(oldPkg) ->
                        newPkg + rawText.removePrefix(oldPkg)
                    else -> rawText
                }

                if (rewritten != rawText) {
                    if (value is ValueWrapper) {
                        Logger.log("AXML: '$name' on <$tag> is a resource reference – re‑emitting as literal string '$rewritten'")
                        super.attr(ns, name, resourceId, AxmlVisitor.TYPE_STRING, rewritten)
                        return
                    } else {
                        newValue = rewritten
                    }
                }
            }

            if (newValue is ValueWrapper && newValue.raw == null) {
                Logger.log("AXML: ValueWrapper with null raw text for attr '$name' on <$tag> – substituting empty string")
                try {
                    val rawField = ValueWrapper::class.java.getDeclaredField("raw")
                    rawField.isAccessible = true
                    rawField.set(newValue, "")
                } catch (e: Exception) {
                    Logger.log("AXML: reflection patch failed: ${e.message}")
                }
            }

            val safeName = name ?: ""
            var safeValue = newValue
            if (type == AxmlVisitor.TYPE_STRING && safeValue == null) {
                safeValue = ""
            }

            super.attr(ns, safeName, resourceId, type, safeValue)
        }

        override fun child(ns: String?, name: String?): NodeVisitor {
            val safeName = name ?: ""
            return RewritingNodeVisitor(super.child(ns, safeName), safeName, oldPkg, newPkg)
        }

        override fun text(lineNumber: Int, value: String?) {
            val safeValue = value ?: ""
            super.text(lineNumber, safeValue)
        }
    }

    // -------------------------------------------------------------------------
    //  DEX STRING REWRITER – with fixed descriptor and aggressive replacement
    // -------------------------------------------------------------------------

    private fun rewriteDexPackageStrings(
        entryName: String,
        dexBytes: ByteArray,
        oldPackageName: String,
        newPackageName: String,
        dangerousMethods: Set<String>,
        authorityMap: Map<String, String>
    ): Pair<ByteArray, Int> {
        val dexFile = try {
            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(dexBytes))
        } catch (e: Exception) {
            Logger.log("  Dex rewrite: failed to parse $entryName, leaving untouched: ${e.message}")
            return dexBytes to 0
        }

        var matchCount = 0

        val module = object : RewriterModule() {
            override fun getMethodRewriter(rewriters: Rewriters): Rewriter<org.jf.dexlib2.iface.Method> {
                val defaultMethodRewriter = super.getMethodRewriter(rewriters)
                return object : Rewriter<org.jf.dexlib2.iface.Method> {
                    override fun rewrite(method: org.jf.dexlib2.iface.Method): org.jf.dexlib2.iface.Method {
                        // Build correct descriptor for comparison
                        val params = method.parameterTypes.joinToString("")
                        val descriptor = "${method.definingClass}->${method.name}($params)${method.returnType}"
                        val aggressive = dangerousMethods.contains(descriptor)

                        val origImpl = method.implementation
                        if (origImpl == null) return defaultMethodRewriter.rewrite(method)

                        val newInstructions = origImpl.instructions.map { instruction ->
                            val ref = (instruction as? ReferenceInstruction)?.reference
                            val stringRef = ref as? StringReference
                            if (stringRef == null) return@map instruction

                            val original = stringRef.string
                            var rewritten: String? = null

                            // 1) Check authority map first (exact match)
                            if (authorityMap.containsKey(original)) {
                                rewritten = authorityMap[original]
                            }

                            // 2) If not an authority, apply package name rewriting
                            if (rewritten == null) {
                                if (aggressive) {
                                    // In dangerous methods, do a simple replace of all occurrences
                                    // (this catches concatenated or partially built strings)
                                    rewritten = original.replace(oldPackageName, newPackageName)
                                    // But avoid breaking if the replacement creates a partial token?
                                    // This is a trade-off; we assume the string is a component name or similar.
                                } else {
                                    // Safe token‑aware replacement for non‑dangerous methods
                                    rewritten = replacePackageToken(original, oldPackageName, newPackageName)
                                }
                            }

                            if (rewritten == null || rewritten == original) return@map instruction

                            matchCount++
                            val newRef = ImmutableStringReference(rewritten)
                            when (instruction) {
                                is Instruction21c -> ImmutableInstruction21c(
                                    instruction.opcode, instruction.registerA, newRef
                                )
                                is Instruction31c -> ImmutableInstruction31c(
                                    instruction.opcode, instruction.registerA, newRef
                                )
                                else -> instruction
                            }
                        }

                        val newImpl = org.jf.dexlib2.immutable.ImmutableMethodImplementation(
                            origImpl.registerCount,
                            newInstructions,
                            origImpl.tryBlocks,
                            origImpl.debugItems
                        )
                        return org.jf.dexlib2.immutable.ImmutableMethod(
                            method.definingClass,
                            method.name,
                            method.parameters,
                            method.returnType,
                            method.accessFlags,
                            method.annotations,
                            method.hiddenApiRestrictions,
                            newImpl
                        )
                    }
                }
            }
        }

        val dexRewriter = DexRewriter(module)
        val rewrittenDexFile = module.getDexFileRewriter(dexRewriter).rewrite(dexFile)
        if (matchCount == 0) {
            return dexBytes to 0
        }

        val tempDex = File.createTempFile("dex_rewrite", ".dex", context.cacheDir)
        return try {
            DexPool.writeTo(tempDex.absolutePath, rewrittenDexFile)
            val patchedBytes = tempDex.readBytes()
            Logger.log(
                "  Dex string rewrite: $entryName — patched $matchCount occurrence(s) " +
                    "(package + authority replacements) (${dexBytes.size} -> ${patchedBytes.size} bytes)"
            )
            patchedBytes to matchCount
        } catch (e: Exception) {
            Logger.log("  Dex rewrite: failed to re‑encode $entryName after patching, using original: ${e.message}")
            dexBytes to 0
        } finally {
            tempDex.delete()
        }
    }

    // -------------------------------------------------------------------------
    //  HELPER: replacePackageToken (unchanged)
    // -------------------------------------------------------------------------

    private fun replacePackageToken(value: String, oldPkg: String, newPkg: String): String? {
        fun isIdentifierPart(ch: Char) = ch.isLetterOrDigit() || ch == '_' || ch == '$'

        fun tryReplace(token: String): String? {
            var index = value.indexOf(token)
            while (index >= 0) {
                val before = if (index > 0) value[index - 1] else null
                val afterIndex = index + token.length
                val after = if (afterIndex < value.length) value[afterIndex] else null

                val beforeOk = before == null || !isIdentifierPart(before) || before == 'L' || before == '['
                val afterOk = after == null || !isIdentifierPart(after)

                if (beforeOk && afterOk) {
                    return value.substring(0, index) + newPkg + value.substring(afterIndex)
                }
                index = value.indexOf(token, index + 1)
            }
            return null
        }

        tryReplace(oldPkg)?.let { return it }
        val slashedOld = oldPkg.replace('.', '/')
        val slashedNew = newPkg.replace('.', '/')
        return tryReplace(slashedOld)?.replace(slashedOld, slashedNew)
    }

    // -------------------------------------------------------------------------
    //  REST OF THE CLASS (unchanged: alignment, badging, signing, install)
    // -------------------------------------------------------------------------

    private fun alignmentFor(entryName: String): Int {
        val lower = entryName.lowercase()
        return when {
            lower.startsWith("lib/") && lower.endsWith(".so") -> 16384
            lower == "resources.arsc" -> 4
            else -> 1
        }
    }

    private fun alignmentExtraField(dataOffsetSansExtra: Long, alignment: Int): ByteArray {
        val remainder = (dataOffsetSansExtra % alignment).toInt()
        if (remainder == 0) return ByteArray(0)
        var padding = alignment - remainder
        while (padding < 6) padding += alignment
        val dataLen = padding - 4
        val out = ByteArray(padding)
        out[0] = 0x35
        out[1] = 0xd9.toByte()
        out[2] = (dataLen and 0xFF).toByte()
        out[3] = ((dataLen shr 8) and 0xFF).toByte()
        out[4] = (alignment and 0xFF).toByte()
        out[5] = ((alignment shr 8) and 0xFF).toByte()
        return out
    }

    private class CountingOutputStream(private val out: java.io.OutputStream) : java.io.OutputStream() {
        var count: Long = 0
            private set
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
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

            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF6D00") }
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
            Logger.log("Icon badge failed for $entryName: ${e.message} — using original")
            bytes
        }
    }

    private fun signApkBinary(inputFile: File, outputFile: File) {
        Logger.log("  Signing ${inputFile.name} -> ${outputFile.name} (v1/v2/v3)")
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
            Logger.log("  SIGNING FAILED for ${inputFile.name}: ${e.message}")
            throw e
        }
    }

    // -------------------------------------------------------------------------
    //  INSTALLATION SESSION (unchanged)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    //  COMPANION – constants & preferences
    // -------------------------------------------------------------------------

    companion object {
        private val COMPONENT_TAGS = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
        private val PERMISSION_DECLARATION_TAGS = setOf("permission", "permission-group", "permission-tree")
        private val PERMISSION_REFERENCE_ATTRS = setOf("permission", "readPermission", "writePermission")

        private val TOGGLE_METHOD_NAMES = setOf(
            "setComponentEnabledSetting",
            "setApplicationEnabledSetting"
        )

        private const val PREFS_NAME = "app_cloner_prefs"
        private const val PREF_KEY_INSTALLER_PACKAGE = "preferred_installer_package"
        private const val LOCAL_HEADER_FIXED_SIZE = 30L
        private val DEX_ENTRY_PATTERN = Regex("^classes\\d*\\.dex$")

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
