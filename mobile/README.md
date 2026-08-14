# FrameCut for iPhone and Android

Native apps that do what the [web version](../README.md) does — browse Google
Drive, trim a video, save the copy back — but on a phone, with sign-in that
sticks and a trim engine that finishes in seconds.

| | iOS | Android |
|---|---|---|
| Language | Swift + SwiftUI | Kotlin + Jetpack Compose |
| Sign-in | `ASWebAuthenticationSession` + PKCE, refresh token in Keychain | Custom Tabs + PKCE, refresh token in app-private storage |
| Trim | `AVAssetExportSession` (passthrough) | `MediaExtractor` + `MediaMuxer` |
| Dependencies | none beyond the system frameworks | Compose + coroutines only |

Both are deliberately dependency-light: no Flutter, no React Native, no
FFmpeg, no Google Sign-In SDK. Everything is a platform API.

## Why the trim is fast

The trim is a **stream copy**, not a re-encode. The compressed video and audio
samples are moved into a new container untouched, so the phone never decodes or
encodes a frame. The quality is bit-identical to the source and the battery
barely notices.

Measured on the exact code path the iOS app uses (`AVAssetExportSession`,
passthrough preset), trimming 45 s off the front and 90 s off the back of a
30-minute 1080p file:

```
source:   384.4 MB, 30.0 min
keeping:  45s – 1710s (27.8 min)
TRIM TOOK: 4.03 seconds          # 413x faster than playback
```

That run was on this Mac rather than a handset, but the operation is bound by
storage throughput rather than CPU, and iPhone/modern Android storage is fast —
expect the same few-seconds order. Re-encoding the same clip would take minutes
even with hardware acceleration, which is exactly why neither app offers it.

The trade-off is keyframe alignment: the start lands on the last keyframe at or
before your handle, so it can sit a second or two earlier than you dragged.
(In the run above, asking to keep 27.75 min yielded 27.75 min of output — the
snap is small.)
For topping and tailing a lecture that is the right deal — a precise cut would
mean re-encoding the whole kept region, which is the one operation that is
genuinely slow on a phone. Both apps say this on the trim screen.

In practice the wait you actually notice is the Drive download and upload, not
the trim. Both apps use the same tricks as the web version: parallel ranged
downloads to get past Drive's per-connection throttling, and chunked resumable
uploads that survive a dropped connection.

## Before either app can sign in

Both apps ship with a placeholder client id and show a clear "not connected"
screen until you fill it in. **You have to create the OAuth clients yourself** —
they are tied to your Google Cloud project, and Google only issues them through
the console.

Go to **Google Cloud console → APIs & Services → Credentials** in the same
project as the web app (`754571415429`), then **Create credentials → OAuth
client ID**, twice:

### iOS client

- Application type: **iOS**
- Bundle ID: `io.github.nipunbatra.framecut`

Copy the client id. Open `mobile/ios/FrameCut.xcconfig` and set
`GOOGLE_CLIENT_ID_SUFFIX` to the part **before** `.apps.googleusercontent.com`:

```
GOOGLE_CLIENT_ID_SUFFIX = 754571415429-a1b2c3d4e5
```

That one value drives both the runtime client id and the redirect URL scheme,
so there is nothing else to keep in sync.

### Android client

- Application type: **Android**
- Package name: `io.github.nipunbatra.framecut`
- SHA-1: the fingerprint of the keystore that signs the APK

Get the fingerprint for the debug keystore (used by local builds):

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep SHA1
```

Register **both** the debug fingerprint and your release keystore's fingerprint
— an APK signed with a key Google does not know will be refused at sign-in.
You can add several Android clients to the same project, one per fingerprint.

### One thing to decide about consent

Google treats `https://www.googleapis.com/auth/drive` as a **restricted** scope,
and that decides how long sign-in lasts:

- **OAuth app in "Testing"** — refresh tokens expire after **7 days**, so
  everyone signs in again weekly. Fine for trying it out, annoying to live with.
- **Internal to your Workspace** (`iitgn.ac.in`) — no expiry, no verification,
  no review. This is the right choice if the users are you and your lab.
  Personal `@gmail.com` accounts cannot use an internal app.
- **Published externally** — no expiry, but the restricted scope triggers
  Google's verification process, which can include a paid security assessment.

Set this under **APIs & Services → OAuth consent screen**. Unless you plan to
give this to the public, choose Internal.

## Building and installing

### Android — the APK

```bash
cd mobile/android
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it over
USB with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or just
send yourself the file and open it on the phone (Android will ask you to allow
installing from that source once).

CI also builds an APK on every push and attaches one to each GitHub release,
so there is a permanent download link — see
[`.github/workflows/android.yml`](../.github/workflows/android.yml).

### iOS — the simulator

```bash
cd mobile/ios
xcodebuild -project FrameCut.xcodeproj -scheme FrameCut \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 16 Pro' build
```

Or just open `FrameCut.xcodeproj` and press Run.

### iOS — your own iPhone

Set `DEVELOPMENT_TEAM` in `FrameCut.xcconfig` to your Apple team id
(`LFMTDT3LDX`), plug the phone in, pick it in Xcode, and Run.

### iOS — TestFlight

`mobile/ios/scripts/testflight.sh` archives, signs, and uploads in one go. It
needs two things that only exist in your App Store Connect account:

1. **An app record.** App Store Connect → Apps → **+** → New App, with bundle
   id `io.github.nipunbatra.framecut`. This only has to be done once, and it is
   what mints the eventual TestFlight invite link.
2. **Your API issuer id.** App Store Connect → Users and Access → Integrations
   → App Store Connect API. It is the UUID at the top of the page. Your API
   keys are already on this machine (`74ZBV87T64`, `J9YGJ3869A`), so the issuer
   id is the only missing piece.

Then:

```bash
export ASC_ISSUER_ID=<uuid-from-step-2>
export ASC_KEY_ID=74ZBV87T64
./mobile/ios/scripts/testflight.sh
```

The build shows up under TestFlight after 5–15 minutes of processing. Add
testers there and TestFlight generates the public install link.

Note that the first external TestFlight group needs a short Apple review
(usually under a day). Internal testers — anyone on your App Store Connect team
— get builds immediately with no review at all, which is the fast path if this
is for you and a few colleagues.

## What the apps do differently from the web version

- **Sign-in persists.** The web app has to re-prompt because browsers cannot
  hold a refresh token safely. The apps store one in the Keychain / app-private
  storage and mint access tokens silently, so you sign in once.
- **No 2 GB ceiling.** The web app holds the video in memory; the apps stream
  it to disk, so file size is bounded by free storage, not RAM.
- **No precise-cut mode.** The web app can re-encode for a frame-exact cut.
  On a phone that is the slow path, so the apps are lossless-only. Use the web
  app when you need an exact frame.
- **Save to the device.** Both apps can drop the trimmed file into the photo
  library / Movies folder instead of uploading it.
