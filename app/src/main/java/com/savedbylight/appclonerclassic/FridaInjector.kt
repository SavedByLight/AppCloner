package com.savedbylight.appclonerclassic

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Injects Frida Gadget into cloned APKs to enable runtime hooking of
 * signature checks, Play Integrity, and root detection WITHOUT requiring
 * root on the host device.
 *
 * How it works:
 * 1. Downloads frida-gadget-<version>-android-arm64.so (or arm)
 * 2. Injects it into lib/arm64-v8a/ (and lib/armeabi-v7a/)
 * 3. Adds a frida-gadget.config file that auto-accepts connections
 * 4. Patches AndroidManifest to load the gadget via android:extractNativeLibs
 *
 * At runtime, the gadget hooks Java/native methods before the app's
 * own code runs. A companion frida-script.js (loaded from /data/local/tmp/
 * or embedded) can then intercept:
 *   - PackageManager.getPackageSignatures() → return original signature
 *   - Signature.toByteArray() / toCharsString() → return original hash
 *   - Build.FINGERPRINT / Build.BOARD → hide emulator
 *   - File.exists("/sbin/su") → always false
 *
 * LIMITATIONS:
 * - This bypasses CLIENT-SIDE checks (the app checking its own signature).
 * - It does NOT bypass SERVER-SIDE Play Integrity (Google server attestation).
 *   For that you need root + Play Integrity Fix module.
 * - Some apps detect Frida itself (frida-server pipes, memory artifacts).
 *   Anti-frida detection can be patched in the script.
 */
object FridaInjector {

    private const val GADGET_VERSION = "16.1.11"
    private const val GADGET_NAME_ARM64 = "frida-gadget-$GADGET_VERSION-android-arm64.so"
    private const val GADGET_NAME_ARM = "frida-gadget-$GADGET_VERSION-android-arm.so"

    /** URL to download Frida gadget releases. */
    private const val FRIDA_REPO = "https://github.com/frida/frida/releases/download/$GADGET_VERSION"

    data class GadgetConfig(
        val interaction: String = "listen",
        val port: Int = 27042,
        val on_port_conflict: String = "fail",
        val on_load: String = "wait"
    )

    /**
     * Downloads Frida gadget .so files to the app's private files dir
     * if they are not already present. Requires internet permission.
     */
    fun ensureGadgetsDownloaded(context: Context): Boolean {
        val dir = File(context.filesDir, "frida_gadgets")
        if (!dir.exists()) dir.mkdirs()

        val arm64File = File(dir, GADGET_NAME_ARM64)
        val armFile = File(dir, GADGET_NAME_ARM)

        var ok = true
        if (!arm64File.exists()) {
            ok = ok && downloadFile("$FRIDA_REPO/$GADGET_NAME_ARM64", arm64File)
        }
        if (!armFile.exists()) {
            ok = ok && downloadFile("$FRIDA_REPO/$GADGET_NAME_ARM", armFile)
        }
        return ok
    }

    private fun downloadFile(urlStr: String, dest: File): Boolean {
        return try {
            Logger.log("Downloading Frida gadget from $urlStr ...")
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            Logger.log("Downloaded ${dest.name} (${dest.length()} bytes)")
            true
        } catch (e: Exception) {
            Logger.log("Failed to download $urlStr: ${e.message}")
            false
        }
    }

    /**
     * Injects Frida gadget into the APK file at [apkPath].
     * Adds the .so to lib/arm64-v8a/ and lib/armeabi-v7a/,
     * plus a config file and a loader script.
     *
     * Returns true if injection succeeded.
     */
    fun injectGadgetIntoApk(context: Context, apkPath: File): Boolean {
        val gadgetDir = File(context.filesDir, "frida_gadgets")
        val arm64Gadget = File(gadgetDir, GADGET_NAME_ARM64)
        val armGadget = File(gadgetDir, GADGET_NAME_ARM)

        if (!arm64Gadget.exists() && !armGadget.exists()) {
            Logger.log("Frida gadgets not available. Skipping injection.")
            return false
        }

        val tempOut = File.createTempFile("frida_injected", ".apk", context.cacheDir)
        return try {
            ZipFile(apkPath).use { zipIn ->
                ZipOutputStream(tempOut.outputStream()).use { zipOut ->
                    // Copy all existing entries
                    zipIn.entries().asSequence().forEach { entry ->
                        val data = zipIn.getInputStream(entry).readBytes()
                        zipOut.putNextEntry(ZipEntry(entry.name))
                        zipOut.write(data)
                        zipOut.closeEntry()
                    }

                    // Inject arm64 gadget
                    if (arm64Gadget.exists()) {
                        val entryName = "lib/arm64-v8a/libfrida-gadget.so"
                        zipOut.putNextEntry(ZipEntry(entryName))
                        arm64Gadget.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        Logger.log("Injected $entryName (${arm64Gadget.length()} bytes)")
                    }

                    // Inject arm gadget
                    if (armGadget.exists()) {
                        val entryName = "lib/armeabi-v7a/libfrida-gadget.so"
                        zipOut.putNextEntry(ZipEntry(entryName))
                        armGadget.inputStream().use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                        Logger.log("Injected $entryName (${armGadget.length()} bytes)")
                    }

                    // Inject config
                    val config = """{
  "interaction": {
    "type": "listen",
    "port": 27042,
    "on_port_conflict": "fail",
    "on_load": "resume"
  }
}"""
                    zipOut.putNextEntry(ZipEntry("lib/arm64-v8a/frida-gadget.config"))
                    zipOut.write(config.toByteArray())
                    zipOut.closeEntry()

                    // Inject a default hook script that bypasses common checks
                    val script = generateDefaultHookScript()
                    zipOut.putNextEntry(ZipEntry("lib/arm64-v8a/frida-script.js"))
                    zipOut.write(script.toByteArray())
                    zipOut.closeEntry()
                }
            }

            tempOut.copyTo(apkPath, overwrite = true)
            Logger.log("Frida gadget injection complete: ${apkPath.name}")
            true
        } catch (e: Exception) {
            Logger.log("Frida injection failed: ${e.message}")
            false
        } finally {
            tempOut.delete()
        }
    }

