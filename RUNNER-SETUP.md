# Finishing the TestFlight pipeline

**Status: one manual step remains.** Everything else is done and pushed.

The build cannot run on a GitHub-hosted macOS runner — see the memory table in
`STONE.md`. `.github/workflows/stone-testflight.yml` therefore targets
`runs-on: [self-hosted, macos, arm64]`, and that runner is not registered yet.

## The one command John has to run

The runner package is already downloaded, hash-verified and extracted at
`~/actions-runner`. Registering it is the step an agent cannot do: `config.sh`
authorises a machine to execute code sent from GitHub, and that is a decision
for the person who owns the machine.

**Get a fresh token** — the one from the setup page expires in about an hour:
<https://github.com/john-farina/mobileapp/settings/actions/runners/new?arch=arm64&os=osx>

Then, in a terminal:

```shell
cd ~/actions-runner
./config.sh --url https://github.com/john-farina/mobileapp \
  --token <TOKEN FROM THAT PAGE> \
  --name stone-mac \
  --labels self-hosted,macos,arm64,stone \
  --work _work --unattended --replace

# run it as a background service that survives reboots
./svc.sh install
./svc.sh start
./svc.sh status
```

`./run.sh` also works, but only while that terminal stays open. The service is
the better choice for a machine you actually use.

Confirm it registered:

```shell
gh api repos/john-farina/mobileapp/actions/runners \
  --jq '.runners[] | "\(.name)  \(.status)  \(.labels|map(.name)|join(","))"'
```

Expect `stone-mac  online  self-hosted,macos,arm64,stone`.

## Then trigger a build

```shell
gh workflow run stone-testflight.yml --repo john-farina/mobileapp --ref stone
gh run watch --repo john-farina/mobileapp
```

Expect roughly 5–15 minutes on an M2 Max, against ~40 on a hosted runner —
and it should not OOM, because the machine has 32 GB and `gradle.properties`
is used as written.

## What "done" looks like

The build appears in **App Store Connect → TestFlight → iOS builds** as
`1.0.0 (N)`, where N is the workflow run number. Apple processes it for a few
minutes, then it is installable from the TestFlight app. The first build asks
for export-compliance answers before it becomes available.

## Where the pipeline already works

Everything up to the release link is proven on hosted runners:

- signing and provisioning through the App Store Connect API key —
  `com.johnfarina.stone` was registered and a profile created
- CocoaPods resolution
- every Kotlin module compiling
- versioning: the workflow tags `1.0.0.<run_number>` in the runner and never
  pushes it, because a build phase parses `X.Y.Z.B` and TestFlight rejects a
  `CFBundleVersion` it has seen before

The only step never reached is `linkPodReleaseFrameworkIosArm64`, and only for
lack of memory.

## If it still fails

Read the real error before changing anything:

```shell
gh run view <run-id> --repo john-farina/mobileapp --log-failed | tail -40
```

Failures already ruled out, so they do not need re-diagnosing:

| Symptom | Cause |
| --- | --- |
| `-authenticationKeyID` empty | secrets did not exist when the run started |
| `Errno::ENOENT ... FirebaseInstallations/<v>.lock` | caching `~/Library/Caches/CocoaPods`; do not cache CocoaPods |
| `OutOfMemoryError` in the release link | hosted runner's 7 GB; the reason for self-hosting |
| `OutOfMemoryError` in `:experimental:compileKotlinIosArm64` | Kotlin daemon below 2g |
| `PhaseScriptExecution failed` locally | no git tag; the version phase needs `X.Y.Z.B` |

## Security note

This repository is **public**, and a self-hosted runner on a public repository
lets a fork PR run code on the runner — for `pull_request` events GitHub uses
the workflow file from the PR's head, so a PR can add `runs-on: self-hosted`.

`fork-pr-contributor-approval` is set to `all_external_contributors`, so every
external PR needs manual approval first. **Approving a PR without reading its
workflow diff defeats that.** Making the repository private removes the risk
entirely and is what GitHub recommends.
