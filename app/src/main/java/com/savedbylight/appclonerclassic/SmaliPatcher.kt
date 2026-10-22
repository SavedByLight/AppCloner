package com.savedbylight.appclonerclassic

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Patches DEX bytecode at the string level to neuter common anti-clone checks.
 *
 * This is a binary search-and-replace pass over classes.dex. It looks for
 * known string constants used in signature/root/integrity checks and replaces
 * them with harmless alternatives.
 *
 * WARNING: This is a BLUNT INSTRUMENT. It may break functionality.
 * Only enable for apps that are already failing with "Something went wrong".
 *
 * What it patches:
 * - "getPackageSignatures" → "getPackageSignaturesX" (breaks reflection lookup)
 * - "getInstallerPackageName" → "getInstallerPackageNameX"
 * - "android.intent.action.MAIN" signatures (some apps check launcher intent)
 * - "su" binary paths in string pools
 * - "ro.debuggable" / "ro.secure" system property names
 * - "SHA1" / "SHA256" (breaks signature hash computation)
 * - "Play Integrity" / "SafetyNet" (breaks API name resolution)
 */
object SmaliPatcher {

    /** Patches that neuter client-side checks. Each pair is (search, replace).
     *  Replacements must be SAME LENGTH to avoid breaking DEX offsets. */
    private val PATCHES = listOf(
        // Same-length only — unequal pairs break DEX string-pool offsets.
        "/system/bin/su" to "/system/bin/sx",
        "/system/xbin/su" to "/system/xbin/sx",
        "/sbin/su" to "/sbin/sx",
        "/su/bin/su" to "/su/bin/sx",
        "getPackageSignatures" to "getPackageSignature_",
        "getInstallerPackageName" to "getInstallerPackageNam_",
        "ro.debuggable" to "ro.debuggablx",
        "SHA1withRSA" to "SHA1withRSX",
        "SHA256withRSA" to "SHA256withRSX",
        "SafetyNetApi" to "SafetyNetApx",
        "play_integrity" to "play_integritx",
        "integrity_token" to "integrity_tokeX",
    )
        "SafetyNetApi"         to "SafetyNetApiX",
        "AttestationApi"       to "AttestationApiX",
        // Break Firebase App Check
        "app_check"            to "app_checkX",
        "appcheck"             to "appcheckX",
        // Break common certificate pinning strings
        "X509TrustManager"     to "X509TrustManagerX",
        "checkServerTrusted"   to "checkServerTrustedX"
    )

    /**
     * Performs binary string replacement on all .dex files inside the APK.
     * The APK is a zip, so we extract → patch → re-zip.
     */
    fun patchDexStrings(context: Context, apkFile: File): Int {
        var totalPatches = 0
        val workDir = File(context.cacheDir, "dex_patch_${apkFile.nameWithoutExtension}").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }

        try {
            // Extract DEX files
            val dexFiles = extractDexFiles(apkFile, workDir)
            Logger.log("SmaliPatcher: found ${dexFiles.size} DEX file(s)")

            dexFiles.forEach { dex ->
                val count = patchSingleDex(dex)
                totalPatches += count
                Logger.log("SmaliPatcher: patched $count strings in ${dex.name}")
            }

            if (totalPatches > 0) {
                repackDexFiles(apkFile, dexFiles)
                Logger.log("SmaliPatcher: repacked APK with $totalPatches patches")
            }
        } finally {
            workDir.deleteRecursively()
        }

        return totalPatches
    }

    private fun extractDexFiles(apkFile: File, workDir: File): List<File> {
        val dexFiles = mutableListOf<File>()
        java.util.zip.ZipFile(apkFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.name.endsWith(".dex")) {
                    val out = File(workDir, entry.name)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    dexFiles.add(out)
                }
            }
        }
        return dexFiles
    }

    private fun patchSingleDex(dexFile: File): Int {
        var count = 0
        val bytes = dexFile.readBytes()
        val patched = bytes.copyOf()

        PATCHES.forEach { (search, replace) ->
            if (search.length != replace.length) {
                Logger.log("SmaliPatcher: SKIP '$search' → '$replace' (length mismatch)")
                return@forEach
            }
            val searchBytes = search.toByteArray(StandardCharsets.UTF_8)
            val replaceBytes = replace.toByteArray(StandardCharsets.UTF_8)

            var idx = 0
            while (idx <= patched.size - searchBytes.size) {
                var match = true
                for (i in searchBytes.indices) {
                    if (patched[idx + i] != searchBytes[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    for (i in replaceBytes.indices) {
                        patched[idx + i] = replaceBytes[i]
                    }
                    count++
                    idx += searchBytes.size
                } else {
                    idx++
                }
            }
        }

        if (count > 0) {
            dexFile.writeBytes(patched)
        }
        return count
    }

    private const val PREFS_SMALI = "smali_settings"
    private const val KEY_SHOULD_PATCH = "should_patch_dex"

    fun shouldPatch(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_SMALI, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOULD_PATCH, false)
    }

    fun setShouldPatch(context: Context, patch: Boolean) {
        context.getSharedPreferences(PREFS_SMALI, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOULD_PATCH, patch)
            .apply()
    }

    private fun repackDexFiles(apkFile: File, dexFiles: List<File>) {
        val tempOut = File.createTempFile("dex_patched", ".apk", apkFile.parentFile)
        java.util.zip.ZipFile(apkFile).use { zipIn ->
            java.util.zip.ZipOutputStream(tempOut.outputStream()).use { zipOut ->
                val dexNames = dexFiles.map { it.name }.toSet()
                zipIn.entries().asSequence().forEach { entry ->
                    if (dexNames.contains(entry.name)) return@forEach
                    val bytes = zipIn.getInputStream(entry).use { it.readBytes() }
                    val outEntry = java.util.zip.ZipEntry(entry.name)
                    val store = entry.name == "resources.arsc" ||
                        entry.method == java.util.zip.ZipEntry.STORED
                    if (store) {
                        outEntry.method = java.util.zip.ZipEntry.STORED
                        outEntry.size = bytes.size.toLong()
                        outEntry.compressedSize = bytes.size.toLong()
                        outEntry.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    } else {
                        outEntry.method = java.util.zip.ZipEntry.DEFLATED
                    }
                    zipOut.putNextEntry(outEntry)
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                }
                dexFiles.forEach { dex ->
                    val bytes = dex.readBytes()
                    val outEntry = java.util.zip.ZipEntry(dex.name)
                    outEntry.method = java.util.zip.ZipEntry.DEFLATED
                    zipOut.putNextEntry(outEntry)
                    zipOut.write(bytes)
                    zipOut.closeEntry()
                }
            }
        }
        tempOut.copyTo(apkFile, overwrite = true)
        tempOut.delete()
    }
}
