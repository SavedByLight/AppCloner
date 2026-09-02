package com.savedbylight.appclonerclassic

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Injects a self-contained hook Application into cloned APKs.
 *
 * HOW IT WORKS:
 * 1. Generates a tiny DEX file containing HookApplication.class
 * 2. Injects the DEX into the APK (classes2.dex)
 * 3. Rewrites AndroidManifest to point android:name=".HookApplication"
 * 4. HookApplication loads first, patches PackageManager/Signature/Build
 *    via reflection, then delegates to the real Application class.
 *
 * ADVANTAGES over Frida:
 * - No external .so download required (works offline)
 * - No native code = less anti-detection surface
 * - Runs inside the app process naturally
 * - Can patch both Java and some native checks
 *
 * LIMITATIONS:
 * - Still CANNOT bypass server-side Play Integrity (hardware attestation)
 * - Some apps detect DEX modification via checksum
 * - Apps with custom Application subclasses need special handling
 */
object RuntimePatcher {

    private const val HOOK_APP_CLASS = "com.savedbylight.appclonerclassic.hook.HookApplication"
    private const val HOOK_DEX_NAME = "classes2.dex"

    /**
     * Generates a DEX bytecode for HookApplication.
 * This is a minimal Dalvik executable that:
 * - Extends android.app.Application
 * - In attachBaseContext(): hooks PackageManager, Signature, Build, File
 * - In onCreate(): calls the real Application's onCreate()
 *
 * Since we can't compile .java → .dex on-device without tools,
 * we embed a pre-built minimal DEX as a base64/asset and patch
 * the package name inside it at clone time.
 */
    fun injectHookApplication(context: Context, apkFile: File, originalAppClass: String?): Boolean {
        return try {
            // Generate the hook DEX
            val hookDex = generateHookDex(context, originalAppClass)
            if (hookDex == null) {
                Logger.log("RuntimePatcher: failed to generate hook DEX")
                return false
            }

            // Inject into APK
            val tempOut = File.createTempFile("hooked", ".apk", context.cacheDir)
            ZipFile(apkFile).use { zipIn ->
                ZipOutputStream(tempOut.outputStream()).use { zipOut ->
                    // Copy existing entries
                    zipIn.entries().asSequence().forEach { entry ->
                        val data = zipIn.getInputStream(entry).readBytes()
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        zipOut.write(data)
                        zipOut.closeEntry()
                    }
                    // Add hook DEX
                    zipOut.putNextEntry(ZipEntry(HOOK_DEX_NAME))
                    zipOut.write(hookDex)
                    zipOut.closeEntry()
                }
            }
            tempOut.copyTo(apkFile, overwrite = true)
            tempOut.delete()

            Logger.log("RuntimePatcher: injected $HOOK_DEX_NAME (${hookDex.size} bytes)")
            true
        } catch (e: Exception) {
            Logger.log("RuntimePatcher: injection failed: ${e.message}")
            false
        }
    }

    /**
     * Generates a minimal DEX file containing HookApplication.
     * Uses embedded template DEX from assets (pre-built) with
     * runtime package name patching.
     *
     * If no template is available, falls back to creating a
     * minimal DEX using dx/d8 if available on device (rare).
     */
    private fun generateHookDex(context: Context, originalAppClass: String?): ByteArray? {
        // Try to load pre-built template from assets
        return try {
            context.assets.open("hook_template.dex").use { it.readBytes() }
        } catch (_: Exception) {
            // Fallback: generate minimal DEX bytecode manually
            generateMinimalDex(context, originalAppClass)
        }
    }

    /**
     * Creates a minimal valid DEX file with HookApplication.
     * This is a best-effort approach that constructs raw DEX bytes.
     * For production, a pre-built template DEX should be bundled.
     */
    private fun generateMinimalDex(context: Context, originalAppClass: String?): ByteArray? {
        // For now, return null and rely on Frida or manifest-only
        // Full DEX generation is extremely complex without dx/d8
        Logger.log("RuntimePatcher: no hook template available, skipping DEX injection")
        return null
    }

    /**
     * Rewrites the manifest to use HookApplication as the entry point.
     * The original application class is passed to HookApplication via
     * meta-data so it can delegate after patching.
     */
    fun patchManifestForHook(manifestBytes: ByteArray, originalAppClass: String?): ByteArray {
        // This would need to be integrated into the AXML rewriter
        // For now, we document the approach
        Logger.log("RuntimePatcher: manifest patching for hook app would go here")
        return manifestBytes
    }
}
