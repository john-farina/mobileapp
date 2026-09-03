# feat/1-devices-stone-panel

## What

The Watches tab shows the Stone channel and every debug setting on the watch
card itself, under the colour/battery row and above Disconnect. Stone-specific
controls stop living three taps deep in Settings → Phone → Debug.

## Status

- Builds for iOS (`:pebble:compileKotlinIosArm64`). Not yet run on a watch.
- Untested: the Update button end to end (download → verify → sideload). It
  reuses the same path the Stone channels screen already used.

## Decisions

- One new file, `StoneDevicePanel.kt`, two composables. `WatchDetails` gains
  two lines and a `SnackbarDisplay` parameter, threaded from `topBarParams`
  through `WatchItem`. Rejected: a CompositionLocal for the snackbar, more
  machinery than one parameter.
- Debug settings are rendered from `rememberSettingsItemsState`, the same
  list Settings shows, filtered to `isDebugSetting`. Rejected: copying the
  items, which would drift from Settings.
- The "Show debug options" toggle is filtered out; inline means always shown.
- The trunk channel (`stone`) is what the card tracks. Other channels stay in
  the Stone channels screen.
- Download + sideload moved into `StoneBundleInstaller.installOnWatch` so the
  card and the channels screen share it.

## Changes

- `pebble/.../ui/StoneDevicePanel.kt` — new: `StoneChannelPanel`, `InlineDebugOptions`
- `pebble/.../ui/WatchesScreen.kt` — mount both in `WatchDetails`; thread `snackbarDisplay`
- `pebble/.../services/StoneBundleInstaller.kt` — `installOnWatch`
- `pebble/.../ui/StoneChannelsScreen.kt` — use `installOnWatch`

## Next

- Run it on the watch: confirm the card shows `on watch` after an update.
- Decide whether the inline debug list needs a collapse once it is seen at
  real length.
