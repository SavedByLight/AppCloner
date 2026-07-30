# App Cloner (Android)

Native Android app that clones an already-installed app so you can run it as
a second, independent instance (e.g. two WhatsApp accounts) — the same
technique used by Play Store apps like "App Cloner" and "Parallel Space".

## How it actually works

There's no OS-level "run app twice" API on stock Android, so the real trick
is: **make a second app that Android thinks is a completely different app.**

1. Pull the target app's APK from `ApplicationInfo.sourceDir`.
2. Open `AndroidManifest.xml` inside the APK — it's stored in a compact
   *binary* format (AXML), not plain text — and rewrite the `package`
   attribute plus any `<provider android:authorities="...">` values so they
   don't collide with the original app. Done via the `pxb.android:axml`
   library, so no full apktool decompile/recompile round trip is needed.
3. Re-zip the APK with the new manifest, dropping the old `META-INF/`
   signing files.
4. Sign the result with a freshly generated key using Google's own
   `apksig` library (the same one `apksigner` on the command line uses).
   This is required — the original signature is invalid once the manifest
   bytes changed, and Android refuses to install anything unsigned or
   tampered.
5. Hand the signed APK to the system installer via a `FileProvider` +
   `ACTION_VIEW` intent, same as installing any APK.

## Building it

```
cd AppCloner
./gradlew assembleDebug
```

(You'll need to run `gradle wrapper` once first if there's no `gradlew` in
the zip — I generated this outside Android Studio so the wrapper jar isn't
included. Easiest path: open the folder in Android Studio and let it sync,
or run `gradle wrapper --gradle-version 8.7` before the build.)

Requires `compileSdk 34` / AGP 8.4.1 / JDK 17, same toolchain you're already
using for TWRP/LineageOS Soong builds.

## Real limitations — worth knowing before you rely on this

- **Anti-tamper / signature-pinned apps will detect this.** Banking apps,
  many games, and anything checking `BuildConfig.APPLICATION_ID`,
  SafetyNet/Play Integrity, or its own signing cert at runtime will refuse
  to run or will misbehave. This isn't fixable at the manifest level — it'd
  require patching the actual DEX/native code, which is a different
  (and much murkier) project.
- **Shared prefs / SQL storage collisions are avoided for free** — since the
  cloned app gets a distinct package name, Android gives it its own
  `/data/data/<pkg>/` automatically. No extra work needed there.
- **`resources.arsc` isn't touched.** Fine for the vast majority of apps,
  but an app that reads its own package name out of resources rather than
  `Context.getPackageName()` won't fully cooperate.
- **APK Signature Scheme v2/v3 + this rewrite means you must re-sign.**
  There's no way around that; it's inherent to what's being done here.
- Google Play policy explicitly disallows apps that clone/repackage other
  apps' listings on the Play Store — this is fine for sideloading on your
  own device, not something to publish as-is.

## CI / Releases

`.github/workflows/build.yml` handles this for
`github.com/SavedByLight/<repo>` on the `main` branch:

- **Every push/PR to `main`** — builds a debug APK and uploads it as a
  workflow artifact (`Actions` tab → run → `AppCloner-debug-apk`).
- **Pushing a tag like `v0.1.0`** — builds the APK again and creates a
  GitHub Release on that tag with the APK attached, using
  `softprops/action-gh-release`. Release notes are auto-generated from
  commits since the last tag.

It uses `gradle/actions/setup-gradle` and `android-actions/setup-android`
so no Gradle wrapper needs to be committed — the workflow provisions its
own Gradle + Android SDK. To cut a release:

```
git tag v0.1.0
git push origin v0.1.0
```

Note it currently builds and ships the **debug**-signed APK, since that's
zero-config (AGP auto-generates a debug keystore) and fine for sideloading
on your own device. If you want a proper release-signed APK instead,
that needs a keystore + `signingConfigs.release` block in `app/build.gradle`
plus the keystore/passwords stored as repo secrets — happy to add that if
you want a stable signing identity across releases (recommended if you'll
be upgrading the clone in place rather than reinstalling each time).

## Where I'd take it next

- Batch mode: clone N instances (`.clone1`, `.clone2`, ...) instead of one.
- A settings toggle per clone to also rewrite the launcher icon/label so
  clones are visually distinguishable in the app drawer.
- Optional resources.arsc string-pool rewrite for apps that read their own
  package name back out of resources.
