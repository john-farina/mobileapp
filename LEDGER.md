# feat/2-stone-devtools

Stacked on `feat/1-devices-stone-panel`.

## What

Developer tools for running your own firmware: bug reports go to the Stone
channel server, the Update button shows the build's notes, a Roll back button
returns to what was on the watch before the last update, and a Watch logs
screen streams app logs live and pulls the firmware log dump, all copyable.

## Status

- Compiles for iOS. Nothing here has been run on a watch yet.
- Server routes are live in `PebbleOS` `tools/stone/channel` with tests.
- Untested end to end: the upload flow (create → presign → PUT → complete)
  against the deployed server; app-log shipping toggle on a real watch.

## Decisions

- **Bug reports reuse `BugApi.kt` untouched.** It already posts to `bugUrl`,
  which is the Stone server, through Core's eng-dash routes. The server now
  implements those four routes (`/bug-reports/create`, `/upload/presigned`,
  `PUT /upload/files/:key`, `/upload/complete`) plus `/reports`. The only app
  change is the sign-in gate: it applies only when a sign-in provider exists in
  the build, which in Stone is none. Rejected: a new Stone-specific upload
  path, which would duplicate 200 lines of working code.
- **`completeUpload` accepts a URL or a key.** The app derives `fileKey` by
  splitting `fileUrl` at Core's bucket name; with no such segment it sends the
  whole URL. The server keeps the last path segment either way.
- **Rollback is "install the build that was running before".** The previous
  version is one string in `Settings`; the bundle is re-downloaded from the
  server, which keeps every build. Rejected: caching bundles on the phone.
- **Live tail is app logs only, by protocol.** Firmware logs are never streamed
  by PebbleOS; they exist as a dump on request (endpoint 2002), so the screen
  pulls that. App logs stream over endpoint 2006 once shipping is enabled.
  Both use existing libpebble surfaces (`ConnectedPebble.Messages`,
  `ConnectedPebble.Logs`); no libpebble change.
- **Copy is the export.** Whole buffer to the clipboard, capped at 2000 lines.

## Changes

- `composeApp/.../BugReportScreen.kt` — sign-in gate only when a provider exists
- `pebble/.../ui/StoneDevicePanel.kt` — notes dialog, Roll back, Watch logs button
- `pebble/.../ui/WatchLogsScreen.kt` — new
- `pebble/.../ui/PebbleRoutes.kt` — `WatchLogsRoute`
- `pebble/.../ui/WatchesScreen.kt` — pass `navBarNav` to the panel
- PebbleOS `tools/stone/channel/src/index.js`, `test/index.test.js` — bug report routes

## Next

- Send one report from the phone and open `GET /reports` on the server.
- Watch the live toggle with a watchapp that calls `app_log`.
- If firmware-log streaming is ever wanted, that is a firmware change: ship
  `PBL_LOG` lines over a protocol endpoint the way `app_logging.c` does.
