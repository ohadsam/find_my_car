---
name: release-checklist
description: Verify FindMyCar is ready to release across both distribution channels — the PWA (GitHub Pages) and the Android APK (Capacitor/GitHub Actions). Use before merging a release to main, or whenever asked to check/verify a release, run the release checklist, or confirm the PWA and APK are in sync.
---

# Release Checklist — FindMyCar

FindMyCar ships as **two distribution channels from one codebase**: a browser PWA
(GitHub Pages) and a native Android APK (Capacitor, built by
`.github/workflows/build-android.yml`). Both must be verified before calling a release
done — a version bump or feature that only landed in one channel is a release bug.

Run every check below. Report a single pass/fail table, channel by channel, then a
one-line overall verdict. Do not silently skip a check — if a tool or file isn't
available, report that check as a explicit FAIL/UNKNOWN with the reason, not omit it.

## 1. Version consistency (both channels share one version number)

- Read `js/config.js` — note the `version` field (e.g. `1.11.0`).
- Confirm `js/config.js`'s `changelog` array's first entry's `version` matches it.
- Confirm `CHANGELOG.md`'s top `## [x.y.z]` heading matches it.
- Confirm `sw.js`'s `CACHE_NAME` (e.g. `findmycar-vX.Y.Z`) matches it — a stale cache
  name means returning users won't get the update.
- Confirm `android/app/build.gradle`'s `versionName` matches it, and `versionCode` was
  bumped (any increase is fine — it just must be higher than the previous release).
- Confirm `capacitor.config.json` exists and its `appId`/`appName` are unchanged
  (these should almost never change between releases — flag if they did unexpectedly).
- Confirm `js/config.js`'s `changelog` array actually gained a NEW first entry for
  this release (not just the top-level `version` field bumped with the changelog
  left stale) — a version bump without a matching changelog entry means the
  "מה חדש" (What's New) modal silently shows last release's notes instead of this
  one's. Also confirm `CHANGELOG.md` gained a matching `## [x.y.z]` section.
- Confirm `index.html` still has `id="versionTagBtn"` in the header and
  `id="whatsNewModal"` — and that `js/app.js` sets the button's text to
  `` `v${CFG.version}` `` and wires it to `this.#ui.showWhatsNew(CFG.changelog[0])`.
  This is the only place a user sees the app actually advanced versions; a
  regression here is silent (the app still works, it just never shows it upgraded).

## 2. PWA channel

- Run `npm test` (vitest unit tests) — must pass.
- Run `npm run test:e2e` (Playwright) if a display/browser is available in this
  environment; if not runnable here, report as SKIPPED (not failed) with the reason,
  and note it should run in CI or locally before merging.
- Confirm every file listed in `sw.js`'s `STATIC_ASSETS` actually exists on disk —
  a missing entry means the service worker will fail to install offline caching.
- Confirm every `js/*.js` file has a matching `<link rel="modulepreload">` in
  `index.html` (see CLAUDE.md "Add a new feature" step 6) — grep both and diff the
  file lists.
- Confirm `manifest.json` is valid JSON and its icon paths exist under `icons/`.

## 3. APK channel

- Confirm `android/` exists with `app/src/main/AndroidManifest.xml`,
  `app/build.gradle`, and `gradlew`.
- Confirm `AndroidManifest.xml` still declares all of:
  - `.ParkingForegroundService` (`<service>`)
  - `.widgets.ActiveParkingWidgetProvider`, `.widgets.QuickSaveWidgetProvider`,
    `.widgets.MiniMapWidgetProvider` (`<receiver>`, each with an
    `android.appwidget.provider` meta-data pointing at a `res/xml/widget_*_info.xml`
    that exists)
  - Permissions: `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`,
    `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`,
    `ACCESS_FINE_LOCATION`, `CAMERA`, `RECORD_AUDIO`,
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — a permission missing here
    isn't caught by any build step (the app compiles fine and only fails at
    runtime when that specific feature is used), so check this list literally
    every release, not just when a new native feature is added
