<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/dd1c4695-b61b-4890-a8b7-302bb5dde529

## Download & Install

Latest prebuilt APK: **[`apk/MobilePlay-2.0.0-m2m3-arm64-debug.apk`](apk/MobilePlay-2.0.0-m2m3-arm64-debug.apk)** (~33 MB)

- `versionName` `2.0.0-m2m3` (`versionCode` 2)
- `arm64-v8a` native build (includes `libchiaki_jni.so`), Android 7.0+ (API 24)
- Debug-signed: install with `adb install -r apk/MobilePlay-2.0.0-m2m3-arm64-debug.apk`, or copy the file to the device and tap it (allow installs from unknown sources)

SHA-256: `2336481ACFA0B270F27293DAE6B5F61986FA90921F3B50297E054D32F8F12C50`

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.
