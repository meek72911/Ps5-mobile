# MobilePlay for iPad (branch `feature/ipados-native`)

Native Swift / SwiftUI / UIKit / Metal / VideoToolbox PS5 Remote Play client built on the Chiaki core.
Starts from iPlay5 (see `NOTICE.md`). The Android app in the repo root is untouched.

## Status — read this first
Nothing in this folder has been compiled or run by the author of this commit: it was prepared without Xcode.
- The macOS CI workflow (`.github/workflows/ios-build.yml`) is the first real compile. Check its result.
- Not yet verified: native Chiaki build for iOS, pairing, video/audio on a real iPad, any real PS5 session.
- iPad-specific changes (control scaling, foldable detection off on iPad, orientations, Split View behaviour)
  are untested on a device. Portrait still pauses the stream, as in the original app.
- No custom layout editor yet (Milestone D/E in the brief).

## Getting an installable file without a Mac
1. Push the branch; run the **iPadOS build** workflow (Actions tab).
2. Download the `MobilePlay-unsigned-ipa` artifact.
3. Sideload it from Windows with Sideloadly (or AltStore/SideStore) using your own Apple ID.
   Free Apple IDs expire every 7 days. Apple does not allow installing an unsigned IPA directly.

## Local build (Mac)
    brew install cmake ninja xcodegen
    git submodule update --init --recursive
    cd ios && scripts/build-chiaki.sh
    cp Config/Local.xcconfig.example Config/Local.xcconfig   # set your team ID + bundle ID
    xcodegen generate && open MobilePlay.xcodeproj

On the PS5: Settings › System › Remote Play › Enable Remote Play. Local network only; PSN internet play is not included.