- Confirm `BluetoothClassicPlugin.kt`'s `batteryOptimizationStatus`/
  `requestIgnoreBatteryOptimizations` both guard `Build.VERSION.SDK_INT` against
  `Build.VERSION_CODES.M` before touching `PowerManager.isIgnoringBatteryOptimizations`
  or `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — both are API 23+ only,
  and this project's minSdk is 22, so an unguarded call is a `NoSuchMethodError`
  crash waiting to happen on real (if now rare) devices, not something any emulator
  running a normal API level would catch
- Confirm `android/app/build.gradle` declares `signingConfigs.debug` pointing at
  the committed `android/app/debug.keystore` (not the per-machine default) — a
  fresh CI runner without this would sign every build with a different random
  key, breaking in-place upgrades for anyone with an older build installed
- Confirm `js/bluetooth-native.js` still implements the same public methods as
  `js/bluetooth.js`'s `BluetoothController` (`isSupported`, `init`, `startWatch`,
  `stopWatch`, `checkNow`, `getDevices`, `requestPermission`) — a drift here silently
  breaks BT auto-detection in the APK only, since the PWA path keeps working and masks
  it. Grep method names in both files and diff.
- Confirm `js/app.js` selects between them via
  `NativeBluetoothController.isSupported()` (not a hardcoded `BluetoothController`)
  and that no call site references `BluetoothController.<staticMethod>()` directly
  outside `js/bluetooth.js` itself (that would bypass the native swap-in — see
  CLAUDE.md "Native plugin interface contract").
- Confirm `.github/workflows/build-android.yml` exists, and check its most recent run
  on the current branch (or `main`) via `mcp__github__actions_list` /
  `mcp__github__list_workflow_runs`-equivalent GitHub MCP tool. If the latest run
  didn't succeed, or none exists yet for this commit, trigger one with
  `mcp__github__actions_run_trigger` (workflow_dispatch) and report that a fresh run
  was started (do not block waiting for it — name it as a follow-up).
- If a run has succeeded, confirm the `findmycar-debug-apk` artifact is attached
  (list run artifacts).

## 4. Native unit tests (background-detection migration)

FindMyCar is migrating Bluetooth/GPS decision-making from the WebView into native
Kotlin, in small independently-tested stages (see CLAUDE.md "Native background
detection (`core` package)") — this sandbox has no local Android SDK/emulator, so
these tests only ever run in CI, never locally. A release must never land with this
step broken or skipped:

- Confirm `.github/workflows/build-android.yml` runs `./gradlew testDebugUnitTest`
  as its own step, **before** `assembleDebug` — if a native unit test step doesn't
  exist at all, or runs after/without blocking the APK build, a regression in the
  `core` package's decision logic would ship silently, the same failure mode the
  Diagnostic log and Headless widget action sections below exist to prevent.
- Confirm the most recent `build-android.yml` run's "Run native unit tests" step
  succeeded (not just "Build debug APK") — check the job's step list, not only the
  overall run conclusion, since a run can still show green if a later step masks an
  earlier one in some misconfigurations. If a `findmycar-native-test-report`
  artifact was uploaded, that confirms the test step actually executed.
- Confirm `android/app/build.gradle` declares
  `testImplementation "org.robolectric:robolectric:..."` — without it, any test
  touching `org.json`, `SharedPreferences`, or `Context` fails with "not mocked"
  rather than actually verifying anything.
- Confirm `android/.../core/BtDecisionEngine.kt` exists with zero `org.json`/Android
  framework imports (it must stay a pure function of `NativeVehicle` + plain
  Kotlin/lambda inputs) — this is what keeps its tests fast, reliable, and runnable
  without Robolectric; letting Android/org.json dependencies creep into it would
  silently make future tests slower and more fragile without anyone deciding that
  on purpose.
- Confirm `js/widget-bridge.js`'s `syncVehicles()` call includes `bluetoothDevice`/
  `bluetoothAutoEnd`/`bluetoothAutoStart`/`bluetoothStartPopup`/`hasParking`, not
  just `id`/`name`/`icon` — `VehicleJsonParser`/`BtDecisionEngine` depend on these
  fields being mirrored; a regression here wouldn't fail any test (the JSON would
  just parse to `NativeVehicle`s with default `false` BT/parking fields) but would
  silently make every native decision wrong. `hasParking` specifically must be read
  per-vehicle from `Store.get(CFG.keys.curPrefix + v.id)`, not from `state.current`
  (which only reflects whichever vehicle is currently active) — a regression back to
  the active-only shape would make shadow decisions silently wrong for every
  non-active vehicle without any test catching it (`VehicleJsonParser`/
  `BtDecisionEngine`'s own tests only cover parsing/deciding from already-correct
  input, not where that input comes from).
- Confirm Stage 2's shadow-mode wiring in `BluetoothClassicPlugin.kt` stays a
  no-op: `onConnected`/`onDisconnected` must call `emitAndTrack(...)` (the real,
  unchanged behavior) before `runShadowDecision(...)`, and `runShadowDecision`
  itself must only call `Log.i`/`notifyListeners("btShadowDecision", ...)` inside
  its try/catch — never `WidgetDataPlugin.update`/`.clear`,
  `ParkingForegroundService.setReasonActive`, or any other call with a real side
  effect. Shadow mode existing specifically to build confidence before Bluetooth is
  flipped to live (see CLAUDE.md's migration stage list for the current stage
  number) — a shadow-mode change that quietly starts taking real action skips that
  entire verification step.
- Confirm `runShadowDecision` is wrapped in a top-level try/catch — a bug in shadow
  evaluation (bad JSON, an engine exception) must never prevent the real
  `connected`/`disconnected` event from having already reached JS, since it's
  emitted first, and must never crash BT event handling.
- Confirm `android/.../core/GpsDecisionEngine.kt` and `GpsMath.kt` exist with zero
  `org.json`/Android framework imports (same purity requirement as
  `BtDecisionEngine.kt`) — `GpsDecisionEngine`'s functions must take the current
  time as a parameter (never read the wall clock internally), since that's what
  keeps its tests deterministic; a version that reads `System.currentTimeMillis()`
  internally would make its own tests flaky/order-dependent without any obvious
  symptom until they start failing intermittently in CI.
- Confirm `ParkingForegroundService.kt`'s GPS shadow wiring
  (`updateLocationWatch`/`onLocationShadow`) stays a no-op the same way Bluetooth's
  does: it must only call `Log.i`/`GpsShadowEventBus.emit(...)` inside its own
  try/catch, never `gpsEndModal`, `WidgetDataPlugin.update`/`.clear`, or any other
  real-action call — until the migration plan says GPS has been explicitly flipped
  to live (check CLAUDE.md's stage list for the current stage number).
- Confirm the location watch only starts/stops on a genuine `"parking"` reason
  transition in `setReasonActive()` (`parkingWasActive != parkingIsActive`), not on
  every call — `WidgetDataPlugin.update()` fires on every parking-state sync (photo
  added, description edited, etc.), not just session start; a regression back to
  "reset on every active=true call" would silently wipe the sustained-speed timer
  before it ever reaches `CFG.gpsSpeedDuration`, making the speed check permanently
  unable to fire without any test catching it (this logic lives in a `Service`,
  which the `core` package's unit tests can't reach).
- Confirm `js/widget-bridge.js`'s `syncVehicles()` call includes
  `gpsAutoEndEnabled` at the top level (not per-vehicle) — `GpsDecisionEngine`'s
  shadow evaluation reads it from `WidgetDataPlugin`'s `KEY_GPS_AUTO_END_ENABLED`;
  a regression here would silently make every GPS shadow decision evaluate as if
  the setting were off, masking whatever the real JS setting actually is.
- Confirm `index.html`'s `diagLogCategoryFilter` has a `GPS-SHADOW` option and
  `js/widget-bridge.js`'s `initShadowListener()` is called once from `js/app.js`'s
  `#init()` — losing this wiring has no other symptom (shadow mode has no real
  effect), so nothing else would catch it.
