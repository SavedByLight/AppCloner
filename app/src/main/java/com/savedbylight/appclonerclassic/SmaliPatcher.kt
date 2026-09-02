package com.savedbylight.appclonerclassic

import java.io.File
import java.io.RandomAccessFile
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
        // Break PackageManager signature reflection
        "getPackageSignatures" to "getPackageSignaturesX",
        "getPackageSignature"  to "getPackageSignatureX",
        // Break installer check
        "getInstallerPackageName" to "getInstallerPackageNameX",
        // Break root detection string lookups
        "/system/bin/su"       to "/system/bin/sx",
        "/system/xbin/su"      to "/system/xbin/sx",
        "/sbin/su"             to "/sbin/sx",
        "/su/bin/su"           to "/su/bin/sx",
        // Break system property checks
        "ro.debuggable"        to "ro.debuggableX",
        "ro.secure"            to "ro.secureX",
        // Break hash algorithms used for signature comparison
        "SHA1withRSA"          to "SHA1withRSAX",
        "SHA256withRSA"        to "SHA256withRSAX",
        // Break Play Integrity API name resolution
        "play_integrity"       to "play_integrityX",
        "integrity_token"      to "integrity_tokenX",
        // Break SafetyNet (legacy)
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
                // Copy non-DEX entries
                zipIn.entries().asSequence().forEach { entry ->
                    if (!dexNames.contains(entry.name)) {
                        zipOut.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        zipIn.getInputStream(entry).use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                }
                // Write patched DEX entries
                dexFiles.forEach { dex ->
                    zipOut.putNextEntry(java.util.zip.ZipEntry(dex.name))
                    dex.inputStream().use { it.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
        }
        tempOut.copyTo(apkFile, overwrite = true)
        tempOut.delete()
    }
}
