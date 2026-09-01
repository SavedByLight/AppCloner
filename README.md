# Dual App Cloner (AppClonerClassic)

Native Android app that creates **independent second instances** of any user-installed app — the same technique used by Parallel Space, Dual Apps, and Island. Ideal for devices that lack a built-in dual-app / App Twin feature.

## What’s new in 1.1.0 Dual

- **Two-tab UI**: “Clone New” and “My Clones”
- **Clone manager**: list every existing clone, open it, or uninstall it with one tap
- **Search** across both lists
- **Clone counts** shown next to apps that already have dual instances
- **Numbered icon badges** (shows 2, 3, … instead of a generic “C”)
- Clearer confirm dialogs and status feedback
- Robust PackageInstaller session + automatic export to Downloads for manual install when OEMs block the system installer

## How it works

There is no official “run app twice” API on stock Android. The reliable approach is:

1. Copy the target app’s APK(s) (base + any splits).
2. Rewrite the binary `AndroidManifest.xml` (package name, provider authorities, process, taskAffinity, custom permissions) so the clone is a completely different app to the system.
3. Badge the launcher icon with the clone number.
4. Re-sign with a fresh key (required after any manifest change).
5. Install via PackageInstaller session (or fall back to the exported APKs in Downloads).

Each clone gets its own `/data/data/<package>/` storage, so accounts, preferences and databases are fully isolated.

## Building

```bash
cd AppCloner-classic
# First time only if gradlew is missing:
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug
```

Requirements: JDK 17, Android SDK with compileSdk 34, AGP 8.4+.

Or simply open the folder in Android Studio and let it sync & build.

## Real limitations

- **Anti-tamper / signature-pinned apps** (many banking apps, some games, Snapchat, etc.) detect the changed certificate or package and may refuse to run or show temporary blocks. This is expected and not fixable at the manifest level.
- **Google / Facebook login** usually fails on clones because those providers bind to the original package name + signing certificate. Prefer phone / email login when available.
- Component class names (`android:name`) are deliberately **not** rewritten — changing them breaks ClassLoader resolution.
- Google Play policy disallows publishing apps that clone other apps; this tool is intended for personal sideloading only.

## Usage tips

1. Grant “Install unknown apps” permission when prompted.
2. After cloning, confirm the system install dialog.
3. If the install is blocked by Samsung Auto Blocker / Play Protect / Knox, open the exported folder under `Downloads/AppCloner/<package>/` and install with SAI or `adb install-multiple`.
4. Use the **My Clones** tab to manage or remove dual instances later.

## License / Credits

Open-source educational / personal-use project. Uses:
- `pxb.android.axml` for binary manifest rewriting
- Google `apksig` for proper v1/v2/v3 signing
