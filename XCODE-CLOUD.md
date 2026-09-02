# Xcode Cloud

The preferred way to get this app into TestFlight. Apple's own CI, 25 compute
hours a month included with the Developer Program, and TestFlight is its native
destination rather than something bolted on with an API key.

It also removes a real problem: a self-hosted runner on a **public** repository
lets a fork pull request run code on the runner, because `pull_request` events
use the workflow file from the PR's head. Xcode Cloud has no such exposure.

## Why a setup script is needed at all

Xcode Cloud provides Xcode and the Apple toolchain. It does not provide:

- **a JDK** — and this project's Xcode build shells out to Gradle, because the
  Kotlin framework is built from an Xcode build phase. Without Java the archive
  fails inside a run script, which reads as a mysterious
  `PhaseScriptExecution failed` rather than "install Java".
- **an Android SDK** — the Gradle build configures an Android target even for an
  iOS-only archive and wants `sdk.dir`.
- **git tags** — Xcode Cloud clones without them, and a build phase runs
  `git describe --tags --abbrev=0`, hard-erroring on a tag it cannot parse.
- **CocoaPods** — `Pods/` is not committed.

`iosApp/ci_scripts/ci_post_clone.sh` handles all four. Xcode Cloud runs it
automatically after cloning; the filename is the contract, not a config entry.

Build numbering uses `CI_BUILD_NUMBER`, Xcode Cloud's own monotonic counter,
which is what `CFBundleVersion` wants — TestFlight refuses a build number it has
already seen.

## Creating the workflow

This part is a GUI flow and has to be done by hand, once.

**In Xcode** (the workspace is already open at `iosApp/iosApp.xcworkspace`):

1. **Integrate → Create Workflow** (older Xcode: Product → Xcode Cloud →
   Create Workflow)
2. Pick the **iosApp** scheme
3. Grant access to `github.com/john-farina/mobileapp` when asked — Xcode Cloud
   needs a GitHub App installed on the repository
4. Edit the workflow it proposes:

   | Setting | Value |
   | --- | --- |
   | Name | Stone TestFlight |
   | Start Condition | Branch Changes → `stone` |
   | Environment | latest Xcode, macOS |
   | Action | **Archive**, scheme `iosApp`, **iOS** |
   | Post-Action | **TestFlight Internal Testing** |

5. Save. It builds on every push to `stone`.

App Store Connect → your app → **Xcode Cloud** does the same thing in a browser.

## What to expect

The build lands in **App Store Connect → TestFlight → iOS builds** as
`1.0.0 (N)`. Apple processes it for a few minutes; the first one asks for
export-compliance answers before it is installable.

## If it fails

Download the logs from the build's Archive action in App Store Connect; the
GitHub check summary strips the detail. The post-clone log prints the machine
(64 GB, 12 cores as of 2026-09) and every gradle task over 5 s as `TASK-TIME`.

Failures already diagnosed, all fixed in `ci_post_clone.sh`:

| Symptom | Cause |
| --- | --- |
| `PhaseScriptExecution failed`, right after pods compile | the `Build composeApp` phase runs gradle inside xcodebuild with no environment; brew's JDK is keg-only. Fixed by linking it under `~/Library/Java/JavaVirtualMachines` |
| `Errno::ENOENT ... FirebaseFirestoreGRPCCPPBinary/...` in `podInstallSyntheticIos` | `:experimental:` and `:composeApp:` synthetic pod installs ran in parallel in one gradle run. Fixed by pre-running them sequentially |
| `java.lang.OutOfMemoryError: Java heap space` in the Kotlin compile | upstream heaps (12g/6g/4g) overflow here. `~/.gradle/gradle.properties` sets 24g/12g/8g and outranks the project file |
| `Build input file cannot be found: .../GoogleService-Info.plist` | the path is gitignored; the dummy is copied into place |
| `No route to host` fetching from `dl.google.com`, four builds in a row | Java chose the host's IPv6 address on a machine with no IPv6 route. `-Djava.net.preferIPv4Stack=true` on all gradle JVMs |

Not available: a cache between builds. `CI_DERIVED_DATA_PATH` is empty at
post-clone time on every build, so gradle, Kotlin/Native and CocoaPods all
start cold. A cold build is about 42 minutes.

## The GitHub Actions workflow

`.github/workflows/stone-testflight.yml` still exists and still targets a
self-hosted runner. Keep whichever proves reliable; running both just builds
everything twice. If Xcode Cloud works, delete the Actions workflow and the four
`ASC_*`/`APPLE_TEAM_ID` secrets with it.
