# Dual App Cloner (Anti-Detection Edition)

The most aggressive on-device Android app cloner. Manifest rewriting + runtime hook injection + DEX string patching.

## What This Does

| Layer | Technique | What It Blocks |
|-------|-----------|---------------|
| **Manifest** | Package rename, authority rewrite, permission rewrite | Install collisions, provider conflicts |
| **Manifest** | sharedUserId strip, meta-data scrub | Signature mismatch on install |
| **DEX** | String search-and-replace | `getPackageSignatures`, `getInstallerPackageName`, root paths, hash algos |
| **Native** | Frida gadget injection | Signature hash comparison, Build props, File.exists(su), Runtime.exec |
| **UI** | Play Integrity pre-scan | Warns before cloning apps known to use server-side attestation |

## Building

```bash
./gradlew assembleDebug
```

## Usage

1. **Open the app** — it lists all installed user apps.
2. **Enable toggles** (optional but recommended for stubborn apps):
   - **Inject Frida**: Downloads ~20 MB gadget libs, hooks APIs at runtime.
   - **Patch DEX**: Performs string replacement in DEX bytecode to break client-side checks.
3. **Tap an app** — if it's high-risk (Claude, Grok, ChatGPT, Snapchat), you'll get a warning.
4. **Clone anyway** — the APK is rewritten, signed, and optionally patched.
5. **Install** — use the system prompt or SAI if blocked.

## The Hard Truth About "Making Every App Work"

### CLIENT-SIDE checks ✅ CAN be bypassed
These happen inside the APK on your device. Our patches handle them:
- Signature hash comparison → **DEX patch** breaks the method name / **Frida** returns empty signature
- Root detection (`File.exists("/sbin/su")`) → **Frida** returns false
- Emulator detection (`Build.FINGERPRINT`) → **Frida** spoofs Pixel values
- Installer source check → **Frida** returns `com.android.vending`

### SERVER-SIDE checks ❌ CANNOT be bypassed without root
These happen on Google's servers and the app's backend:
- **Play Integrity API**: The app asks Google's TEE (Trusted Execution Environment) to sign an attestation token. The token includes the APK signature hash, package name, and device integrity. Google's servers verify it. **No hook, patch, or manifest tweak can forge a hardware-backed cryptographic signature.**
- **Firebase App Check**: The app's API keys and OAuth client IDs are registered to the original package+signature in Google Cloud Console. A re-signed clone with a different package name will always fail authentication.

### What this means for specific apps

| App | Client-side | Server-side | Clone Result |
|-----|------------|-------------|--------------|
| Simple games, utilities | ✅ Patched | ❌ None | **Works** |
| Old social apps (no integrity) | ✅ Patched | ❌ None | **Works** |
| Banking apps | ✅ Patched | ⚠️ Some | **May open, sign-in fails** |
| ChatGPT / Claude / Grok | ✅ Patched | ✅ Play Integrity + Firebase | **Opens, then "Something went wrong"** |
| Snapchat | ✅ Patched | ✅ Play Integrity + behavioral | **SS03 / login blocked** |
| Netflix / Spotify | ✅ Patched | ✅ Widevine L1 + Play Integrity | **Streams blocked** |

### To truly bypass server-side checks, you need:

1. **Root + Magisk + Play Integrity Fix (PIF)** — spoofs the device fingerprint passed to Google's attestation
2. **LSPosed + Core Patch** — makes the system report the original signature instead of the clone's
3. **Custom ROM with signature spoofing** — system-level patch
4. **Pre-patched/modded APK** — someone already did the DEX smali work (risky, often malware)
5. **Second physical device** — the only 100% reliable method

## Manual Install Fallback

If PackageInstaller is blocked:
```bash
adb install-multiple Download/AppCloner/<package>/*.apk
```

## License

MIT — Use at your own risk. Cloning apps may violate their Terms of Service.
