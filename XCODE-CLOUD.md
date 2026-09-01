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

The post-clone log is the first place to look — it prints the machine's memory
and core count, which matters: the GitHub-hosted runners were abandoned because
7 GB was not enough for `linkPodReleaseFrameworkIosArm64`. If Xcode Cloud hits
the same wall the log will say so plainly, and `STONE.md` has the heap
measurements that were already tried.

## The GitHub Actions workflow

`.github/workflows/stone-testflight.yml` still exists and still targets a
self-hosted runner. Keep whichever proves reliable; running both just builds
everything twice. If Xcode Cloud works, delete the Actions workflow and the four
`ASC_*`/`APPLE_TEAM_ID` secrets with it.
