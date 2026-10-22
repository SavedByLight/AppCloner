# Changelog

## 1.9.05 — ROM clone profile support

- Added a root-backed ROM clone profile manager for Android builds that support clone profiles.
- Added profile creation, selection, app installation into a profile, launch attempts, and guarded profile removal.
- Kept standalone APK cloning as a fallback, with up to five numbered clone package names.
- Improved package/manifest rewriting, signing metadata cleanup, DEX self-package string patching, ZIP alignment, and install logging.
- Allowed non-root installs to use Android's PackageInstaller confirmation flow rather than requiring root for every install.
- Updated the app list and documentation to explain the limits of APK repackaging and ROM-specific clone support.

## Verification note

The source archive passed ZIP integrity checks. An Android APK build and on-device testing were not completed in the packaging environment; test on a rooted ROM device before relying on ROM clone profile features.