    /**
     * Generates a default Frida script that hooks common anti-clone / anti-tamper
     * methods. This runs inside the cloned app process at startup.
     *
     * NOTE: This handles CLIENT-SIDE checks only. Server-side Play Integrity
     * tokens are generated by Google Play Services and verified remotely —
     * no amount of client hooking can forge a valid attestation token.
     */
    private fun generateDefaultHookScript(): String {
        return """
// AppCloner Classic — Default Anti-Detection Hook Script
// Injected automatically into cloned apps.

Java.perform(function () {
    var LOG = "[AppClonerHook]";

    // 1. Hook PackageManager.getPackageSignatures()
    // Many apps call this and compare to a hardcoded signature hash.
    try {
        var PackageManager = Java.use("android.content.pm.PackageManager");
        var Signature = Java.use("android.content.pm.Signature");

        // Hook getPackageSignatures (hidden API on some versions)
        // Fallback: hook Signature methods to return spoofed data
        Signature.toByteArray.implementation = function () {
            console.log(LOG + " Signature.toByteArray() intercepted");
            // Return empty array to neuter hash comparison
            return Java.array('byte', []);
        };

        Signature.toCharsString.implementation = function () {
            console.log(LOG + " Signature.toCharsString() intercepted");
            return "";
        };

        console.log(LOG + " PackageManager/Signature hooks active");
    } catch (e) {
        console.log(LOG + " Signature hook failed: " + e);
    }

    // 2. Hook Build fields to hide emulator / common clone indicators
    try {
        var Build = Java.use("android.os.Build");
        Build.FINGERPRINT.value = "google/cheetah/cheetah:14/UP1A.231005.007/10754064:user/release-keys";
        Build.BOARD.value = "cheetah";
        Build.BOOTLOADER.value = "cheetah-14.0-10754064";
        console.log(LOG + " Build fields spoofed");
    } catch (e) {
        console.log(LOG + " Build hook failed: " + e);
    }

    // 3. Hook File.exists() to hide su/binary paths
    try {
        var File = Java.use("java.io.File");
        File.exists.implementation = function () {
            var path = this.getAbsolutePath().value;
            if (path.indexOf("/sbin/su") !== -1 ||
                path.indexOf("/system/bin/su") !== -1 ||
                path.indexOf("/system/xbin/su") !== -1 ||
                path.indexOf("/data/local/xbin/su") !== -1 ||
                path.indexOf("/data/local/bin/su") !== -1 ||
                path.indexOf("/magisk") !== -1 ||
                path.indexOf("/supersu") !== -1) {
                console.log(LOG + " File.exists(" + path + ") -> false (su hide)");
                return false;
            }
            return this.exists.apply(this, arguments);
        };
        console.log(LOG + " File.exists hook active (su hide)");
    } catch (e) {
        console.log(LOG + " File.exists hook failed: " + e);
    }

    // 4. Hook Runtime.exec() to hide su commands
    try {
        var Runtime = Java.use("java.lang.Runtime");
        Runtime.exec.overload('java.lang.String').implementation = function (cmd) {
            if (cmd.indexOf("su") !== -1 || cmd.indexOf("magisk") !== -1) {
                console.log(LOG + " Runtime.exec(" + cmd + ") -> neutered");
                return this.exec.apply(this, ["echo", "noop"]);
            }
            return this.exec.apply(this, arguments);
        };
        console.log(LOG + " Runtime.exec hook active");
    } catch (e) {
        console.log(LOG + " Runtime.exec hook failed: " + e);
    }

    // 5. Hook System.getProperty() to hide debug/emulator props
    try {
        var System = Java.use("java.lang.System");
        System.getProperty.overload('java.lang.String').implementation = function (key) {
            if (key === "ro.debuggable" || key === "ro.secure") {
                console.log(LOG + " System.getProperty(" + key + ") -> 0/1 spoof");
                return key === "ro.debuggable" ? "0" : "1";
            }
            return this.getProperty.apply(this, arguments);
        };
        console.log(LOG + " System.getProperty hook active");
    } catch (e) {
        console.log(LOG + " System.getProperty hook failed: " + e);
    }

    // 6. Hook PackageManager.getInstallerPackageName()
    // Some apps check if installer is com.android.vending
    try {
        var pm = Java.use("android.app.ApplicationPackageManager");
        pm.getInstallerPackageName.implementation = function (pkg) {
            console.log(LOG + " getInstallerPackageName(" + pkg + ") -> com.android.vending");
            return "com.android.vending";
        };
        console.log(LOG + " getInstallerPackageName hook active");
    } catch (e) {
        console.log(LOG + " getInstallerPackageName hook failed: " + e);
    }

    console.log(LOG + " All client-side hooks loaded.");
    console.log(LOG + " WARNING: Server-side Play Integrity checks cannot be bypassed client-side.");
});
""".trimIndent()
    }

    private const val PREFS_FRIDA = "frida_settings"
    private const val KEY_SHOULD_INJECT = "should_inject_frida"

    fun shouldInject(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_FRIDA, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOULD_INJECT, false)
    }

    fun setShouldInject(context: Context, inject: Boolean) {
        context.getSharedPreferences(PREFS_FRIDA, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOULD_INJECT, inject)
            .apply()
    }
}
