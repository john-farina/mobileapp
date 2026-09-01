# Stone — companion app fork

A personal fork of the Pebble companion app, pointed at a private firmware
channel server so builds from the [Stone OS](https://github.com/john-farina/PebbleOS)
fork arrive as ordinary update cards instead of hand-sideloaded `.pbz` files.

Everything upstream does still works. This fork changes **two files**.

## What changed

| File | Change |
| --- | --- |
| `gradle.properties` | `bugUrl=https://stone-channel-production.up.railway.app` |
| `iosApp/Configuration/Config.xcconfig` | `TEAM_ID`, `BUNDLE_ID=com.johnfarina.stone`, `APP_NAME=Stone` |
| `iosApp/iosApp.xcodeproj/project.pbxproj` | dropped the target-level `APP_NAME`/`BUNDLE_ID` overrides |
| `androidApp/.../values/strings.xml` | `app_name` -> Stone |
| `Assets.xcassets/AppIcon.appiconset` | the Stone logo |

### Naming

The target had `APP_NAME = Pebble` and `BUNDLE_ID = coredevices.coreapp`
hardcoded in its build settings, which **override** anything in
`Config.xcconfig` — so setting the xcconfig alone left the app called Pebble.
Both target-level overrides are removed, making the xcconfig the single source
of truth. Verify with:

```shell
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -configuration Release -showBuildSettings | grep -E 'APP_NAME|PRODUCT_'
```

```
APP_NAME = Stone
PRODUCT_NAME = Stone
PRODUCT_BUNDLE_IDENTIFIER = com.johnfarina.stone
```

Upstream sets `PRODUCT_BUNDLE_IDENTIFIER = "${BUNDLE_ID}${TEAM_ID}"` so that an
unedited fork still gets a unique identifier. This fork sets `BUNDLE_ID`
explicitly, so the suffix is dropped — otherwise the id would be
`com.johnfarina.stoneK6TCM5Z5RP`, which is what the App Store Connect app record
would then have to match.

Naming it Stone rather than Pebble is also what upstream's README asks: the
trademark should stay referential.

### The icon

`Assets.xcassets/AppIcon.appiconset/app-icon-1024.png` is rendered from
`docs/stone/logo.svg` in the firmware repo, composited onto a light gradient so
the dark watch body stays legible, and flattened to RGB. **iOS app icons must
not carry an alpha channel** — a PNG with one is rejected at submission, so the
render is deliberately opaque rather than transparent.

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

## TestFlight on every push

`.github/workflows/stone-testflight.yml` builds and ships to TestFlight on every
push to `stone`, so the app can be updated from the phone with no Mac involved.

### One-time setup

**1. Create the app record.** TestFlight will not accept a build for a bundle ID
that has no app in App Store Connect. At
<https://appstoreconnect.apple.com/apps> → **+** → **New App**:

| Field | Value |
| --- | --- |
| Platform | iOS |
| Bundle ID | `com.johnfarina.stone` — pick it from the list |
| SKU | anything unique, e.g. `stone-companion` |
| Name | must be unique across the whole App Store; "Stone" alone is likely taken |

If the bundle ID is not in the list, build once from Xcode first — that
registers it.

**2. Create an App Store Connect API key.**
<https://appstoreconnect.apple.com/access/integrations/api> → **Keys** →
**+** → access **App Manager** → **Generate**. Download the `.p8`
**immediately**; Apple allows exactly one download. Note the Key ID and the
Issuer ID from the same page.

**3. Add three repository secrets** at
`Settings → Secrets and variables → Actions`:

| Secret | Value |
| --- | --- |
| `ASC_KEY_ID` | the Key ID, e.g. `A1B2C3D4E5` |
| `ASC_ISSUER_ID` | the Issuer ID (a UUID) |
| `ASC_PRIVATE_KEY` | the **entire** contents of the `.p8`, including the BEGIN/END lines |
| `APPLE_TEAM_ID` | `K6TCM5Z5RP` |

### Why the build runs on a self-hosted runner

A GitHub-hosted `macos-15` runner has **7.0 GB of RAM**, and
`linkPodReleaseFrameworkIosArm64` -- the Kotlin/Native release link -- needs more
heap than that can spare alongside the Kotlin daemon. Every split was tried:

| native / daemon / gradle | Result |
| --- | --- |
| 12g / 6g / 4g (upstream defaults) | OOM in the release link |
| 4g / 2g / 2g | OOM later, during body lowering |
| 5g / 1g / 1g | OOM moved earlier, into `:experimental:compileKotlinIosArm64` |
| 5g / 2g / 1g | OOM in the release link |
| 6g / 2g / 1g | OOM in the release link |

The daemon needs 2g or module compilation fails; the native compiler needs more
than 6g; the machine has 7. There is no split that fits, so the build runs on a
real Mac instead.

That machine also keeps `~/.gradle` and `~/.konan` between runs, so no
`actions/cache` steps are needed -- and the heaps in `gradle.properties` are used
as written rather than trimmed.

```{warning}
**This repository is public, and a self-hosted runner on a public repository is a
known risk.** For `pull_request` events GitHub runs the workflow file from the
PR's head, so a fork PR can add `runs-on: self-hosted` and execute arbitrary code
on the runner -- which here is a personal Mac holding signing identities and SSH
keys.

The mitigation in place is
`actions/permissions/fork-pr-contributor-approval: all_external_contributors`,
so **every** external PR needs manual approval before any workflow runs. That is
a human gate: approving a PR without reading its workflow diff defeats it.

Making this repository private removes the entire class of attack, and is what
GitHub recommends for self-hosted runners.
```

### How the version works

A build phase in `iosApp.xcodeproj` runs `git describe --tags --abbrev=0` and
parses the tag as `X.Y.Z` or `X.Y.Z.B`, where `B` becomes `CFBundleVersion`. It
**hard-errors on any other shape**, so the workflow creates the tag itself:

```
1.0.0.<github.run_number>
```

Run numbers never repeat, which matters because TestFlight rejects a
`CFBundleVersion` it has already seen. Change the marketing version by running
the workflow manually with a different `version` input.

The tag is created in the runner and never pushed — it only has to exist for
`git describe` during the build.

### What the fork already fixed for CI

- `iosApp/iosApp/GoogleService-Info.plist` — copied from the dummy upstream
  ships. The Crashlytics build phase references this path in Release builds and
  fails without it.
- `iosApp/iosApp/iosApp.entitlements` — dropped
  `applinks:cloud.repebble.com`. That is Core's domain; an associated-domains
  entitlement for a domain you do not control is at best useless.

## Building it locally

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
