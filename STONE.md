# Stone — companion app fork

A personal fork of the Pebble companion app, pointed at a private firmware
channel server so builds from the [Stone OS](https://github.com/john-farina/PebbleOS)
fork arrive as ordinary update cards instead of hand-sideloaded `.pbz` files.

Everything upstream does still works. This fork changes **two files**.

## What changed

| File | Change |
| --- | --- |
| `gradle.properties` | `bugUrl=https://stone-channel-production.up.railway.app` |
| `iosApp/Configuration/Config.xcconfig` | `BUNDLE_ID`, `APP_NAME=Stone`, `TEAM_ID` left for you |

No Kotlin was modified. The integration point already existed: `EngDashOta.kt`
polls `GET $BUG_URL/ota/latest` and already understands the response shape the
Stone channel server returns.

## Getting both Stone and normal Pebble updates

This is a **toggle in the app**, not a build option.

`FirmwareUpdateCheck.coreDeviceCheck()` reads a user setting:

```kotlin
if (engDashOtaEnabled()) {                       // BUG_URL != null && useEngDashOta
    val result = engDashOta.getLatestFirmware(watch)
    if (result !is UpdateCheckFailed) return result
}
return if (MEMFAULT_TOKEN != null) memfault.getLatestFirmware(watch)
       else cohorts.getLatestFirmware(watch)     // https://cohorts.rebble.io
```

So in **Settings → Debug → "Use Core OTA service"**:

| Toggle | You get |
| --- | --- |
| **On** | Stone builds from your channel server |
| **Off** | Normal Pebble firmware, via Cohorts (`cohorts.rebble.io`) |

Cohorts uses `HttpClientAuthType.PebbleOptional`, so it works without any Core
credentials — which this fork does not have. `memfaultToken` is left empty
deliberately; Memfault is Core's crash-reporting and OTA service and its token is
not public.

**Note the fallback is failure-only.** If your server answers `204 No Content`
("nothing new"), that is a *success*, so the app stops there and does not go on
to check Cohorts. The toggle is the way to switch sources, not a merge.

## Building it

Prerequisites (from upstream's README):

```shell
brew install openjdk@17          # Java 17; Java 8 will not work
brew install cocoapods
# Android Studio is required even for an iOS-only build (it supplies the SDK)
xcodebuild -downloadPlatform iOS
./gradlew podInstall             # generates Podfile.lock
```

Then:

1. Open **`iosApp/iosApp.xcworkspace`** — the workspace, not the `.xcodeproj`.
2. Signing & Capabilities → pick your team. Set `TEAM_ID` in
   `Config.xcconfig` too if you want reproducible command-line builds.
3. Set up `iosApp/iosApp/iosApp.entitlements` with the HealthKit entitlements
   upstream's README lists.
4. Firebase: create a project matching your `BUNDLE_ID` and drop in
   `GoogleService-Info.plist`, or use the dummy plist upstream provides.
5. `git tag 1.0.0.1` — the version derives from a tag shaped `X.Y.Z.B`, where `B`
   becomes `CFBundleVersion`. **Every part must be numeric**; the build script
   hard-errors otherwise.
6. Build to a **physical device**. Upstream notes simulator builds currently fail
   to link because of a third-party library.

## Two things to know before you rely on it

- **Uninstall the official app once this works.** Both would compete for the
  watch's BLE companion connection.
- **Free personal-team signing expires after 7 days**, after which the app stops
  launching until you rebuild. For an app your watch depends on daily, the paid
  Apple Developer Program (TestFlight, 1-year provisioning) is the saner option.

## Why this matters beyond convenience

The official app checks Core's OTA service. When you sideload a Stone build, it
sees a version lower than Core's shipping firmware, calls it a downgrade, reboots
the watch into PRF, and then its update check installs Core's firmware *over the
build you just installed* — which looks exactly like a failed install.

Stone OS works around that with a version floor (`v200.x`). Pointing the app at
your own server removes the cause instead: the update check returns *your*
builds, so there is nothing to override you.
