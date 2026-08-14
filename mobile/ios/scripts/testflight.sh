#!/usr/bin/env bash
# Archive FrameCut, export a signed IPA, and upload it to TestFlight.
#
# One-time setup before this works:
#   1. App Store Connect → Users and Access → Integrations → App Store Connect
#      API. Copy the Issuer ID (a UUID) shown at the top of that page.
#   2. Make sure an app record exists for the bundle id below. If it does not,
#      create it once at App Store Connect → Apps → "+" → New App.
#   3. Put your team id in mobile/ios/FrameCut.xcconfig (DEVELOPMENT_TEAM).
#
# Then:
#   export ASC_ISSUER_ID=<the uuid from step 1>
#   export ASC_KEY_ID=74ZBV87T64          # or whichever key you want to use
#   ./mobile/ios/scripts/testflight.sh
#
# The private key is read from ~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8,
# which is where Xcode and altool already look — nothing is copied or printed.

set -euo pipefail

cd "$(dirname "$0")/.."

BUNDLE_ID="io.github.nipunbatra.framecut"
BUILD_DIR="${TMPDIR:-/tmp}/framecut-ios-build"
ARCHIVE="$BUILD_DIR/FrameCut.xcarchive"
EXPORT_DIR="$BUILD_DIR/export"

: "${ASC_ISSUER_ID:?Set ASC_ISSUER_ID — see the header of this script}"
: "${ASC_KEY_ID:?Set ASC_KEY_ID — see the header of this script}"

TEAM_ID="$(grep -E '^DEVELOPMENT_TEAM' FrameCut.xcconfig | sed 's/.*=[[:space:]]*//')"
if [ -z "$TEAM_ID" ]; then
  echo "DEVELOPMENT_TEAM is empty in FrameCut.xcconfig — add your Apple team id first." >&2
  exit 1
fi

if grep -q PASTE_CLIENT_ID_SUFFIX_HERE FrameCut.xcconfig; then
  echo "GOOGLE_CLIENT_ID_SUFFIX is still a placeholder in FrameCut.xcconfig." >&2
  echo "The app would ship unable to sign in. Fill it in first." >&2
  exit 1
fi

# A TestFlight build number must be higher than every build already uploaded,
# so derive one that always increases.
BUILD_NUMBER="$(date +%Y%m%d%H%M)"

# Archiving is the slow part. If a previous run already produced one and the
# sources have not changed since, reuse it — that turns a retry (say, after
# creating the App Store Connect record) into a few seconds instead of minutes.
# Pass --clean to force a fresh archive.
REUSE_ARCHIVE=false
if [ "${1:-}" != "--clean" ] && [ -d "$ARCHIVE" ]; then
  if [ -z "$(find FrameCut FrameCut.xcconfig FrameCut-Info.plist -newer "$ARCHIVE" 2>/dev/null | head -1)" ]; then
    REUSE_ARCHIVE=true
  fi
fi

if [ "$REUSE_ARCHIVE" = true ]; then
  # The build number is baked in when the archive is made, so a reused archive
  # keeps its original one. Report that rather than the number computed above,
  # which would be a lie.
  BUILD_NUMBER="$(/usr/libexec/PlistBuddy -c 'Print :ApplicationProperties:CFBundleVersion' \
    "$ARCHIVE/Info.plist" 2>/dev/null || echo "$BUILD_NUMBER")"
  echo "==> Reusing the existing archive (nothing changed since it was built)"
  echo "    Build number $BUILD_NUMBER. Pass --clean to rebuild it from scratch."
else
  rm -rf "$BUILD_DIR"
  mkdir -p "$BUILD_DIR"
  echo "==> Archiving (build $BUILD_NUMBER)"
  xcodebuild archive \
    -project FrameCut.xcodeproj \
    -scheme FrameCut \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "$ARCHIVE" \
    -allowProvisioningUpdates \
    -authenticationKeyPath "$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8" \
    -authenticationKeyID "$ASC_KEY_ID" \
    -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
    CURRENT_PROJECT_VERSION="$BUILD_NUMBER" \
    DEVELOPMENT_TEAM="$TEAM_ID"
fi

mkdir -p "$BUILD_DIR"
cat > "$BUILD_DIR/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>app-store-connect</string>
	<key>teamID</key>
	<string>$TEAM_ID</string>
	<key>uploadSymbols</key>
	<true/>
	<key>destination</key>
	<string>upload</string>
</dict>
</plist>
PLIST

echo "==> Exporting and uploading to TestFlight"
set +e
xcodebuild -exportArchive \
  -archivePath "$ARCHIVE" \
  -exportPath "$EXPORT_DIR" \
  -exportOptionsPlist "$BUILD_DIR/ExportOptions.plist" \
  -allowProvisioningUpdates \
  -authenticationKeyPath "$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8" \
  -authenticationKeyID "$ASC_KEY_ID" \
  -authenticationKeyIssuerID "$ASC_ISSUER_ID" 2>&1 | tee "$BUILD_DIR/export.log"
EXPORT_STATUS=${PIPESTATUS[0]}
set -e

# Apple reports "no app record yet" as an opaque "Error Downloading App
# Information", so translate it into the one action that actually fixes it.
if [ "$EXPORT_STATUS" -ne 0 ]; then
  LOGS="$(ls -dt "${TMPDIR:-/tmp}"/FrameCut_*.xcdistributionlogs 2>/dev/null | head -1)"
  if grep -qs "missingApp" "$LOGS"/*.log 2>/dev/null ||
     grep -qs "Error Downloading App Information" "$BUILD_DIR/export.log"; then
    cat >&2 <<MSG

────────────────────────────────────────────────────────────────────
The archive built and signed fine. The upload failed only because
App Store Connect has no app record for $BUNDLE_ID yet.

Create it once (about a minute):
  1. https://appstoreconnect.apple.com/apps  →  "+"  →  New App
  2. Platform: iOS
     Name:     FrameCut          (must be unique across the store)
     Language: English (U.K.)
     Bundle ID: $BUNDLE_ID
     SKU:      framecut
  3. Re-run this script. It reuses the archive, so it takes seconds.
────────────────────────────────────────────────────────────────────
MSG
  fi
  exit "$EXPORT_STATUS"
fi

# App Store Connect rejects a build number it has already seen, so retire the
# archive once it has been accepted. The next run then builds a fresh one with
# a new number instead of failing on a duplicate.
rm -rf "$ARCHIVE"

echo
echo "Uploaded build $BUILD_NUMBER for $BUNDLE_ID."
echo "It appears in App Store Connect → TestFlight in 5-15 minutes once processing finishes."
echo "Add testers there, and they get the install link."