- Confirm `BluetoothClassicPlugin.maybeRecordPendingAction()` (Stage 5) still gates
  on `MainActivity.getActiveWebView() != null` and returns immediately when the
  WebView IS reachable — this is what prevents a real BT event from producing BOTH
  the normal live JS-handled action AND a recorded pending action, which would
  otherwise double-apply the same auto-end/auto-start once a later stage starts
  replaying pending actions. This is a correctness bug with no test coverage
  (`BluetoothClassicPlugin.kt` needs a live `Bridge`/`Activity`, same precedent as
  its other wiring) — verify by reading the code, not just grepping for the guard's
  existence.
- Confirm `maybeRecordPendingAction`/`recordPendingAction` (native side) never call
  `WidgetDataPlugin.update`/`.clear`, open any modal, or otherwise touch real parking
  state directly — only `PendingBtActionStore.add(...)` and a plain
  `NotificationCompat`/`NotificationManagerCompat` notification. The *native*
  recording step must stay side-effect-free on parking data; the JS-side replay
  (`#reconcilePendingBtActions()`, Stage 6) is the only place a pending entry is
  allowed to turn into a real save/end.
- Confirm `js/app.js`'s `#reconcilePendingBtActions()` replays each entry by calling
  the real `#onBtConnected(label)` / `await #onBtDisconnected(label)` — the SAME
  handlers a live event uses — rather than a separate reimplementation that reads
  the pending entry's `action`/`vehicleId` fields to decide what to do directly. A
  regression toward "trust the recorded action" instead of "replay the event and
  let the live handler re-decide" would apply a decision based on settings that may
  be stale by the time the app resumes (e.g. the user turned `bluetoothAutoEnd` off
  after backgrounding) — the live handlers' own current-state checks are what make
  that safe.
