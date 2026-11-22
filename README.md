# MobilePlay

MobilePlay is an ARM64 Android PS5 Remote Play client. Every APK build includes the native Chiaki engine.

## Build on Windows

Install Git for Windows and Python 3 with pip. Use JDK 17 or newer supported by Gradle 9.3.1, such as the JDK bundled with Android Studio. Install Android SDK Platform 36.1, Build Tools 36.0.0, Android NDK 27.2.12479018, and CMake 3.22.1 through Android Studio's SDK Manager. Install Platform Tools for ADB.

Run the Windows build script from the repository root in PowerShell 7.

```powershell
.\scripts\build-android-windows.ps1
```

The script defaults to Android Studio's standard SDK and JDK locations. Supply `-AndroidSdk`, `-JavaHome`, or `-Python` if those tools are installed elsewhere. It downloads pinned sources and host tools to `third_party`, uses Git Bash with the Windows NDK for OpenSSL 3.5.9 LTS, and uses CMake and Ninja for Opus and Chiaki. Chiaki is pinned to commit `f30da0aaeb96fc0026cc03683153f682352e7d7c`, the commit referenced by the upstream v1.10.1 release tag.

The script assembles `app/build/outputs/apk/debug/app-debug.apk`, runs the JVM and Robolectric tests, runs Android Lint, and verifies APK signing, ZIP alignment, and the required ARM64 libraries. An existing project `debug.keystore` is used for AI Studio debug signing when available. Otherwise Gradle uses its normal Android debug key.

[Original AI Studio project](https://ai.studio/apps/dd1c4695-b61b-4890-a8b7-302bb5dde529)

## GitHub Actions

The Android workflow runs the Windows build script on JDK 21. Download the verified
debug APK from the MobilePlay-Android-arm64-debug workflow artifact.

The APK under apk/ is the historical MobilePlay 2.0 build.
