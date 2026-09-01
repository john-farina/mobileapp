# The firmware this app talks to

This fork exists to serve one firmware fork. If you are changing anything in
this repository that touches OTA, versions, or the channel server, **the other
side may need a matching change** — and nothing will tell you if you get it
wrong, because the failure is silent.

| | |
| --- | --- |
| **Firmware fork** | <https://github.com/john-farina/PebbleOS> — branch `stone` |
| **Channel server** | `tools/stone/channel/` in that repo, deployed at `https://stone-channel-production.up.railway.app` |
| **Watch** | Pebble Time 2, `obelix@pvt` (SiFli SF32LB52) |
| **Read first over there** | `docs/stone/index.md`, then `docs/stone/channels.md` |

## The contract between the two repos

`EngDashOta.kt` calls `GET $BUG_URL/ota/latest` and expects:

```json
{
  "version": "v200.0.0.1-27-g1c42dbfb9",
  "notes": "channel: stone\ncommit: 1c42dbf\n...",
  "is_downgrade": false,
  "artifacts": [{ "url": "https://.../stone_obelix_pvt_<version>.pbz" }]
}
```

It sends `device_serial`, `hardware_version`, and — when not in recovery —
`current_version`. The server implementation is `tools/stone/channel/src/index.js`
in the firmware repo.

**Neither side may change this shape alone.**

## Changes that require the other repo to change too

| Change here | What must change in the firmware repo |
| --- | --- |
| The response fields `EngDashOta` parses | `otaLatest()` in `tools/stone/channel/src/index.js`, plus its tests |
| `bugUrl` in `gradle.properties` | nothing, but the new server must serve the same routes |
| How the app compares versions | `docs/stone/recovery.md` — the version floor exists *because* of this comparison |

| Change in the firmware repo | What must change here |
| --- | --- |
| The `/ota/latest` response shape | `EngDashLatestResult` in `EngDashOta.kt` |
| The deployed channel-server URL | `bugUrl` in `gradle.properties` |
| Dropping the `v200` version floor | nothing in code — but every install becomes a downgrade again |

## The version floor, and why it is not cosmetic

Stone firmware reports `v200.x`. That is deliberate and load-bearing.

`FirmwareUpdateCheck.coreDeviceCheck()` treats a build whose `major.minor.patch`
is lower than the running firmware as a **downgrade**, and the downgrade path
reboots the watch into PRF. Once in PRF, the update check runs again, finds
Core's shipping firmware newer, and installs *that* over the build the user
just sideloaded. The build never transfers. It looks exactly like a failed
install; it is a successful install of something else.

Core ships `v4.36.2` from a release branch that upstream `main` never reaches,
so no main-derived version can ever win. Hence 200. See
`docs/stone/recovery.md` in the firmware repo — including why it is 200 and not
999 (`WatchInfoVersion` fields are `uint8_t`).

**Pointing this app at the Stone channel server removes the cause**: the update
check returns Stone builds, so there is nothing to override the user. The floor
is the belt; this fork is the braces. Do not remove either without understanding
the other.

## Getting both Stone and stock Pebble updates

`Settings → Debug → "Use Core OTA service"`:

| Toggle | Source |
| --- | --- |
| On | Stone builds, from the channel server |
| Off | Normal Pebble firmware, via Cohorts (`cohorts.rebble.io`) |

The fallback is **failure-only**. A `204 No Content` from the Stone server means
"nothing new", which is a *success*, so the app stops there and does not go on to
check Cohorts. It is a switch, not a merge — do not "fix" this by treating 204 as
a failure, or every quiet poll would fall through to Core's servers.

## Working on this fork

- Work on `stone`. `master` is an untouched upstream mirror — never commit to it.
- Keep the diff against upstream files small; upstream moves fast and every edit
  is a recurring rebase conflict. See `STONE.md` for what this fork changes and
  why each change exists.
- Every push to `stone` builds and ships to TestFlight. It is not free — a macOS
  runner takes 20–40 minutes — so markdown-only pushes are deliberately skipped.
