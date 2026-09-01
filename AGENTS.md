# Stone (this fork)

This is a personal fork of the Pebble companion app, renamed **Stone**, pointed
at a private firmware channel server so builds from a forked PebbleOS arrive as
ordinary update cards.

**Read `STONE.md` before changing anything here** — it lists every file this fork
changes and why. **Read `STONE-OS.md` before touching OTA, versions, or the
channel URL** — those are a contract with a second repository, and breaking them
fails silently.

| Read | For |
| --- | --- |
| `STONE.md` | What this fork changes, how to build it, TestFlight setup |
| `STONE-OS.md` | The firmware fork, the OTA contract, and what must change in lockstep |

## The five things most likely to waste your time

- **Work on `stone`, never `master`.** `master` is an untouched upstream mirror.
- **Target build settings override `Config.xcconfig`.** The target had
  `APP_NAME = Pebble` and `BUNDLE_ID = coredevices.coreapp` hardcoded, which beat
  the xcconfig entirely. Both were removed so the xcconfig is authoritative.
  Verify a rename actually took, rather than assuming:

  ```shell
  cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
    -configuration Release -showBuildSettings | grep -E 'APP_NAME|PRODUCT_'
  ```

- **The version comes from a git tag, and a missing tag is a hard build failure.**
  A build phase runs `git describe --tags --abbrev=0` under `set -e` and parses
  `X.Y.Z` or `X.Y.Z.B`, erroring on anything else. A shallow clone has no tags and
  fails with `Command PhaseScriptExecution failed with a nonzero exit code`. Fix
  by tagging, e.g. `git tag -a 1.0.0.1 -m ...`, not by editing the script.
- **An app icon must not carry an alpha channel.** `AppIcon.appiconset` is
  flattened RGB on purpose; a transparent PNG is rejected at submission.
- **`GoogleService-Info.plist` must exist** even if it is the dummy. The
  Crashlytics build phase references it by path in Release builds and fails
  without it.

## CI

`.github/workflows/stone-testflight.yml` — every push to `stone` archives, signs
and uploads to TestFlight. Signing uses an App Store Connect API key with
`-allowProvisioningUpdates`, so nothing signing-related is committed.

It tags `1.0.0.<run_number>` in the runner (never pushed) because TestFlight
rejects a `CFBundleVersion` it has already seen, and run numbers never repeat.

Markdown-only pushes are skipped: a macOS runner costs 20–40 minutes.

## Commits

Match the firmware fork's conventions so the two repos read alike:

- `area: short description` — lower-case area, imperative
- `git commit -s`, exactly one `Signed-off-by`
- co-author trailer: `Co-Authored-By: Claude <noreply@anthropic.com>`

Use `stone:` for fork-local work and `ci:` for workflow changes.

## Feature branches (agents start here)

Every push to any branch is archived by Xcode Cloud and lands in TestFlight, so
a feature is testable on a phone without anyone doing anything.

- **Branch name: `feat/<n>-<slug>`**, where `<n>` is the next unused integer
  (`git branch -r | grep feat/` shows the taken ones). The number is the
  version: `feat/12-channel-filter` archives as `1.12.0 (build)`, which gives
  the branch its own section in TestFlight. `stone` is `1.0.0`. A branch that
  does not follow the pattern shows up as `1.999.0`.
- **Branch from `stone`**, never `master`.
- **Keep `LEDGER.md` at the repo root** and update it in every commit. It is
  the handoff document for the next agent and it is also the build's TestFlight
  notes, so write it for a reader on a phone. Sections, in this order:
  1. **What** — the feature in two sentences
  2. **Status** — what works, what does not, what is untested
  3. **Decisions** — each choice and why, including what was rejected
  4. **Changes** — files touched and what changed in each
  5. **Next** — what is left, concrete enough to start from
- Delete `LEDGER.md` in the commit that merges the branch into `stone`; its
  content belongs in `STONE.md` by then.
