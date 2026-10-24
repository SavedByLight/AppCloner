package com.savedbylight.appclonerclassic

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Scans an installed app for known Play Integrity, Firebase App Check,
 * SafetyNet, and heavy Google-ecosystem dependencies that make the app
 * extremely likely to fail when cloned.
 *
 * These apps bind their APK signature + package name to Google Cloud
 * console / Firebase projects. A re-signed clone with a different
 * package name will ALWAYS fail attestation / OAuth / API-key checks.
 */
object PlayIntegrityDetector {

    /** Packages that are known to perform strong integrity checks. */
    private val HIGH_RISK_PACKAGES = setOf(
        "com.anthropic.claude",       // Claude
        "com.xai.grok",               // Grok (if/when released)
        "com.openai.chatgpt",         // ChatGPT
        "com.snapchat.android",       // Snapchat
        "com.google.android.gms",       // Play services
        "com.google.android.gsf",       // Google Services Framework
        "com.android.vending",          // Play Store
        "com.instagram.android",      // Instagram (some flows)
        "com.whatsapp",               // WhatsApp (registration)
        "com.facebook.katana",        // Facebook
        "com.facebook.orca",          // Messenger
        "com.zhiliaoapp.musically",   // TikTok
        "com.google.android.apps.photos",
        "com.google.android.apps.maps",
        "com.google.android.youtube",
        "com.netflix.mediaclient",
        "com.spotify.music",
        "com.discord",
        "com.bank",
        "com.paypal.android.p2pmobile"
    )

    /** Manifest meta-data names that indicate Play Integrity / Firebase binding. */
    private val RISK_METADATA_KEYS = listOf(
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

    /** Permission patterns that signal deep Play Services integration. */
    private val RISK_PERMISSION_PATTERNS = listOf(
        "com.google.android.c2dm.permission.RECEIVE",
        "com.google.android.gms.permission.AD_ID",
        "com.google.android.providers.gsf.permission.READ_GSERVICES"
    )

    data class RiskReport(
        val level: RiskLevel,
        val reasons: List<String>
    )

    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    fun analyze(pm: PackageManager, appInfo: ApplicationInfo): RiskReport {
        val reasons = mutableListOf<String>()
        var level = RiskLevel.LOW
        val pkg = appInfo.packageName

        // 1. Known high-risk package names
        if (HIGH_RISK_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }) {
            level = RiskLevel.CRITICAL
            reasons.add("Known high-risk app: strong signature/integrity checks")
        }

        // 2. Check for banking / financial keywords
        if (pkg.contains("bank") || pkg.contains("wallet") || pkg.contains("pay") ||
            pkg.contains("finance") || pkg.contains("crypto")) {
            if (level < RiskLevel.HIGH) level = RiskLevel.HIGH
            reasons.add("Financial app: likely uses device attestation / root detection")
        }

        // 3. Parse manifest meta-data via PackageManager
        try {
            val metaData = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData
            metaData?.let { bundle ->
                for (key in RISK_METADATA_KEYS) {
                    if (bundle.containsKey(key)) {
                        if (level < RiskLevel.HIGH) level = RiskLevel.HIGH
                        reasons.add("Bound to Google service: $key")
                    }
                }
            }
        } catch (_: Exception) { }

        // 4. Check permissions
        try {
            val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            pi.requestedPermissions?.forEach { perm ->
                if (RISK_PERMISSION_PATTERNS.any { perm.contains(it) }) {
                    if (level < RiskLevel.MEDIUM) level = RiskLevel.MEDIUM
                    reasons.add("Play Services permission: $perm")
                }
            }
        } catch (_: Exception) { }

        // 5. Check for split APKs with Play-specific config
        val splitDirs = appInfo.splitSourceDirs
        if (!splitDirs.isNullOrEmpty()) {
            val configSplits = splitDirs.filter { it.contains("config.") }
            if (configSplits.isNotEmpty()) {
                // Not a risk by itself, but worth noting
                reasons.add("${splitDirs.size} split APK(s) — multi-split clones need SAI/adb")
            }
        }

        return RiskReport(level, reasons)
    }

    fun getWarningMessage(report: RiskReport): String? {
        return when (report.level) {
            RiskLevel.LOW -> null
            RiskLevel.MEDIUM ->
                "Medium risk: ${report.reasons.joinToString("; ")}.\n\n" +
                    "The clone may work, but some features (notifications, sign-in) might fail."
            RiskLevel.HIGH ->
                "HIGH RISK: ${report.reasons.joinToString("; ")}.\n\n" +
                    "This app likely uses Play Integrity or Firebase App Check. " +
                    "The clone will probably crash or refuse to sign in because the " +
                    "APK signature and package name no longer match the Google Cloud project.\n\n" +
                    "TIP: Enable \"Inject Frida\" toggle and download gadgets. This patches " +
                    "CLIENT-SIDE checks (signature comparison, root detection) but CANNOT " +
                    "bypass server-side Google attestation."
            RiskLevel.CRITICAL ->
                "CRITICAL: ${report.reasons.joinToString("; ")}.\n\n" +
                    "This app is known to block cloned/re-signed copies server-side. " +
                    "The clone will almost certainly fail with a \"Something went wrong\" or " +
                    "\"Google Play\" error.\n\n" +
                    "Frida injection will patch CLIENT-SIDE checks but CANNOT forge " +
                    "server-side Play Integrity tokens. For these apps you need:\n" +
                    "1) A second physical device, OR\n" +
                    "2) Root + Magisk + Play Integrity Fix + LSPosed, OR\n" +
                    "3) Use the web version instead."
        }
    }
}
