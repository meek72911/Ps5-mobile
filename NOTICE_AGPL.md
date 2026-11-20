# MobilePlay — Open-Source Licensing & Milestone 2 Integration Notes

## License & Upstream Attribution (GNU AGPL-3.0)

The complete, unmodified GNU Affero General Public License v3.0 text is provided in `/COPYING`.

- **MobilePlay Application**: Native Android Kotlin multi-touch DualSense input engine (`com.example.mobileplay.engine`), custom Android `TouchOverlayView`, visual `LayoutEditorScreen`, `ProfileManager`, `KeystoreCredentialVault`, `AspectRatioSurfaceView` / `VideoRenderer`, `AudioRenderer`, `LocalPs5Discovery`, `SonyAuthManager`, and `mobileplay_chiaki_jni.cpp`.
- **Upstream Remote Play Foundations**:
  - **chiaki-ng / libchiaki** (`https://github.com/chiaki-ng/chiaki-ng`) — pinned to release tag **`v1.10.1`** (commit `7ea0b0c`), licensed under GNU AGPL-3.0.
  - **Momiji** (`https://github.com/yazanj082/momiji`) — Android Remote Play reference architecture, licensed under GNU AGPL-3.0.
- **Native Transitive Dependencies (built for `arm64-v8a` in CI)**:
  - **OpenSSL** (`openssl-3.3.2`, Apache-2.0)
  - **libopus** (`v1.5.2`, BSD-3-Clause)
  - **nanopb** & **jerasure / gf-complete** (via pinned `chiaki-ng` submodules)
- **Redistribution Obligation**: Any distributed APK/AAB linked with `libchiaki` must preserve `/COPYING`, all upstream copyright headers, and make complete corresponding source code available under GNU AGPL-3.0.
- **Trademarks**: PlayStation, PS5, PSN, and DualSense are registered trademarks of Sony Interactive Entertainment Inc. MobilePlay is an independent open-source project and is not affiliated with Sony.

## Native Compilation Strategy (`arm64-v8a`)

Because the AI Studio cloud container does not install Android NDK, CMake, Ninja, or Clang in `$ANDROID_SDK_ROOT`, native `.so` compilation is verified via the reproducible GitHub Actions workflow `.github/workflows/android-ndk-build.yml`:

1. Checks out pinned `chiaki-ng` tag `v1.10.1` (`7ea0b0c`) with `--recurse-submodules`.
2. Cross-compiles `OpenSSL` and `libopus` static libraries for Android `arm64-v8a` with 16 KB page alignment (`-Wl,-z,max-page-size=16384`).
3. Runs `./gradlew :app:testDebugUnitTest`, `./gradlew :app:lintDebug`, and `./gradlew :app:assembleDebug -PmobileplayEnableNativeChiaki=true`.
4. Unzips the generated APK and fails the workflow if `lib/arm64-v8a/libchiaki_jni.so` is absent or if any ELF `LOAD` segment is not 16 KB aligned.
