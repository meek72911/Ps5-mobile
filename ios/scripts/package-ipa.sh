#!/usr/bin/env bash
# Builds an unsigned build/MobilePlay.ipa for sideloading. SideStore, AltStore and
# Sideloadly re-sign it with the installer's own Apple ID.
#
#   scripts/package-ipa.sh
#   VERSION=0.2.0 BUILD_NUMBER=7 scripts/package-ipa.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${VERSION:-0.0.0}"
BUILD_NUMBER="${BUILD_NUMBER:-1}"
BUNDLE_ID="${BUNDLE_ID:-com.mobileplay.ipad}"
DERIVED="$ROOT/build/DerivedData-ipa"
STAGE="$ROOT/build/ipa"
OUT="$ROOT/build/MobilePlay.ipa"

[ -d Frameworks/ChiakiKit.xcframework ] || scripts/build-chiaki.sh
xcodegen generate

xcodebuild -project MobilePlay.xcodeproj -scheme MobilePlay -configuration Release \
	-destination 'generic/platform=iOS' -derivedDataPath "$DERIVED" \
	CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" DEVELOPMENT_TEAM="" \
	MOBILEPLAY_BUNDLE_ID="$BUNDLE_ID" MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
	build

APP="$DERIVED/Build/Products/Release-iphoneos/MobilePlay.app"
PLIST="$APP/Info.plist"
EXECUTABLE=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$PLIST")
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$PLIST")" = "$BUNDLE_ID"
test "$(/usr/libexec/PlistBuddy -c 'Print :UIDeviceFamily:0' "$PLIST")" = "2"
test "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleSupportedPlatforms:0' "$PLIST")" = "iPhoneOS"
xcrun lipo "$APP/$EXECUTABLE" -verify_arch arm64

mkdir -p "$STAGE"
TEMP="$(mktemp -d "$STAGE/package.XXXXXX")"
trap 'rm -rf -- "$TEMP"' EXIT
mkdir -p "$TEMP/Payload"
cp -R "$APP" "$TEMP/Payload/"
(cd "$TEMP" && zip -qry MobilePlay.ipa Payload)
unzip -tq "$TEMP/MobilePlay.ipa"
mv -f "$TEMP/MobilePlay.ipa" "$OUT"
echo "Built $OUT"
