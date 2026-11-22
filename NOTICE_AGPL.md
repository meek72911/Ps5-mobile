# MobilePlay Open-Source Notices

## License & Upstream Attribution (GNU AGPL-3.0)

Developed by Sarthak Khedkar. Copyright © 2026 Sarthak Khedkar for original MobilePlay contributions. Upstream components retain their copyright and license terms.

The GNU Affero General Public License v3.0 is provided in `COPYING`. The Android APK includes the full Chiaki license with its OpenSSL linking permission and dependency licenses under `assets/about/`.

- **MobilePlay Application**: Native Android Kotlin multi-touch DualSense input engine (`com.example.mobileplay.engine`), custom Android `TouchOverlayView`, visual `LayoutEditorScreen`, `ProfileManager`, `KeystoreCredentialVault`, `AspectRatioSurfaceView` / `VideoRenderer`, `AudioRenderer`, `LocalPs5Discovery`, `SonyAuthManager`, and `mobileplay_chiaki_jni.cpp`.
- **Upstream Remote Play Foundations**:
  - **chiaki-ng / libchiaki** (`https://github.com/chiaki-ng/chiaki-ng`) — pinned to release tag **`v1.10.1`** (commit `f30da0a`), licensed under GNU AGPL-3.0.
  - **Momiji** (`https://github.com/yazanj082/momiji`) — Android Remote Play reference architecture, licensed under GNU AGPL-3.0.
- **Native Transitive Dependencies (built for `arm64-v8a`)**:
  - **OpenSSL** (`openssl-3.5.9`, Apache-2.0)
  - **libopus** (`v1.5.2`, BSD-3-Clause)
  - **nanopb** & **jerasure / gf-complete** (via pinned `chiaki-ng` submodules)
- **Redistribution Obligation**: Any distributed APK/AAB linked with `libchiaki` must preserve `/COPYING`, all upstream copyright headers, and make complete corresponding source code available under GNU AGPL-3.0.
- **Trademarks**: PlayStation, PS5, PSN, and DualSense are registered trademarks of Sony Interactive Entertainment Inc. MobilePlay is an independent open-source project and is not affiliated with Sony.

## Reproducing the Android APK

Use `scripts/build-android-windows.ps1` and the prerequisites in `README.md`. The script pins native dependencies, applies the repository's Chiaki patches and builds the ARM64 APK. Distribute the exact corresponding source for the released binary, including the native dependency sources, the applied patches and the scripts used to build it.