- Confirm `#reconcilePendingBtActions()` processes entries in a sequential loop with
  `await` (not `Promise.all`/fire-and-forget-per-entry) — concurrent replays could
  race on `#state.activeVehicleId`/`#switchVehicle`, something real BT events never
  have to handle since they only ever arrive one at a time.
- Confirm `#reconcilePendingBtActions()` calls `clearPendingActions()` unconditionally
  after the loop (wrapped so a per-entry throw, caught individually, can't skip it)
  — a regression that only clears on full success would replay the same already-
  applied action again on every future app resume once any single entry ever throws.
- Confirm `#init()` calls `#reconcilePendingBtActions()` without `await`ing it (fire-
  and-forget with a `.catch()`) — it can involve a live GPS fetch
  (`#onBtDisconnected` → `#saveNewParking()`), and blocking the rest of app init on
  that would hang the loading screen.
- Confirm `core/PendingBtAction.kt` has zero `org.json`/Android framework imports
  (same purity requirement as the other `core` data classes) and that
  `PendingBtActionJson.kt`'s round-trip tests cover both a location fix present and
  absent (`lat`/`lng` are nullable — `AutoEnd` never has one, `AutoStart` does) —
  a regression that silently drops nullability here would make every recorded
  `AutoEnd` look identical to a location-less `AutoStart`, or vice versa, without
  any other test noticing.
- Confirm `ParkingForegroundService.maybeRecordPendingGpsSuggestion()` (Stage 7,
  GPS's counterpart to Stage 5) gates on `MainActivity.isForeground()`, NOT
  `MainActivity.getActiveWebView() != null` — a real, previously-shipped bug (see
  CLAUDE.md's "GPS suggestions require `MainActivity.isForeground()`, not
  `getActiveWebView()`"): `getActiveWebView()` stays non-null for the Activity's
  entire lifetime, including the whole backgrounded/paused window (screen off, app
  switched away, not destroyed) since `KeepRunning` keeps it alive without
  destroying it — but `navigator.geolocation.watchPosition()`, the live JS path
  this gate is meant to defer to, is a WebView-level API subject to Android's own
  background-location throttling tied to Activity *visibility*, not just process
  liveness. Gating on `getActiveWebView()` silently no-ops for the entire
  paused-but-alive window (the common "driving away with the screen off" case),
  producing no notification and no diagnostic-log entry at all until the user
  physically reopens the app — confirm `MainActivity.java` sets its `foreground`
  flag `true` in `onResume()` and `false` in `onPause()`, and that
  `getActiveWebView()`/`activeInstance` (still correct for `WidgetActionReceiver`'s
  headless widget actions, which only need the WebView to exist, not be visible)
  were NOT also changed to key off `isForeground()` — that would break headless
  widget actions while the app is legitimately backgrounded. Also confirm this
  method never itself opens
  `gpsEndModal` or calls `#resetParking`/any real-action method — only
  `PendingGpsSuggestionStore.set(...)` and `BackgroundAlertNotifier.show(...)`. GPS
  suggestions are never auto-performed even after this stage (unlike BT's
  `AutoEnd`/`AutoStart`) — `GpsDecisionEngine` only ever produces `SuggestEnd`, so
  there is no "real action" for this path to take beyond recording + notifying.
- Confirm `js/app.js`'s `#reconcilePendingGpsSuggestion()` replays by calling the
  real `#suggestGpsEnd()` (never `#resetParking()`/any auto-end call directly), and
  enforces BOTH idempotency checks itself before calling it: the recorded
  `vehicleId` must still equal the *current* `#state.activeVehicleId` (discard
  otherwise — `#suggestGpsEnd()` always acts on whichever vehicle is active *now*,
  so skipping this check could show a suggestion for the wrong vehicle), and
  `#state.current` must still be truthy (discard otherwise). Unlike Bluetooth's
  replay, `#suggestGpsEnd()` has no precondition checks of its own (its real
  callers `#checkGpsSpeed`/`#checkGpsDistance` already checked before calling it) —
  a regression that drops either check here is not caught by `#suggestGpsEnd()`
  itself.
- Confirm `core/PendingGpsSuggestion.kt` has zero `org.json`/Android framework
  imports, and `PendingGpsSuggestionJson.kt` round-trips both a real suggestion and
  `null` (it serializes to/from a single JSON value, not a list, since at most one
  GPS suggestion is ever outstanding — unlike `PendingBtActionJson`'s array, which
  can hold one per linked vehicle).
- Confirm `index.html`'s `diagLogCategoryFilter` has both `BT-PENDING` and
  `GPS-PENDING` options (a prior release shipped the `BT-PENDING` category without
  adding it to this dropdown — caught and fixed when `GPS-PENDING` was added; check
  it explicitly each release rather than assuming past coverage was complete).
- Confirm `BluetoothClassicPlugin.kt` and `ParkingForegroundService.kt` both call
  the shared `BackgroundAlertNotifier.show(...)` for their pending-action/
  -suggestion notifications, not separate hand-rolled `NotificationCompat` code —
  two independent implementations of the same channel-creation/permission-check
  logic would be easy to let drift (e.g. only one gets updated if the channel ID
  or POST_NOTIFICATIONS handling ever needs to change).
- Confirm `WidgetDataPlugin.update()`/`.clear()` (Stage 9) call
  `showParkingNotification(address)`/`cancelParkingNotification()`, and that
  `js/app.js`'s `#showParkingNotification`/`#cancelParkingNotification` both start
  with a `Capacitor.isNativePlatform()` guard that returns immediately — without
  it, saving a parking on native would show TWO "active parking" notifications
  (the JS Service-Worker one and the native one) instead of native cleanly
  replacing the PWA-only path. Also confirm the native version uses its own
  dedicated `findmycar_parking_active` channel (not reusing
  `BackgroundAlertNotifier`'s `findmycar_bt_alerts` channel, whose `DEFAULT`
  importance would make an alerting sound/heads-up for what should be a silent,
  persistent-style notification matching the JS version's `silent: true`).

## 5. Diagnostic log (Bluetooth/GPS/notifications)

Background BT/GPS/notification behavior is otherwise unobservable without a connected
device and `adb logcat` — the in-app diagnostic log is the only way a user can report
back what actually happened. It must stay wired on every release that touches that
pipeline (Bluetooth, GPS auto-end, or `Notify`):

- Confirm `js/diag-log.js` exists and exports `DiagLog` with `log`/`getAll`/`clear`/
  `formatText`.
- Confirm `DiagLog` is imported and called from `js/bluetooth.js`,
  `js/bluetooth-native.js`, `js/notify.js`, and `js/app.js` — grep `DiagLog.log(` in
  each; a file that stopped logging silently blinds that part of the pipeline without
  any test catching it.
- Confirm `index.html` still has `id="diagLogModal"`, `id="openDiagLogBtn"`,
  `id="diagLogContent"`, `id="diagLogVehicleFilter"`, `id="diagLogCategoryFilter"`,
  and the copy/export/clear/refresh buttons (`diagLogCopyBtn`/`diagLogExportBtn`/
  `diagLogClearBtn`/`diagLogRefreshBtn`) — and that `js/app.js` binds all of them
  (grep `Utils.el('diagLog`).
- Confirm `js/diag-log.js` is in `sw.js`'s `STATIC_ASSETS` and has a
  `<link rel="modulepreload">` in `index.html` (covered generically by section 2's
  checks, but call it out by name here since a miss would silently break the whole
  diagnostic feature rather than a cosmetic one).
- Confirm `android/.../BluetoothClassicPlugin.kt` still declares
  `isForegroundServiceRunning` and `js/bluetooth-native.js` still calls it after
  `startWatch()` — this is the one diagnostic that directly answers "is the thing that
  keeps BT/GPS alive in the background actually running," so a regression here silently
  removes the most useful signal for diagnosing background-detection reports.
- Confirm `index.html`'s `diagLogCategoryFilter` still has a `BT-SHADOW` option and
  `js/bluetooth-native.js` still listens for the native `btShadowDecision` plugin
  event and logs it under that category — this is the only way to compare native's
  Stage-2 shadow decisions against the real JS ones without adb; losing this listener
  wouldn't break anything else (shadow mode has no real effect) so no other check
  would catch it.

## 6. Headless widget actions

Widget actions (Quick Save's tap; Save/Swap/End in the "⋮" quick-actions popup) must
run without ever opening the app — a regression here silently falls back to opening
the app instead (still "works," just not headlessly), so nothing else catches it:

- Confirm `js/app.js` still exposes a PUBLIC (not `#`-private) `performWidgetAction`
  method on the `FindMyCarApp` class — grep `performWidgetAction(action, vehicleId)`
  with no `#` prefix. A private method here would silently break every widget action
  (native `evaluateJavascript()` can't reach a JS private class field), degrading them
  all to the app-opening fallback with no test to catch it.
- Confirm `android/.../WidgetActionReceiver.kt` exists, is registered in
  `AndroidManifest.xml` as `android:exported="false"`, and its `onReceive()` calls
  `MainActivity.getActiveWebView()` first — if reachable, runs `evaluateJavascript()`
  against it; if not (Stage 8 of the native migration), records a
  `PendingWidgetAction` to `PendingWidgetActionStore` and shows a `Toast` directly,
  rather than launching `MainActivity` (the pre-Stage-8 fallback — a regression back
  to that would still "work" from the user's perspective but silently lose the
  headless-when-killed behavior Stage 8 exists to provide, so verify by reading the
  code, not just checking the receiver exists).
- Confirm `js/app.js`'s `#reconcilePendingWidgetActions()` replays each entry via the
  real `performWidgetAction(a.action, a.vehicleId ?? null)` (not a separate
  reimplementation), processes them sequentially, and calls
  `WidgetBridge.clearPendingWidgetActions()` unconditionally after the loop (a
  per-entry throw is caught individually so it can't skip the clear) — a regression
  that only clears on full success would replay the same already-applied action
  again on every future app resume.
- Confirm `core/PendingWidgetAction.kt` has zero `org.json`/Android framework
  imports, and its `vehicleId` field is nullable (`QuickSaveWidgetProvider`'s
  main-tap "save" never sets one) — `PendingWidgetActionJson.kt`'s tests should
  cover both a present and a null `vehicleId` round-tripping correctly.
- Confirm `index.html`'s `diagLogCategoryFilter` includes `WIDGET` (a prior release
  shipped the `WIDGET` diagnostic-log category — used by both live and replayed
  widget actions — without ever adding it to this dropdown; check it explicitly
  rather than assuming past coverage was complete, same lesson as `BT-PENDING`).
- Confirm `AndroidManifest.xml`'s `.widgets.WidgetQuickActionsActivity` declares
  `android:taskAffinity=""`. Without it, this activity shares `MainActivity`'s
  default task affinity (neither activity declares one, so both fall back to the
  app's package name) — combined with the `FLAG_ACTIVITY_NEW_TASK` its launching
  `Intent` sets, Android would reuse/foreground any existing `MainActivity` task
  instead of creating an isolated one, so finishing the dialog reveals
  `MainActivity` underneath (looks exactly like the widget action "opened the
  app," a real bug this project shipped once already — see CLAUDE.md). This only
  reproduces once a `MainActivity` task already exists in recents (i.e. any time
  after the user has opened the app once), so a quick manual check right after a
  fresh install can miss it — read the manifest attribute directly rather than
  only testing on a pristine install.
- Confirm `performWidgetAction()` (js/app.js) calls `Notify.show('FindMyCar',
  message)` unconditionally in both its success and catch branches — not gated by
  `document.visibilityState` like `#notifyIfBackground()` — since a widget action
  never has an in-app UI open to show the result in. Because
  `#reconcilePendingWidgetActions()` replays through this same method, this one
  call also covers the "action replayed after the app was force-killed" case; a
  regression that adds a separate, non-reused notification call for the replay
  path instead would silently drift from the live-tap wording over time.
- Confirm `android/.../MainActivity.java` sets a static `activeInstance` (or
  equivalent) in `onCreate()`, clears it in `onDestroy()`, and registers
  `WidgetJsBridge` on the WebView as `"AndroidWidgetBridge"`.
- Confirm `android/.../WidgetJsBridge.kt` exists with an `@JavascriptInterface fun
  onResult` method — this is the only way a widget action's result reaches a Toast;
  losing the `@JavascriptInterface` annotation makes Android silently refuse to expose
  the method to JS (no compile error, no crash, the callback just never fires).
- Confirm `QuickSaveWidgetProvider.kt` targets `WidgetActionReceiver` via
  `PendingIntent.getBroadcast` (not `.getActivity` targeting `MainActivity`) for its
  main tap — that's the difference between headless and opening the app.
- Confirm `WidgetDataPlugin.kt` declares `syncVehicles` and `js/widget-bridge.js`
  calls it (`this.#plugin.syncVehicles?.(`) — this is what lets
  `WidgetQuickActionsActivity`'s vehicle picker show real vehicles instead of an
  empty list.
- Confirm all 3 widget layouts (`widget_active_parking.xml`, `widget_quick_save.xml`,
  `widget_mini_map.xml`) declare a `widget_quick_actions_btn` view, and all 3
  providers (`ActiveParkingWidgetProvider`, `QuickSaveWidgetProvider`,
  `MiniMapWidgetProvider`) wire it to `WidgetQuickActionsActivity` — the user asked
  for this to "work the same way" across every widget type, so a new widget or a
  layout change that drops this button is a regression even if the widget's primary
  function still works.

## 7. Multi-vehicle widget display

Both `ActiveParkingWidgetProvider` and `MiniMapWidgetProvider` must show more than
just the active vehicle when 2+ vehicles have simultaneously active parking — a
regression here silently reverts to only ever showing one vehicle, with the other
parked vehicle invisible until the user opens the app:

- Confirm `android/.../widgets/ParkedVehicles.kt` exists and its `parse()` filters to
  `hasParking=true` only, skipping entries with a blank `id` — this reads the same
  `syncVehicles()` mirror as `WidgetQuickActionsActivity`'s vehicle picker, so a
  broken filter would either hide parked vehicles or show ones with no active
  parking.
- Confirm `js/widget-bridge.js`'s `syncVehicles()` payload includes `address`/`lat`/
  `lng`/`timestamp` per vehicle (not just the BT/id/name/icon/hasParking fields
  `core`'s `BtDecisionEngine` needs) — `ParkedVehicles.parse()` depends on these; a
  regression that drops them wouldn't fail any test (the JSON still parses) but
  would silently render blank addresses/pins for every non-active parked vehicle.
- Confirm both `ActiveParkingWidgetProvider` and `MiniMapWidgetProvider` override
  `onAppWidgetOptionsChanged()` (not just `onUpdate()`) and call the same `updateOne()`
  — without it, an already-placed widget only re-renders for size on the next
  periodic/manual update, not live as the user drags it bigger/smaller.
- Confirm the `LARGE_MIN_HEIGHT_DP` thresholds (110 in `ActiveParkingWidgetProvider`,
  280 in `MiniMapWidgetProvider`) are read via
  `mgr.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)`
  in both providers — not `OPTION_APPWIDGET_MIN_WIDTH` or a hardcoded size, since
  these widgets grow vertically (extra row / stacked map), not horizontally.
- Confirm the single-parked-vehicle case renders identically to before this feature
  in both providers: `widget_row2`/`widget_cycle_btn` (active-parking widget) and
  `widget_mini_map_slot2`/`widget_mini_map_cycle_btn` (mini-map widget) are all
  `View.GONE` whenever `parked.size <= 1` — a regression that shows the cycle button
  or second row/slot with only one vehicle parked would be a visible cosmetic bug on
  the (still most common) single-vehicle case.
- Confirm the cycle button (`widget_cycle_btn`/`widget_mini_map_cycle_btn`) is shown
  only when `parked.size >= 2 && !isLarge`, and the second row/slot only when
  `parked.size >= 2 && isLarge` — these two conditions must be mutually exclusive
  (never both visible, never neither when 2+ are parked and the widget is below vs.
  at/above the threshold) in both providers.
- Confirm `android/.../widgets/WidgetCycleVehicleReceiver.kt` exists, is registered
  in `AndroidManifest.xml` as `android:exported="false"`, and its `onReceive()`
  advances the stored index with `(current + 1) % count` (guarding `count == 0`
  first) rather than plain `%` on a value that could go negative — Kotlin's `%` can
  return a negative remainder, which `List.get()` would throw on; `ActiveParkingWidgetProvider`/
  `MiniMapWidgetProvider` themselves read the stored index back with `.mod(parked.size)`
  (the non-negative variant) for the same reason.
- Confirm the selection index is persisted per `appWidgetId` (key pattern
  `"cycle_${widgetType}_$appWidgetId"`, via
  `WidgetCycleVehicleReceiver.selectedIndexKey()`) — not a single shared key — so two
  separate placed instances of the same widget type can independently show different
  vehicles.
- Confirm `PendingIntent` requestCodes for the new cycle buttons
  (`id + 400000` in `ActiveParkingWidgetProvider`, `id + 500000` in
  `MiniMapWidgetProvider`) don't collide with the existing root (`id`) or "⋮" button
  (`id + 200000`/`id + 300000`) requestCodes used by the same providers.

## 8. Cross-channel behavior parity

- Confirm `js/widget-bridge.js` and every `Capacitor.isNativePlatform()` /
  `window.Capacitor` branch in `js/app.js` is genuinely a no-op in the browser (no
  code path that throws or behaves differently for PWA users when `window.Capacitor`
  is `undefined`) — spot-read the guards, don't just grep for their existence.

## Output format

A markdown table per channel (check | status | detail), then:

**Overall: READY TO MERGE** — every check passed (SKIPPED items noted but not
blocking if they're environment-only, like Playwright needing a browser).

**Overall: NOT READY** — list exactly what to fix, in priority order.
