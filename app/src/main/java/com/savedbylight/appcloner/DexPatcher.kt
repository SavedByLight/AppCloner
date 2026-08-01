package com.savedbylight.appcloner

import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.Rewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.writer.pool.DexPool
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Patches literal String constants embedded in dex bytecode that hold the app's OWN
 * (pre-clone) package name — the class of bug behind crashes like:
 *
 *   java.lang.SecurityException: Attempt to change component state;
 *       component=ComponentInfo{com.github.android/...DeepLinkActivity}
 *
 * Some apps hardcode their own applicationId as a string literal (e.g. to build a
 * ComponentName for PackageManager#setComponentEnabledSetting) instead of deriving it
 * from Context#getPackageName(). After cloning, the running process's real package name
 * no longer matches that literal, so PackageManagerService's "caller must own the target
 * package" check fails and the app crashes in Application#onCreate() before any UI shows.
 *
 * This version rewrites the **entire string pool**, replacing:
 * - any string that exactly equals oldPkg
 * - any string that starts with oldPkg + "/" (flattened component)
 *
 * This is broader than the previous const‑string‑only approach and catches all
 * occurrences without touching class name descriptors (which are stored separately).
 */
object DexPatcher {

    fun patchSelfPackageStringConstants(
        dexBytes: ByteArray,
        oldPkg: String,
        newPkg: String,
        tempDir: File
    ): ByteArray? {
        val opcodes = Opcodes.getDefault()
        val dexFile: DexFile = DexBackedDexFile.fromInputStream(opcodes, ByteArrayInputStream(dexBytes))

        var matchCount = 0

        val rewriter = DexRewriter(object : RewriterModule() {
            override fun getStringRewriter(): Rewriter<String> {
                return object : Rewriter<String> {
                    override fun rewrite(value: String): String {
                        val newValue = when {
                            value == oldPkg -> {
                                matchCount++
                                Logger.log("DexPatcher: replaced string pool entry \"$value\" -> \"$newPkg\"")
                                newPkg
                            }
                            value.startsWith("$oldPkg/") -> {
                                matchCount++
                                val replacement = newPkg + value.substring(oldPkg.length)
                                Logger.log("DexPatcher: replaced string pool entry \"$value\" -> \"$replacement\"")
                                replacement
                            }
                            else -> value
                        }
                        return newValue
                    }
                }
            }
        })

        val rewrittenDexFile = rewriter.dexFileRewriter.rewrite(dexFile)
        if (matchCount == 0) {
            Logger.log("DexPatcher: no matching hardcoded self-package string constants found in dex")
            return null
        }

        Logger.log("DexPatcher: patched $matchCount hardcoded self-package string constant(s) in string pool")

        val tempOut = File.createTempFile("patched_dex", ".dex", tempDir)
        return try {
            DexPool.writeTo(tempOut.absolutePath, rewrittenDexFile)
            tempOut.readBytes()
        } finally {
            tempOut.delete()
        }
    }

    fun isDexEntry(entryName: String): Boolean =
        Regex("""classes\d*\.dex""").matches(entryName)
}
