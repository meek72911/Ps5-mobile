# MobilePlay for iPad

MobilePlay streams from a locally paired PS5 to an iPad running **iPadOS 17 or later**. It uses a SwiftUI/UIKit interface, the native Chiaki engine, VideoToolbox decoding, and Metal rendering. It supports a scaled touch overlay and compatible physical controllers. Open a landscape window to play. The Android custom layout editor and internet relay are outside the scope of this build.

## Install from Windows

1. Open a successful [iPadOS build](https://github.com/meek72911/Ps5-mobile/actions/workflows/ios-build.yml) on `feature/ipados-native` and download **MobilePlay-unsigned-ipa**.
2. Extract the IPA and follow the [AltStore Classic Windows guide](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows). Use your own Apple Account to sign the IPA with the sideloading tool. Trust the developer account on the iPad and enable Developer Mode when prompted.
3. Put the IPA in **Files** on the iPad and open **AltStore → My Apps → +** to install. With free signing, refresh the app every seven days. [AltStore refresh instructions](https://faq.altstore.io/altstore-classic/your-altstore)

The workflow builds an arm64 device app, verifies the executable architecture when packaging, and runs tests on an iPad simulator. Pairing, audio, video, and input on physical hardware need a separate device test.

## Pair your PS5

1. Put the iPad and PS5 on the same network. On PS5, enable **Settings → System → Remote Play**.
2. In MobilePlay, add your PS5 through local discovery or enter its IP address.
3. Use **Sign in with PlayStation** in the app or enter your numeric PSN account ID or its eight-byte Base64 representation. Use the same PlayStation account as on your PS5. In the separate PIN field, enter the eight-digit code shown by **Link Device** on the PS5.
4. Save the console and connect. Pair a physical controller in iPad Bluetooth settings if you want to use one.

## Build locally on a Mac

Install Xcode and select it as the active developer directory. From the repository root:

```sh
brew install cmake ninja xcodegen
git submodule update --init --recursive
cd ios
./scripts/build-chiaki.sh
cp Config/Local.xcconfig.example Config/Local.xcconfig
xcodegen generate
open MobilePlay.xcodeproj
```

Set your team ID and bundle ID in `Config/Local.xcconfig` for a signed device build. For the unsigned packaging and simulator test commands, see the [iPadOS workflow](../.github/workflows/ios-build.yml).

[MobilePlay overview and Android instructions](../README.md) · [iPad provenance and license notices](NOTICE.md)
