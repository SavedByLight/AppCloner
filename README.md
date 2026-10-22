# SavedByLight App Cloner

Android app cloning utility with two distinct modes:

1. **ROM clone profile** — installs the original package into a separate Android user/profile, keeping the same package ID and signing certificate. This gives the app separate Android-managed data and is the closest match to the native cloned-app feature in custom ROMs.
2. **Standalone APK clone** — creates another package such as `com.example.app.clone1`, rewrites the binary manifest/provider authorities, patches some self-package string constants, signs the rewritten APK set, and installs it as a separate package. It can work without root by handing the staged APK set to Android's `PackageInstaller` for user confirmation.

## ROM clone profiles

This mode needs **root access** and a ROM that actually implements Android's clone profile user type (`android.os.usertype.profile.CLONE`). Android's framework defines clone profiles, but not every Android build delivers the complete user-facing feature. Stock devices that lack ROM/framework support will reject profile creation; the app reports that instead of pretending a profile was created.

- Select an app, then choose **ROM clone profile**.
- Pick an existing clone profile or create one. The manager can also create and remove profiles from the toolbar menu.
- The app runs `pm install-existing --user <profile-id> <package>` to make the original app available in that profile, then attempts to launch it there.
- Removing a profile erases that profile's apps and data. The UI asks for confirmation first; the primary user (`0`) is never removed by this app.

A ROM's launcher/SystemUI still controls how profile icons, notifications, sharing, and settings are displayed. This app can't add missing system-level integration to a ROM that doesn't implement it.

## Standalone APK clones

The app:

1. Reads the installed app's base APK and any split APKs.
2. Rewrites the binary `AndroidManifest.xml`, package name, provider authorities and selected package references.
3. Removes stale signature metadata, rewrites some hardcoded self-package string constants in DEX, and adds a small numeric clone badge to supported raster launcher icons.
4. Re-aligns stored ZIP entries (including native libraries) and signs the rewritten APK set with a persistent local signing identity.
5. Installs through root when available, or submits a `PackageInstaller` session for Android's normal installer/confirmation flow.

Choose one of five standalone instances (`.clone1` through `.clone5`). Re-cloning an installed instance reuses this app's signing key and can update that package in place.

## Limitations

Standalone APK cloning is **not equivalent to an OS clone profile**. Repackaging changes the package name and signing certificate. Apps that verify their certificate, rely on Play Integrity, use hardcoded package names in native code, use signature-level permissions, depend on their own application ID in resources, or perform anti-tamper checks may fail. Google apps, banking apps, some games, and deeply integrated system apps are especially likely to need native ROM-profile mode instead. A badge is only applied to supported PNG/WebP launcher assets; vector/adaptive icon assets may retain their original appearance.

Root installs bypass the user-facing package installer only when `su` actually grants root. Without root, Android controls confirmation and policy; the app cannot silently install a package. ROM clone profile creation likewise fails clearly if the required user type or ROM integration is missing.

## Build

Requires JDK 17, Android Gradle Plugin 8.4.1, and Android SDK platform 34 or compatible newer tooling. From the project root:

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

The uploaded source does not include the Gradle wrapper JAR. Open the project in Android Studio or generate the wrapper as above. The debug APK is suitable for sideloading. The release build expects `app/appcloner-release.jks` plus `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` environment variables.

## Logging

Use **View Logs** from the toolbar menu for a timestamped record of APK rewriting, signing, root commands, profile creation/install actions, and PackageInstaller results. Logs are stored in the app's private files directory.
