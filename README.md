# MobilePlay

MobilePlay is an unofficial, open-source **PS5 Remote Play client for Android and iPad**. It streams video and audio from your own PS5 and sends back input from touch controls or a compatible gamepad. Both apps use the native Chiaki Remote Play engine.

## Download

| Platform | Requirements | Build |
| --- | --- | --- |
| Android | Android 7.0+ on an ARM64 device | [Android build workflow](https://github.com/meek72911/Ps5-mobile/actions/workflows/android-ndk-build.yml) · artifact **MobilePlay-Android-arm64-debug** |
| iPad | iPadOS 17 or later | [iPadOS build workflow](https://github.com/meek72911/Ps5-mobile/actions/workflows/ios-build.yml) · artifact **MobilePlay-unsigned-ipa** |

Open a successful workflow run, sign in to GitHub if prompted, and download and extract the artifact. GitHub Actions artifacts can expire. The APK in [apk/](https://github.com/meek72911/Ps5-mobile/tree/main/apk) is the historical Android 2.0 build.

- **Android** — Open the APK on your phone, or install it with `adb install -r app-debug.apk`. Updates require the same signing key. If Android reports a signing mismatch, uninstalling the previous build erases its saved consoles and settings.
- **iPad** — The IPA is unsigned. Use [AltStore Classic](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows) or another compatible sideloading tool to sign and install it with your Apple Account. A Windows PC can do this. With free AltStore signing, refresh the app every seven days. [iPad build and install guide](https://github.com/meek72911/Ps5-mobile/blob/feature/ipados-native/ios/README.md)

## Connect your PS5

1. On PS5, open **Settings → System → Remote Play** and enable Remote Play.
2. Connect your mobile device and PS5 to the same home network. Use **Consoles** in MobilePlay to find the PS5, or enter its local IP address. Wired Ethernet for the PS5 is useful when troubleshooting an unstable stream.
3. Choose **Link Device** on the PS5 to get an eight-digit PIN. In MobilePlay, enter the PIN and the **PSN account ID** for the same account you use on the console. The ID is separate from your PSN username and the pairing PIN. Use PlayStation sign-in in the app or enter the numeric ID or its eight-byte Base64 form manually. The iPad build needs commit [abbfa62](https://github.com/meek72911/Ps5-mobile/commit/abbfa62) or later for numeric entry.
4. Save the pairing, select the console, and start the stream. Use landscape for gameplay. For a physical controller, pair it in your device’s Bluetooth settings before opening the stream.

## Features and limits

| Area | Android | iPad |
| --- | --- | --- |
| Stream | Native Chiaki, MediaCodec video, AudioTrack audio. Adjustable resolution, frame rate, codec and bitrate. | Native Chiaki, VideoToolbox video decoding and Metal rendering. Adjustable stream settings. |
| Touch controls | Touch presets including standard controller, on-foot and driving layouts. Layout editor, sensitivity and response-curve tuning, and gyro camera options. | Scaled touch overlay and physical controller support. The Android-style custom layout editor is not included. |
| Physical controller | Compatible Android gamepads, including DualSense. Option to hide the touch layout when a controller connects. | Compatible gamepads through Apple's Game Controller framework. |
| Platform | Kotlin, Jetpack Compose and C++/JNI. Native builds target `arm64-v8a`. | SwiftUI and UIKit. iPad target only. The stream needs a landscape window. |

**Scope:** MobilePlay streams games running on a PS5 you can pair with. It has no console emulation, cloud game streaming, browser client, or supported PSN relay for playing over the internet. PSN sign-in is used for account identification during pairing. Pairing and gameplay depend on the actual console, device, and network. CI builds and simulator tests do not establish that a physical PS5 session works.

If video is black or stutters, verify the console and mobile device are on the same network and try **720p, H.264, and a lower bitrate** as a baseline. When reporting a problem, include the build or commit, device and OS, PS5 model or firmware if known, network setup, stream settings, and steps to reproduce. Keep pairing PINs, account IDs, and sign-in redirect URLs out of reports. [Report an issue](https://github.com/meek72911/Ps5-mobile/issues)

## Build from source

### Android on Windows

Use PowerShell 7, Git for Windows, Python 3 with pip, and JDK 21 (the CI version). In Android Studio's SDK Manager, install Android SDK Platform **36.1**, Build Tools **36.0.0**, NDK **27.2.12479018**, CMake **3.22.1**, and Platform Tools if you use ADB.

```powershell
.\scripts\build-android-windows.ps1
```

Provide `-AndroidSdk`, `-JavaHome`, or `-Python` if your tools are in custom locations. The script fetches the pinned native sources, assembles `app/build/outputs/apk/debug/app-debug.apk`, runs JVM and Robolectric tests and Android Lint, and verifies APK signing, 16 KB alignment, and the required ARM64 libraries.

### iPad

The native iPad source is on [`feature/ipados-native`](https://github.com/meek72911/Ps5-mobile/tree/feature/ipados-native). Local compilation uses macOS and Xcode. Windows users can download and sideload the CI artifact. [Build and install instructions](https://github.com/meek72911/Ps5-mobile/blob/feature/ipados-native/ios/README.md)

## Credits and license

MobilePlay project by **Sarthak Khedkar**. The iPad app derives from [iPlay5](https://github.com/reefbarman/iPlay5), and both builds use Chiaki. See the [GNU AGPL-3.0 license](https://github.com/meek72911/Ps5-mobile/blob/main/COPYING), [Android attribution](https://github.com/meek72911/Ps5-mobile/blob/main/NOTICE_AGPL.md), and [iPad attribution](https://github.com/meek72911/Ps5-mobile/blob/feature/ipados-native/ios/NOTICE.md) for upstream credits and dependency licenses.

PlayStation, PS5, PSN, and DualSense are Sony Interactive Entertainment trademarks. MobilePlay is an independent project.
