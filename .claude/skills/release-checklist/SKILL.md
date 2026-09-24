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

## 0. JS syntax (the gate that must never be skipped)

- Run `npm run check:syntax`. It must pass. This is first for a reason: v1.42.0
  shipped an unescaped apostrophe inside a Hebrew changelog string, which broke
  `js/config.js` and therefore the entire app (every module imports `CFG`), and
  **CI was green end to end** — `npm test` does not import config, `cap:sync`
  only copies files, and Gradle packaged the broken asset into a working APK
  build. A syntax error here is total: the app does not start.
- Confirm `.github/workflows/build-android.yml` still runs `npm run check:syntax`
  as its own step **before** the Android work, and that `npm test` still chains
  it. Losing either makes the outage above shippable again.

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
  - `.DailyStatusReceiver` (`<receiver>`, with a `BOOT_COMPLETED` `<intent-filter>`)
  - Permissions: `BLUETOOTH_CONNECT`, `FOREGROUND_SERVICE`,
    `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`,
    `ACCESS_FINE_LOCATION`, `CAMERA`, `RECORD_AUDIO`,
    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `RECEIVE_BOOT_COMPLETED` — a permission missing here
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
  "reset on every active=true call" would silently wipe the accumulated
  vehicle-speed evidence (`GpsDecisionState.speedAccumMs`) before it ever reaches
  `CFG.gpsSpeedDuration` — and, worse since v1.40.0, before it reaches
  `CFG.gpsVehicleEvidenceMs`, which the distance trigger now also depends on, so
  BOTH triggers would become permanently unable to fire without any test catching
  it (this logic lives in a `Service`, which the `core` package's unit tests can't
  reach).
- Confirm the six GPS constants are identical in both languages — `js/config.js`'s
  `gpsSpeedThreshold`/`gpsSpeedDuration`/`gpsVehicleEvidenceMs`/
  `gpsSpeedSampleCapMs`/`gpsDerivedSpeedMinIntervalMs`/`gpsEvidenceTtlMs`
  against
  `ParkingForegroundService.kt`'s `GPS_SPEED_THRESHOLD_MPS`/`GPS_SPEED_DURATION_MS`/
  `GPS_VEHICLE_EVIDENCE_MS`/`GPS_SPEED_SAMPLE_CAP_MS`/
  `GPS_DERIVED_SPEED_MIN_INTERVAL_MS`/`GPS_EVIDENCE_TTL_MS`.
  **`gpsSpeedThreshold` must be 7 m/s and must not creep upward** — see
  CLAUDE.md's v1.42.0 evidence: a 13.9 m/s bar disarmed the distance trigger
  for an entire real drive, because city traffic averages well under it. There
  is no shared source between JS and
  Kotlin, and a drift here is invisible: both sides keep working, they just decide
  differently depending on whether the app happened to be open — the hardest kind
  of report to diagnose, since it reproduces only in one of the two states.
- Confirm `js/app.js`'s `#checkGpsDistance()` still gates on
  `this.#state.gpsSpeedAccumMs >= CFG.gpsVehicleEvidenceMs`, and
  `GpsDecisionEngine.checkDistance()` on its `vehicleEvidenceMs` parameter. This is
  the v1.40.0 walking-false-positive fix (see CLAUDE.md "Vehicle-movement
  detection") — distance alone says how far, never how. A regression removes no
  functionality and breaks no test on either side; it just starts telling people
  out for a walk that their car has moved.
- Confirm `js/app.js` resets GPS detection state ONLY through
  `#resetGpsDetection()` (grep for direct `gpsSpeedAccumMs`/`gpsLastSpeedSampleAt`/
  `gpsPrevFix`/`gpsLastAboveAt` assignments outside it and
  `#checkGpsSpeed`/`#effectiveSpeed`) — the
  five fields are only meaningful relative to one another, and a call site that
  reset a subset would carry the previous session's evidence into a new parking,
  which is exactly what the distance trigger's gate relies on not happening.
- Confirm `GpsDecisionEngine.checkSpeed()` (and `js/app.js`'s `#checkGpsSpeed()`)
  still check evidence expiry BEFORE the unknown-speed early return — deliberate,
  and it looks like a tidy-up: moving it below that return leaves stale evidence
  alive forever on a device that rarely reports speed.
- Confirm neither `checkSpeed` implementation has regrown a distance/"departure
  radius" gate on accumulation. v1.41.0 had one and v1.42.0 removed it after a
  real drive proved it would have disarmed detection permanently (CLAUDE.md).
  The general rule it left behind: prefer a detection fix that degrades to
  "fires more often than ideal" over one that can degrade to "never fires".
- Confirm `WidgetActionReceiver.queueForReplay()` AND
  `BtPendingActionRecorder.record()` both call
  `WidgetMirror.applyQueuedAction(...)`. A queued action is guaranteed to
  happen, so a widget that keeps showing the state it already changed — for
  as long as it takes the user to next open the app — is simply a wrong
  display. Both paths had this; fixing only the reported one leaves the
  other. Confirm suggestions (GPS drive-away, walk-away) do NOT call it:
  they change no state until answered.
- Confirm `WidgetMirror` never invents an address (an optimistic save writes a
  BLANK address plus `LastKnownLocation`, matching what js/app.js writes before
  geocoding resolves), and that it PATCHES the `vehicles_json` entry rather
  than rebuilding it — native does not know the BT/daily-status/walk-away
  fields in there and would silently drop them.
- Confirm `KEY_PENDING_SYNC_AT` is cleared by all three JS-driven writes
  (`syncVehicles`/`update`/`clear`) and rendered as ⏳ by
  `ActiveParkingWidgetProvider`. A marker that is set but never cleared would
  leave every widget permanently claiming to be out of date.
- Confirm `ACTION_REFRESH` is handled BEFORE the ack-or-queue path in
  `WidgetActionReceiver.onReceive()` and never reaches `queueForReplay()` —
  replaying a refresh is meaningless, and queueing one would make the ↻ button
  report "יבוצע כשהאפליקציה תיפתח מחדש" for something it just did. Confirm
  `performWidgetAction('refresh')` is the ONE action exempt from the otherwise
  unconditional `Notify.show()`.
- Confirm `BackgroundAlertNotifier`'s channel is `IMPORTANCE_HIGH` with
  `PRIORITY_HIGH`, and `js/notify.js` passes a `channelId` created with
  `importance: 4`. DEFAULT only makes a sound — it does NOT produce the
  heads-up banner these confirmation prompts exist to deliver.
- **If a channel's importance is ever changed again, its id must change too.**
  Importance is fixed at channel creation and `createNotificationChannel`
  silently ignores a raise on an existing id, so editing the constant alone
  fixes nothing for anyone who already has the app installed — which is the
  entire population who would report it. Confirm the old id is deleted, and
  that both the Kotlin and JS constants name the SAME channel.
- Confirm `WidgetActionReceiver` never calls `evaluateJavascript(script, null)`
  — it must pass a callback, and every path that cannot deliver live (no
  WebView, `FMC_NOT_READY`, the `ACK_TIMEOUT_MS` timeout, a thrown
  `evaluateJavascript`) must go through `queueForReplay()`. This was a real,
  previously-shipped silent black hole: a widget tap that was neither performed
  nor queued nor logged anywhere. Also confirm the live and queued paths are
  mutually exclusive via the `AtomicBoolean`, or an action can be double-applied.
- Confirm `performWidgetAction()` still has its `CFG.widgetActionDedupeMs`
  guard and that it clears `#lastWidgetAction` in the catch branch — without
  that clear, one failed action would suppress the user's retry for 3 seconds.
- Confirm `ParkingForegroundService.shouldWatchLocation()` is
  `isParkingReasonActive() || PendingParkingSuggestionStore.getWindow(...) != null`
  and that every start/stop goes through `refreshLocationWatch()` rather than
  `updateLocationWatch(isParkingReasonActive())`. The GPS end-suggestion and the
  walk-away window are two independent consumers of one watch; a regression to
  the parking-only predicate silently kills walk-away detection, and a
  walk-away-only one would kill drive-away detection.
- Confirm `onBecameEligibleForLocationType()` also gates on
  `shouldWatchLocation()`, NOT `isParkingReasonActive()` — v1.43.0 unified the
  watch predicate but left this one behind, and v1.43.1 fixed it. This is the
  only path that can obtain real background-location capability (a foreground
  restart), and the walk-away window runs by definition while there is NO
  parking, so a parking-only gate means walk-away detection can never acquire
  it after a reboot or an app update: it simply never fires, while the service
  looks alive and the watch reports "started". Every signal reads healthy —
  the same failure documented three times over in CLAUDE.md, reached by the
  other consumer.
- Confirm the GPS decision state is reset in `onParkingActiveChanged()`, not
  only inside `updateLocationWatch()` — when the watch is already running for a
  walk-away window, `updateLocationWatch(true)` returns early and would carry
  the previous session's evidence into the new parking.
- Confirm `js/app.js`'s `walkAwayConfirmBtn` handler uses `this.#ui.closeModal()`
  and NOT `this.#closeModal()`. The latter clears the pending suggestion (right
  for a dismissal) and would delete the recorded disconnect location before
  `saveAt` reads it — the parking would then be saved at the user's current
  position, which is the one outcome this feature exists to avoid.
- Confirm `WalkAwayDetector.maybeOpenWindow()` logs a `WALK` entry on EVERY
  path, including each decline (master switch off / no vehicle linked to that
  label / per-vehicle opt-in off / auto-start on / already parked). A silent
  return makes "ran and correctly decided there was nothing to do"
  indistinguishable from "never ran" — the ambiguous silence the whole
  diagnostic log exists to eliminate, and the reason one report could not be
  answered from a log at all.
- Confirm `WalkAwayDetector.eligible()` still requires all of `walkAwaySuggest`,
  `!bluetoothAutoStart` and `!hasParking` — dropping the auto-start check makes
  the app ask about a parking it already saved.
- Confirm the six walk constants match between `js/config.js`
  (`walkMinSpeed`/`walkMaxSpeed`/`walkAbortSpeed`/`walkRequiredMs`/
  `walkMinDisplacement`/`walkWindowMs`) and `ParkingForegroundService.kt`
  (`WALK_*`), same hand-kept parity as the GPS constants.
- Confirm `js/widget-bridge.js`'s `syncVehicles()` call includes
  `gpsAutoEndEnabled` at the top level (not per-vehicle) — `GpsDecisionEngine`'s
  shadow evaluation reads it from `WidgetDataPlugin`'s `KEY_GPS_AUTO_END_ENABLED`;
  a regression here would silently make every GPS shadow decision evaluate as if
  the setting were off, masking whatever the real JS setting actually is.
- Confirm `index.html`'s `diagLogCategoryFilter` has a `GPS-SHADOW` option and
  `js/widget-bridge.js`'s `initShadowListener()` is called once from `js/app.js`'s
  `#init()` — losing this wiring has no other symptom (shadow mode has no real
  effect), so nothing else would catch it.
- Confirm `BtPendingActionRecorder.maybeRecord()` (Stage 5; moved out of
  `BluetoothClassicPlugin` — see CLAUDE.md "Resolved: real, previously-shipped bug")
  still gates on `MainActivity.getActiveWebView() != null` and returns immediately
  when the WebView IS reachable — this is what prevents a real BT event from
  producing BOTH the normal live JS-handled action AND a recorded pending action,
  which would otherwise double-apply the same auto-end/auto-start once a later stage
  starts replaying pending actions. This is a correctness bug with no test coverage
  (needs a live `Context` with real `SharedPreferences`, same precedent as the rest
  of this migration's Service/Plugin wiring) — verify by reading the code, not just
  grepping for the guard's existence.
- Confirm `ParkingForegroundService`'s BT receiver calls
  `BtPendingActionRecorder.maybeRecord(context, label, connected)` directly and
  unconditionally, alongside (not instead of) `BtEventBus.emitConnected/
  emitDisconnected()` — NOT gated behind whether a `BtEventBus` listener currently
  exists. A regression back to only calling it from `BluetoothClassicPlugin.onConnected/
  onDisconnected` (i.e. only when a live Activity-bound Plugin instance happens to be
  registered) would silently reintroduce the exact bug this fix resolved: that
  listener is torn down precisely when the Activity is destroyed, which is the one
  scenario Stage 5 exists to handle — so recording would again only ever succeed
  while the WebView IS reachable, when it's needed least.
- Confirm `BluetoothClassicPlugin.handleOnDestroy()` calls `BtEventBus.removeListener(this)`
  but does **NOT** call `ParkingForegroundService.setReasonActive(context, "bluetooth",
  false)` — a real, previously-shipped bug: clearing the "bluetooth" reason here fires
  on every routine Activity destruction (not just when the user disables BT), which
  — whenever no parking was also active — stopped the ENTIRE foreground service
  (receiver included) until the app was next reopened, producing a total background-
  detection outage. The reason must only ever be cleared by an explicit `stopWatch()`
  call (the user turning off the BT master switch in Settings).
- Confirm `maybeRecord`/`record` (in `BtPendingActionRecorder`) never call
  `WidgetDataPlugin.update`/`.clear`, open any modal, or otherwise touch real parking
  state directly — only `PendingBtActionStore.add(...)` and a plain
  `NotificationCompat`/`NotificationManagerCompat` notification. The *native*
  recording step must stay side-effect-free on parking data; the JS-side replay
  (`#reconcilePendingBtActions()`, Stage 6) is the only place a pending entry is
  allowed to turn into a real save/end.
- Confirm `BtPendingActionRecorder.maybeRecord()` gates on
  `MainActivity.isForeground()`, NOT `getActiveWebView() != null`. A real,
  previously-shipped bug (v1.45.0): a Capacitor event is pushed rather than
  polled, but its DELIVERY waits for the WebView's JS engine to resume, so with
  the Activity alive-but-paused the recorder deferred to a live path that was
  frozen. The disconnect was handled 20+ minutes late, one second after the app
  was opened, and auto-start saved the parking at the user's position instead of
  the car's. CLAUDE.md previously asserted Bluetooth was immune to this; it is
  not.
- Confirm `emitAndTrack()` puts `at` (System.currentTimeMillis()) on both the
  `connected` and `disconnected` payloads, and that `#onBtDisconnected` refuses
  to auto-start past `CFG.btEventMaxAgeMs` when it has NO `presetLoc` — and
  surfaces that refusal (toast), never silently. Without the timestamp the live
  handler cannot tell fresh news from thawed news, which is the whole bug.
- Confirm `#reconcilePendingBtActions()` passes the recorded `lat`/`lng` as
  `presetLoc` into `#onBtDisconnected`. The recorded location must NOT go back
  to being informational-only: re-deriving the decision from current settings is
  right, re-taking the LOCATION at replay time is not — by then it describes the
  user, not the car.
- Confirm `#reconcilePendingBtActions()` is called from `visibilitychange` as
  well as `#init()`, and is guarded against overlapping runs (`#reconcilingBt`).
  The Activity usually survives the app being closed, so a resume is not a cold
  start and a pending action would otherwise wait for one.
- Confirm every `getCurrentPosition` error path reports the actual
  `PositionError.code` via `FindMyCarApp.describeGeoError()` — never a single
  "denied or unavailable" for all three. A revoked permission, no fix, and a
  timeout need completely different actions from the user, and collapsing them
  left one real report impossible to answer from its log.
- Confirm `#saveNewParking`/`#swapParking` call `#reportLocationFailure()`
  instead of a bare toast when no location can be had, and that it checks the
  NATIVE permission (`OemSetup.status().locationGranted`) rather than trusting
  the browser error code alone — sending someone to app settings when the real
  problem is being indoors (or vice versa) is worse than saying nothing.
  Confirm it also fires `Notify.show(...)`: on a Bluetooth auto-start there is
  no screen for a toast, so without it the save fails completely silently.
- Confirm `#getCurrentLocation()` retries once with relaxed options
  (`enableHighAccuracy: false`, a large `maximumAge`) after a precise attempt
  fails, and does NOT retry when `err.code === 1` (permission denied) — a
  second attempt cannot help there and only delays telling the truth.
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

## 4b. Addresses (v1.47.0)

- Confirm no call site in `js/app.js` runs a one-shot geocode any more: grep
  `reverseGeocode` — every parking-address lookup must go through
  `#resolveAddress(vehicleId, parkingId)`. A single attempt from a backgrounded
  page (widget, Bluetooth) routinely fails, and a one-shot call left those
  parkings as bare coordinates forever — the reported bug.
- Confirm `reverseGeocodeDetailed()` still distinguishes `ok`/`none`/`failed`,
  treats a body with neither `address` nor `error` as `failed`, and that
  `tests/unit/geocoder.test.js` covers all of them. Collapsing `failed` into
  `none` marks a parking address-less permanently.
- Confirm `sw.js`'s Nominatim branch answers a network failure with a non-2xx
  status (503), never a synthetic 200 — a 200 `{}` is indistinguishable from
  "no address here".
- Confirm `#fillMissingAddresses()` is called from `#init()`, from
  `visibilitychange` (visible) and from the `online` handler, and that it spaces
  requests >= 1s apart (Nominatim usage policy).
- Confirm `#updateCurrentLocation()` deletes `addressLookup` before re-resolving,
  and `#applyAddress()` refuses to apply when the parking's coordinates changed
  mid-lookup — otherwise a moved parking keeps the old spot's address or its
  old "no address" marker.

## 4d. Bluetooth connect question (v1.49.0)

- Confirm `BtPendingActionRecorder.maybeRecord()`'s connected branch handles
  `BtConnectDecision.SuggestEnd` by calling `suggestEnd()` (a notification with
  `end`/`dismiss` buttons) and records nothing for it. Dropping it back to
  "AutoEnd only" makes the question appear only when the page's JS is awake.
- Confirm `#onBtConnected` does not call `#notifyIfBackground` on native (both
  the auto-end and the suggest branch) — native already notified, and JS doing
  it too produces a duplicate every time the page is alive in the background.
- Confirm a connect older than `CFG.btEventMaxAgeMs` on native does NOT open
  `btParkingModal`, and that `js/bluetooth-native.js` passes `at` for
  `connected` (not only `disconnected`).
- Confirm `performWidgetAction('end')` closes `gpsEndModal` and, when it is
  about the same vehicle, `btParkingModal`.

- Confirm `#notifyIfBackground()` returns immediately on native. Every caller
  is a background BT/GPS event that native already notifies; a JS notification
  there duplicates it, or arrives late when a frozen page thaws.
- Confirm the GPS and walk-away notifications use `ACTION_DISMISS_GPS` /
  `ACTION_DISMISS_WALK` (not plain `ACTION_DISMISS`) and that
  `WidgetActionReceiver` clears `PendingGpsSuggestionStore` /
  `PendingParkingSuggestionStore` for them. Plain dismiss leaves the stored
  question, and the app re-asks it on next open.
- Confirm every `WidgetMirror.applyQueuedAction(...)` caller that knows the real
  spot passes it (`atLat`/`atLng`): the BT disconnect fix, the walk-away
  suggestion's spot, the widget tap fix. And confirm the mirror then calls
  `NativeGeocoder.resolveForMirror`, which must no-op once
  `WidgetMirror.hasPendingSync()` is false (JS owns the address after a sync).
- Confirm `core/NominatimAddressJson` applies the same rules as
  `js/geocoder.js` (street|locality required; empty body = Failed) and that
  `NominatimAddressJsonTest` mirrors `tests/unit/geocoder.test.js`.

## 4c. Queued widget saves (v1.48.0)

- Confirm `WidgetActionReceiver.queueForReplay()` records
  `LastKnownLocation.getFix()` onto the `PendingWidgetAction` for `save` and
  `swap`, and that `PendingWidgetActionJsonTest` covers both a recorded fix and
  an old entry without one.
- Confirm `#reconcilePendingWidgetActions()` routes save/swap through
  `#tapLocation()` and passes `{ presetLoc }` into `performWidgetAction()`, and
  that `#tapLocation()` REFUSES (returns false, toast + Notify) rather than
  falling back to a live fix when the tap is old and no fresh fix was recorded.
  A live fix at replay time is where the app was opened, not where the car is.
- Confirm `#reconcilePendingWidgetActions()` is called from `visibilitychange`
  as well as `#init()`, guarded by `#reconcilingWidget`.
- Confirm the live script in `WidgetActionReceiver` passes `{ tappedAt }` and
  that `performWidgetAction()` returns null for a delivery older than
  `CFG.widgetAckTimeoutMs`. Confirm `CFG.widgetAckTimeoutMs` equals
  `WidgetActionReceiver.ACK_TIMEOUT_MS` (2500) — hand-kept JS/Kotlin parity. If
  they drift apart, either a thawed delivery saves at the wrong spot or a
  legitimate slow delivery is silently dropped.

## 5. Diagnostic log (Bluetooth/GPS/notifications)

Background BT/GPS/notification behavior is otherwise unobservable without a connected
device and `adb logcat` — the in-app diagnostic log is the only way a user can report
back what actually happened. It must stay wired on every release that touches that
pipeline (Bluetooth, GPS auto-end, or `Notify`):

- Confirm `#reconcileNativeLog()` runs from `#init()`, on `visibilitychange`
  (visible), and when the diagnostic-log modal opens or is refreshed, guarded by
  `#mergingNativeLog`, and that `#filteredDiagLogEntries()` sorts by `t` (not
  insertion order). A real report's log showed no native line for an entire
  morning because the merge only ran on a cold start — whether a Bluetooth
  disconnect was even received was unanswerable.
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
- Confirm `index.html`'s `diagLogCategoryFilter` has a `SERVICE` option (native
  background service lifecycle — see CLAUDE.md "Native background service log").
- Confirm `NativeLogStore.add(context, tag, category, message)` is called alongside
  (not instead of) the existing `Log.i`/`Log.w`/`Log.e` calls in
  `ParkingForegroundService.kt`, with `category = "SERVICE"`, at: `onCreate()` success
  and failure, `onDestroy()`, BT receiver registration, every raw ACL broadcast
  received (including the no-readable-device-name-dropped case — this must log
  regardless of whether a vehicle is linked to that label or the WebView is
  reachable, unlike `BT`/`BT-PENDING`, which only fire once something is actually
  decided), and GPS watch start/stop/every distinct failure-to-start reason (no
  permission / no `LocationManager` / no enabled provider) in `updateLocationWatch()`.
  Confirm it is NOT called from `onLocationShadow()` per individual location update —
  that would blow through the 200-entry cap in minutes on a single drive and push out
  the more valuable lifecycle transitions; `GPS-SHADOW` already covers the meaningful
  per-threshold-crossing level.
- Confirm `NativeLogStore.add(...)` with `category = "BRIDGE"` is called as the first
  line of every `@PluginMethod` in `BluetoothClassicPlugin.kt` (all 12:
  `requestBtPermission`, `permissionStatus`, `isForegroundServiceRunning`,
  `openAppSettings`, `batteryOptimizationStatus`, `requestIgnoreBatteryOptimizations`,
  `startWatch`, `stopWatch`, `checkNow`, `getPendingActions`, `clearPendingActions`,
  `getBondedDevices`) and `WidgetDataPlugin.kt` (7 of its 9: `syncVehicles`, `update`,
  `clear`, `getPendingGpsSuggestion`, `clearPendingGpsSuggestion`,
  `getPendingWidgetActions`, `clearPendingWidgetActions`) — proving every JS→native
  call actually reached native, regardless of whether it then succeeds. Confirm
  `getNativeLog`/`clearNativeLog` themselves are deliberately NOT instrumented — doing
  so would be self-referential noise (every app open would log two meaningless
  entries about having read/cleared the log that already contains them).
- Confirm `NativeLogStore.add(...)` with `category = "BRIDGE"` precedes all 3
  `notifyListeners(...)` call sites (`BluetoothClassicPlugin.kt`'s `emitAndTrack()` —
  `connected`/`disconnected` — and `runShadowDecision()` — `btShadowDecision`;
  `WidgetDataPlugin.kt`'s `onGpsShadowDecision()` — `gpsShadowDecision`) — proving
  native attempted every send to JS regardless of whether the WebView was actually
  there to receive it.
- Confirm `index.html`'s `diagLogCategoryFilter` has a `BRIDGE` option (native<->JS
  Capacitor plugin message-bus traffic — see CLAUDE.md "Native<->JS message bus
  log"), distinct from `SERVICE`.
- Confirm `core/NativeLogEntry.kt`'s `category` field round-trips correctly through
  `NativeLogEntryJson.kt` (both `"SERVICE"` and `"BRIDGE"` values, and a
  missing-`category` JSON payload defaulting to `"SERVICE"` for forward
  compatibility) — `NativeLogEntryJsonTest.kt` should cover all three cases.
- Confirm `WidgetDataPlugin.getNativeLog()`/`.clearNativeLog()` exist and return/
  clear `NativeLogStore`'s entries via the tested `NativeLogEntryJson` (de)serializer
  — not a hand-rolled `JSObject` built directly from `NativeLogEntry` fields.
- Confirm `js/app.js`'s `#reconcileNativeLog()` is **awaited** (not fire-and-forget
  like the other `#reconcilePending*()` calls) and runs as the very first thing in
  `#init()`, before any other `DiagLog` entry this session — `DiagLog` stores entries
  in insertion order and only reverses for display at render time, it does not sort
  by timestamp, so calling this late (or fire-and-forget, racing with other init
  logging) would silently scramble the chronological order these merged historical
  entries are supposed to establish, defeating the whole point of the feature.
- Confirm `#reconcileNativeLog()` files each entry under its OWN `e.category` (not a
  single hardcoded `'SERVICE'`) and passes its own `timestamp` as `DiagLog.log`'s 4th
  argument (`t`), not `Date.now()` — the displayed time must be when the native event
  actually happened (while the app was closed), not when it was read on this resume;
  a regression back to the default `Date.now()` or a hardcoded category wouldn't fail
  any test (the entries would still appear, just all stamped with the reopen time or
  filed under the wrong category) but would silently defeat the "know with certainty
  what happened and when" purpose this feature exists for.
- Confirm `js/diag-log.js`'s `DiagLog.log(category, message, meta, t)` still accepts
  that optional 4th `t` argument and falls back to `Date.now()` only when it's
  omitted/`null` — losing this parameter breaks `#reconcileNativeLog()` silently (no
  error, entries just all show the wrong time).

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
- Confirm every `reverseGeocode(...).then(addr => ...)` completion callback in
  `js/app.js` (`#geocodeCurrentParking`, `#saveNewParking`, `#swapParking`,
  `#updateCurrentLocation`) calls `this.#syncUI()`, not just
  `this.#ui.updateAddress(...)` — a real, previously-shipped bug (see CLAUDE.md
  "Every real parking-state mutation must call `#syncUI()`"): geocoding is async and
  resolves seconds after the initial save/sync (which correctly goes out with
  `address: null`), so without a second sync on completion the widgets and the
  native Stage 9 notification get stuck showing the "מיקום נשמר" placeholder
  forever, even though the in-app UI itself updates fine (that's why this class of
  bug is easy to miss in manual testing — it looks correct from inside the app).
- Confirm `#clearVehicleParking(vehicleId)` (js/app.js) calls `this.#syncUI()`
  unconditionally, not only inside its `if (isActive)` branch — another real,
  previously-shipped bug: this is the path a Bluetooth auto-end takes for a vehicle
  that ISN'T the currently-active one, and without an unconditional sync, ending
  that vehicle's parking updates storage/history correctly but never tells the
  widgets or native notification, so a non-active vehicle keeps showing as parked
  indefinitely after Bluetooth actually ended it.
- More generally: grep every `VehicleController.setCurrent(`/`.removeCurrent(` call
  in `js/app.js` and confirm each one is followed, in the same function (not a
  different, unrelated code path), by a `this.#syncUI()` call — there is no
  compile-time or lint-time enforcement of this, so a new feature can silently
  reintroduce the same class of bug (no test failure, no crash — the widget just
  quietly stops updating for that one code path).

## 9. Unified log: source prefixes, dual timestamps, heartbeats

Every `DiagLog` entry must be attributable to the process that wrote it, show when it
actually happened vs. when it was recorded, and both the native service and the web
app must prove continuous liveness — a regression in any of these degrades the
diagnostic log back to "silence is ambiguous" without any other test catching it:

- Confirm `js/diag-log.js`'s `DiagLog.log(category, message, meta, t, source)` accepts
  a 5th `source` parameter defaulting to `'WEB'`, and pushes both `loggedAt:
  Date.now()` and `source` onto every stored entry — grep the signature and the
  `entries.push(...)` call.
- Confirm `formatText()` renders a `[source]` prefix (falling back to `'WEB'` for any
  older stored entry with no `source` field) ahead of the category on every line, and
  appends the "נרשם בפועל ב-..." suffix only when `Math.abs(e.loggedAt - e.t) > 2000`
  — not unconditionally (that would make every live entry noisy) and not never (that
  would silently drop the one signal this dual-timestamp exists to surface).
- Confirm `js/app.js`'s `#reconcileNativeLog()` passes `e.tag` (not a hardcoded
  string) as `DiagLog.log`'s 5th `source` argument — a regression to a hardcoded
  `'native'` would still show a prefix, but it would no longer distinguish
  `FMC-FgService` from `FMC-BtPlugin`/`FMC-WidgetData` entries from each other.
- Confirm no other `DiagLog.log(...)` call site in the codebase passes an explicit
  `source` argument — every live call should rely on the `'WEB'` default; an
  unnecessary explicit `'WEB'` isn't wrong but is a sign someone copy-pasted from
  `#reconcileNativeLog()` without understanding the default already covers it.
- Confirm `js/config.js` declares `CFG.diagHeartbeatIntervalMs` (5 minutes =
  `5 * 60 * 1000`) and `js/app.js`'s `#startDiagHeartbeat()` (a) logs once
  immediately, not only after the first interval elapses, (b) uses `setInterval`
  (not a one-shot `setTimeout`), and (c) is called exactly once from `#init()`.
  Logging immediately matters because a session that opens and closes again well
  within the first 5 minutes would otherwise show zero heartbeat evidence at all.
- Confirm `ParkingForegroundService.kt` declares a `HEARTBEAT_INTERVAL_MS` companion
  constant equal to `CFG.diagHeartbeatIntervalMs` (both 5 minutes) — there is no
  shared constant source between JS and Kotlin (same precedent as the GPS threshold
  constants), so this can only be checked by reading both values and diffing them by
  hand every release.
- Confirm `startHeartbeat()`/`stopHeartbeat()` are wired into `onCreate()`'s success
  path (after the existing "onCreate succeeded" log) and `onDestroy()` (after the
  existing "onDestroy" log) respectively, and that `startHeartbeat()` calls
  `stopHeartbeat()` first (idempotent — never double-schedules if called twice, e.g.
  by a future code path that re-enters `onCreate()`-adjacent logic).
- Confirm the native heartbeat logs via `NativeLogStore.add(context, TAG, "SERVICE",
  ...)` — reusing the existing `SERVICE` category, not a new one — since a heartbeat
  is just another fact about the same "is the background machinery alive" question
  `SERVICE` already answers; no new `diagLogCategoryFilter` option should exist for
  this feature.
- Confirm `BluetoothClassicPlugin.kt` and `WidgetDataPlugin.kt` do NOT gain their own
  heartbeat — per CLAUDE.md's "Heartbeats" section, they have no independent
  persistent background loop of their own (only `ParkingForegroundService` and the
  web app's JS engine do), so a heartbeat added to either plugin class would be
  meaningless noise, not a real liveness signal.
- Confirm `ParkingForegroundService.kt`'s `startHeartbeat()`/`scheduleNextHeartbeat()`
  use `AlarmManager.setExactAndAllowWhileIdle` (guarded to API 23+, falling back to
  plain `AlarmManager.set()` pre-M) — NOT `Handler.postDelayed`/`Handler(Looper...)`.
  A real, previously-shipped bug (see CLAUDE.md "the native heartbeat needs
  `AlarmManager`, not a `Handler`"): a plain `Handler` timer has no wake source, so
  once the device enters Doze it only fires whenever the CPU happens to wake for some
  unrelated reason — production diagnostic-log evidence showed 7-21+ minute gaps
  instead of a steady 5-minute cadence. Grep the file for `Handler(` / `postDelayed` —
  neither should appear anywhere in this class after the fix. Also confirm
  `stopHeartbeat()` unregisters the dynamic `BroadcastReceiver` AND cancels the
  pending `AlarmManager` alarm (both, not just one) — leaking either would either keep
  firing heartbeats after the service should have stopped, or throw on
  `unregisterReceiver` the next time `startHeartbeat()` tries to register a fresh one.

## 10. Cross-channel behavior parity

- Confirm `js/widget-bridge.js` and every `Capacitor.isNativePlatform()` /
  `window.Capacitor` branch in `js/app.js` is genuinely a no-op in the browser (no
  code path that throws or behaves differently for PWA users when `window.Capacitor`
  is `undefined`) — spot-read the guards, don't just grep for their existence.

## 11. Once-daily status notification

A user-facing system notification, once a day, reporting per-vehicle parked/not-
parked status — added specifically so background detection health is visible without
opening the app or reading the diagnostic log. A regression here is easy to miss
because it only manifests roughly once every 24 hours:

- Confirm `js/config.js` declares `CFG.keys.dailyStatus` (`fmc_daily_status_v1`) and
  `js/vehicles.js`'s `add()`/`update()` both accept and persist a `dailyStatusEnabled`
  parameter, defaulting to `true` — including in `update()`'s backward-compat default
  block (`...vehicles[idx]` spread) for vehicles created before this field existed,
  matching the established pattern for `bluetoothAutoEnd`/etc.
- Confirm `index.html` has a `dailyStatusToggle` checkbox in Settings (global master
  switch) and a `vehicleDailyStatusToggle` checkbox in the vehicle edit modal
  (per-vehicle), and that `js/app.js`'s `#init()` sets the global toggle's `checked`
  from `#getDailyStatusSettings().enabled` while `js/ui.js`'s
  `populateVehicleModal()`/`getVehicleModalValues()` read/write the per-vehicle one —
  a regression that drops either wiring silently reverts to whatever the checkbox's
  static HTML `checked` attribute says, not the vehicle's actual stored value.
- Confirm toggling `dailyStatusToggle` calls `this.#syncUI()` immediately (not just
  `Store.set(...)`) — without it, the native alarm only picks up the change on the
  next unrelated parking-state sync, not right away.
- Confirm `js/widget-bridge.js`'s `syncVehicles()` payload includes
  `dailyStatusNotificationEnabled` at the top level (read fresh via `Store.get`, not
  cached from `state`) and `dailyStatusEnabled` per vehicle — a regression that drops
  either wouldn't fail any test (the JSON still parses) but would silently make the
  native side always believe the feature is off, or ignore per-vehicle opt-outs.
- Confirm `WidgetDataPlugin.syncVehicles()` reads `dailyStatusNotificationEnabled`,
  persists it under `KEY_DAILY_STATUS_ENABLED`, and calls
  `DailyStatusScheduler.scheduleOrCancel(context, dailyStatusEnabled)` — every single
  call, not conditionally — this is the ONLY place that keeps the alarm in sync with
  the setting (there's no separate "settings changed" plugin method), so a regression
  here means toggling the setting has no real effect until some other code path
  happens to call it.
- Confirm `DailyStatusScheduler.scheduleNext()`/`cancel()` use
  `AlarmManager.setExactAndAllowWhileIdle` (API 23+, falling back to `set()` pre-M) —
  same Doze-awareness requirement and same real-bug precedent as the heartbeat fix
  above; grep for `Handler(`/`postDelayed` — neither should appear in this file or in
  `DailyStatusReceiver.kt`.
- Confirm `DailyStatusReceiver` is registered in `AndroidManifest.xml` as a
  **manifest-declared** `<receiver>` (not dynamically registered like
  `ParkingForegroundService`'s BT ACL receiver) with an `<intent-filter>` for
  `android.intent.action.BOOT_COMPLETED`, and that
  `android.permission.RECEIVE_BOOT_COMPLETED` is declared — without manifest
  registration, `BOOT_COMPLETED` can never reach it at all, and the feature would
  silently stop working after every device reboot until the user happened to reopen
  the app (which is the only other place anything re-arms the alarm).
- Confirm `DailyStatusReceiver.onReceive()`'s `BOOT_COMPLETED` branch only
  reschedules (`DailyStatusScheduler.scheduleNext()`) when the stored
  `KEY_DAILY_STATUS_ENABLED` is true, and never fires the notification itself on
  boot — a regression that unconditionally reschedules (or fires) on every boot would
  re-enable a feature the user had explicitly turned off, or show a notification
  immediately after every restart regardless of the target hour.
- Confirm `DailyStatusReceiver`'s `ACTION_DAILY_STATUS` branch calls
  `DailyStatusScheduler.scheduleNext()` (for tomorrow) **unconditionally** — including
  when `showStatusNotification()` throws — wrapped so a single transient failure
  (e.g. a `SharedPreferences` read hiccup) can never silently end the whole daily
  cadence; same "always clean up even on per-entry failure" principle as the
  pending-action reconcilers.
- Confirm `DailyStatusVehicles.parse()` defaults a missing `dailyStatusEnabled` field
  to `true` (matching the JS-side default for vehicles created before this field
  existed) and filters OUT vehicles with it explicitly `false` — a regression to the
  opposite default would silently exclude every pre-existing vehicle from a feature
  the user just turned on globally, with no error to catch it.
- Confirm `showStatusNotification()` returns early (no notification) when the
  filtered vehicle list is empty (global on, but nothing opted in, or no vehicles
  exist) — never posts an empty/blank notification.
- Confirm the `findmycar_daily_status` notification channel uses
  `NotificationManager.IMPORTANCE_DEFAULT`, not `IMPORTANCE_LOW`/`IMPORTANCE_MIN` —
  unlike the persistent parking/background notifications (deliberately silent, since
  they're always-on), this is a once-a-day digest the user opted into specifically to
  notice it landed; a regression to a lower importance would make it easy to miss,
  defeating the point (see CLAUDE.md's prior "must be visible in the shade directly"
  lesson for the background-service notification, same principle applies here).
- Confirm `index.html`'s `diagLogCategoryFilter` has a `DAILY` option (a prior
  release shipped `BT-PENDING` without adding it to this dropdown — caught and fixed
  when `GPS-PENDING` was added; check every new category explicitly rather than
  assuming past coverage was complete).

## 6. Background-service survivability (the v1.36.3 audit)

Everything in sections 4-5 assumes `ParkingForegroundService` is actually running and
actually permitted to do its job. Five separate, independently-shipped gaps meant it
often wasn't — each invisible to every build step and every test, and each producing
exactly the same user-visible symptom ("nothing happens in the background"). Verify
all five literally, every release:

- Confirm `AndroidManifest.xml`'s `<service android:name=".ParkingForegroundService">`
  declares `android:foregroundServiceType="connectedDevice|specialUse|location"` —
  **including `location`** — and that
  `android.permission.FOREGROUND_SERVICE_LOCATION` is declared alongside the other
  `FOREGROUND_SERVICE_*` permissions. This was a real, previously-shipped bug: from
  Android 10, a background process only keeps receiving `LocationManager` updates if
  it holds `ACCESS_BACKGROUND_LOCATION` **or** runs a `location`-typed foreground
  service. Without the type, the Service's GPS watch silently stopped delivering the
  moment the app was backgrounded — the exact scenario Stage 4/7 exist for — with the
  telltale signature of `GPS-SHADOW` entries appearing only ~1 second after each app
  open and never during an actual drive. Nothing fails to compile, and no unit test
  can reach it.
- Confirm the `LOCATION` bit in `resolveForegroundServiceType()` is gated on
  `canStartLocationType()` (i.e. `MainActivity.isForeground()`), NOT on the
  location permission alone. A real, previously-shipped bug: Android 14 refuses
  to start a `location`-typed FGS unless the app currently has while-in-use
  capability, because without `ACCESS_BACKGROUND_LOCATION` location is a
  foreground-only permission. `ServiceRestartReceiver` starts the service from a
  background broadcast, so an ungated `LOCATION` bit made `startForeground()`
  throw and — via `onCreate()`'s single try/catch — killed the whole service,
  BT receiver included, on every reboot and every app update.
- Confirm `onCreate()` starts the service via `startForegroundResilient()` (which
  retries with `specialUse` when the preferred type is rejected) and never calls
  `startForeground()` bare. A rejected type must cost that type only — never the
  BT receiver, heartbeat, or reason bookkeeping that follow it. Native code
  cannot be compiled or run in this sandbox and OEMs vary in how they enforce
  these rules, so this backstop is what keeps the next type/permission mistake
  from becoming another total outage.
- Confirm any Kotlin companion function called from `MainActivity.java` (today
  only `ParkingForegroundService.onAppForegrounded()`) carries `@JvmStatic`.
  Without it Kotlin emits `Companion.foo()`, which Java cannot call as a static,
  and the build fails with "cannot find symbol" — caught only in CI, since this
  sandbox has no Android SDK. Grep `MainActivity.java` for calls into Kotlin
  types and check each target's declaration.
- Confirm `ParkingForegroundService` tracks `startedWithLocationType` (whether the
  `LOCATION` bit was present when the service ENTERED the foreground state)
  separately from `currentType` (what is declared now), and that
  `onBecameEligibleForLocationType()` **restarts the service**
  (`restartFromForeground()`) rather than only calling
  `refreshForegroundServiceType()` when the two disagree. A real, previously-
  shipped bug: an FGS's while-in-use location capability is bound to the moment
  it entered foreground state, so a service started from a background broadcast
  never holds it — and re-calling `startForeground()` with the bit added does not
  grant it retroactively. Android accepts the type change with no error and keeps
  withholding every location update, which is why a whole 1h40m drive produced
  zero fixes while every signal read "location type active".
- Confirm that restart is guarded by `locationRestartAttempted` (one attempt per
  process). A restart loop would be considerably worse than the bug it fixes.
- Confirm the `SERVICE` heartbeat line carries the GPS summary — `fgsType=`,
  `+loc@start`/`NO-loc@start`, `gpsFixes=`, `lastFix=`, `dist=` — and that
  `gpsUpdatesSinceHeartbeat` is incremented in `onLocationShadow()`. Without it,
  "the watch isn't running", "it's running but the OS delivers nothing" and
  "fixes arrive but no threshold was crossed" are three different faults that
  look identical in the log; this is what makes them decidable. Confirm it stays
  folded into the existing heartbeat rather than logged per location update —
  per-update logging would blow `NativeLogStore`'s 200-entry cap during a single
  drive, pushing out the lifecycle entries that matter most.
- Confirm `MainActivity.onResume()` calls `ParkingForegroundService
  .onAppForegrounded()`. Without it, a service that correctly started without the
  location type at boot keeps running without it indefinitely — nothing else
  re-resolves the type for an already-running service whose `"parking"` reason
  never transitions again, so background GPS stays dead until the parking ends
  and a new one starts.
- Confirm the "GPS watch started" `SERVICE` log line distinguishes the case where
  the location foreground-service type is NOT active — `requestLocationUpdates()`
  succeeding does not mean updates will arrive, and an unqualified "started" there
  is the misleading-success signal that made this class of bug take days to find.
- Confirm `ParkingForegroundService.resolveForegroundServiceType()` **OR-combines**
  the types it's actually allowed to use rather than picking exactly one: `location`
  only when a location permission is granted, `connectedDevice` only when
  `BLUETOOTH_CONNECT` is granted, falling back to `specialUse` (API 34+) when neither
  is. Android 14+ throws from `startForeground()` if a declared type's runtime
  prerequisite isn't granted, so a version that unconditionally declares `location`
  would crash on a fresh install (location is requested lazily), and a version that
  picks only one type would lose background location whenever Bluetooth happened to
  be granted first.
- Confirm `refreshForegroundServiceType()` exists and is called from
  `updateLocationWatch(true)` — the Service frequently starts for the `"bluetooth"`
  reason alone (no `location` type needed or permitted yet) and only later gains a
  parking session. Re-calling `startForeground()` with the newly-resolved type is the
  documented way to add a type to an already-running FGS; without this call, a GPS
  watch started mid-session runs under a non-`location` type and is throttled exactly
  as if the type were missing entirely.
- Confirm `restoreReasons(context)` exists, is called from **both** `onCreate()` and
  `onStartCommand()`, and is **additive** (it may only add reasons derived from the
  persisted mirror — `KEY_HAS_PARKING` → `"parking"`, `KEY_BT_ENABLED` →
  `"bluetooth"` — never clear reasons already set in memory). `activeReasons` is
  in-memory static state: a process death (OEM kill, `START_STICKY` restart, reboot)
  wipes it, and every `setReasonActive()` caller is a Capacitor `@PluginMethod`
  reachable only from live JS — so without this, a restarted Service came back with
  an empty reason set and immediately stopped itself, or ran with no GPS watch.
- Confirm `ServiceRestartReceiver` is registered in `AndroidManifest.xml` with an
  `<intent-filter>` covering **both** `android.intent.action.BOOT_COMPLETED` and
  `android.intent.action.MY_PACKAGE_REPLACED`, and that its `onReceive()` calls
  `ParkingForegroundService.startIfNeeded(context)` inside a try/catch. Both are
  protected system broadcasts (so `exported="false"` is correct) and both are exempt
  from Android 12+'s background-FGS-start restriction. Without this, a reboot or an
  APK update left background detection dead until the user next opened the app by
  hand — with nothing in the diagnostic log to explain the silence, since a
  non-running service can't log.
- Confirm `startIfNeeded()` calls `restoreReasons()` first and only starts the
  service when at least one reason is derivable — it must never start a foreground
  service (and its persistent notification) for a user who has no parking and
  Bluetooth turned off.
- Confirm `WidgetDataPlugin` defines `KEY_BT_ENABLED` and `syncVehicles()` reads
  `bluetoothEnabled` from the call and persists it, and that `js/widget-bridge.js`'s
  `sync()` sends `bluetoothEnabled` (read fresh from `CFG.keys.bluetoothSettings`,
  defaulting to `true` to match `js/app.js`'s own `#getBtSettings()` default). This
  is the only input `restoreReasons()` has for the `"bluetooth"` reason — an
  unmirrored master switch means a post-reboot restart never re-enables BT detection
  no matter what the user's actual setting is.
- Confirm **every** settings mutation that the native side mirrors calls
  `this.#syncUI()` afterwards — specifically the GPS auto-end toggle and all three BT
  settings callbacks (`onToggleEnabled`, `onToggleVehicle`, `onSetAll`) in
  `js/app.js`. `#syncUI()` is the only thing that ever calls `WidgetBridge.sync()`;
  a settings write without it leaves the native mirror stale until some unrelated
  parking-state change happens to sync, so a user could turn Bluetooth on and have
  the native decision engines keep reading `false` indefinitely. Grep every
  `Store.set(CFG.keys.` call site in `js/app.js` and check each one that writes a
  setting native reads.

## 7. OEM background-restriction setup guide

The guide's whole value is that a user who has been chasing an invisible
background problem can *trust what it tells them*. Every check here protects
that, not a feature:

- Confirm `OemSetupPlugin.status()` returns `autostartAvailable`/
  `oemBatteryAvailable` for the OEM steps and NEVER a field named `granted`
  (or any equivalent) for them — there is no Android API that can read MIUI's
  Autostart or an OEM's per-app battery policy, so any code path that reports
  those as verified is reporting a guess as a fact. Correspondingly, confirm
  `js/oem-setup.js`'s `buildSteps()` gives every `kind: 'manual'` step the
  state `'unknown'` unless the USER's own stored confirmation
  (`CFG.keys.oemSetup`) says otherwise — a manual step must never derive
  `'ok'` from anything the app observed itself.
- Confirm the verifiable steps are re-read live on every `buildSteps()` call
  (it `await`s `status()` each time) rather than cached — revoking a
  permission in system settings and reopening the guide must flip that step
  back to `'todo'`.
- Confirm `status()` version-gates `POST_NOTIFICATIONS` (API 33+) and
  `BLUETOOTH_CONNECT` (API 31+) and reports `true` below those levels —
  minSdk here is 22, and an ungated `checkSelfPermission` on a permission that
  doesn't exist yet reports "denied" forever, which would show a permanently
  unfixable red step on older devices.
- Confirm the location step requires `ACCESS_FINE_LOCATION` specifically (not
  just "some location permission") — coarse-only silently degrades drive-away
  detection without any other symptom, so a guide that passes it is actively
  misleading.
- Confirm every launcher in `OemSettingsIntents` returns one of
  `RESULT_OPENED`/`RESULT_FALLBACK`/`RESULT_FAILED` and that `js/app.js`'s
  setup-modal handler surfaces `fallback` and `failed` as a toast. A vendor
  screen that doesn't exist on a given ROM silently opening the generic
  app-info page instead — with no explanation — is the exact
  indistinguishable-from-a-bug dead end this feature exists to remove.
- Confirm `OemSettingsIntents.launch()` catches `Exception` (not just
  `ActivityNotFoundException`) around `startActivity` — several OEMs guard
  these Activities with a `SecurityException` instead, and an uncaught one
  crashes the app from a settings button.
- Confirm `AndroidManifest.xml` has the `<queries>` block listing the OEM
  settings packages, and that it does NOT use `QUERY_ALL_PACKAGES`. Without
  `<queries>`, Android 11+ package-visibility filtering makes
  `resolveActivity()` blind to those packages, so `autostartAvailable()`
  returns false and a Xiaomi user is told their device has no Autostart
  screen — the single most important step silently vanishing from the list,
  with nothing failing to build or throwing.
- Confirm `BluetoothClassicPlugin`'s `openAppSettingsInternal()` and
  `requestIgnoreBatteryOptimizations()` both delegate to `OemSettingsIntents`
  rather than building their intents inline — two copies of the
  launch/fallback logic would drift (same precedent as
  `BackgroundAlertNotifier`).
- Confirm `OemSetupPlugin` is registered in `MainActivity.onCreate()`'s
  `registerPlugin(...)` list alongside the other two — a Capacitor plugin that
  isn't registered simply resolves to `undefined` in JS, so the guide would
  quietly report "not supported" on native and hide itself, exactly as it
  correctly does in the browser.
- Confirm `OemSetupPlugin` does NOT redeclare a `context` property — the other
  two plugins use `Plugin`'s inherited `getContext()` via Kotlin's synthetic
  property, and shadowing it risks an accidental-override compile error that
  this sandbox (no Android SDK) cannot catch locally.
- Confirm `OemSetup.shouldAutoShow()` returns false once every step is `'ok'`,
  and that `#init()` calls it fire-and-forget on a timer (never `await`ed) —
  a guide that reappears when there is nothing left to do trains users to
  dismiss it reflexively, and blocking init on a plugin call would stall the
  loading screen.
- Confirm `js/oem-setup.js` is in `sw.js`'s `STATIC_ASSETS` and has a
  `<link rel="modulepreload">` (covered generically by section 2, but named
  here since a miss breaks the whole feature rather than something cosmetic).
- Confirm `#oemSetupSection` in `index.html` starts `style="display:none;"`
  and is only revealed when `OemSetup.isSupported()` — the PWA has no native
  plugin and no device settings to open, so the entry point must not appear
  there at all.
- Confirm every string interpolated into the modal's `innerHTML` goes through
  `Utils.escHtml()` — the step list includes `Build.MANUFACTURER`, which comes
  from the device, not from this codebase.

## 8. Widget liveness dots

The dots exist so three consecutive silent-failure bugs become visible from the
home screen. Every check here protects their trustworthiness, which is the only
thing that makes them worth having:

- Confirm `WidgetStatus.render()` produces **four** states and that `GRAY` (not
  `RED`) is what a deliberately-disabled setting or an absent parking maps to —
  specifically `!gpsEnabled || !hasParking` → gray, and `!btEnabled` → gray. A
  red dot for something the user turned off themselves is a false alarm that
  teaches them to ignore the indicator, exactly what the OEM guide's "cannot
  verify" badge exists to avoid.
- Confirm the `AMBER` branch exists and is reached when the GPS watch is active
  but `KEY_GPS_LOCATION_TYPE_ACTIVE` is false — this is the v1.37.1 post-reboot
  state, invisible in every other way (service alive, watch reports started),
  and collapsing it into green or red would hide the exact condition the dots
  were added for.
- Confirm `ParkingForegroundService` writes `KEY_GPS_WATCH_ACTIVE`,
  `KEY_GPS_LOCATION_TYPE_ACTIVE` and `KEY_BT_RECEIVER_ACTIVE` **alongside** (not
  instead of) its existing `NativeLogStore` entries at the same points, and
  clears all three in `onDestroy()`. A flag left `true` after teardown would
  show green for a service that is gone — worse than no indicator at all.
- Confirm `WidgetStatus` reads `ParkingForegroundService.isRunning` directly
  rather than inferring liveness from a persisted timestamp alone: a running
  foreground service keeps this process alive, so the static is precise here,
  while a staleness heuristic would lag by whatever the Doze-throttled refresh
  interval happens to be.
- Confirm `WidgetStatusRefresher.scheduleOrCancel()` counts placed widgets
  across **all three** providers and cancels when the total is zero, and that
  every provider calls it from `onUpdate`/`onEnabled`/`onDisabled`. Removing the
  last widget must stop the alarm — a 2-minute CPU wake for a widget nobody has
  placed is pure battery cost, and a per-provider `onDisabled` that cancelled
  unconditionally would kill the refresh for the other two types.
- Confirm `WidgetDataPlugin.syncVehicles()` calls
  `WidgetStatusRefresher.refreshAll()`. `refreshWidgets()` covers only the two
  data-driven providers and only runs from `update()`/`clear()`, so without this
  a settings toggle would leave the dots showing the previous setting until an
  unrelated parking event — the native counterpart of the "every settings toggle
  must call `#syncUI()`" rule.
- Confirm all three widget layouts declare `widget_status_gps` and
  `widget_status_bt`, and that on `widget_quick_save.xml` / `widget_mini_map.xml`
  the status row is positioned so it cannot overlap the `⋮` quick-actions button
  (top|end) or the 🔁 cycle button (top|start).
- Confirm `ic_status_gps.xml`/`ic_status_bt.xml` are white-sourced vectors —
  `RemoteViews.setInt(id, "setColorFilter", …)` blends with the source color, so
  a colored drawable would render the wrong hue with no error.
- Confirm `WidgetStatusRefreshReceiver` is registered in `AndroidManifest.xml`
  with `exported="false"` — without registration the alarm fires into nothing and
  the dots silently stop refreshing on a timer (they would still update on real
  parking events, which makes the regression easy to miss).
- Confirm `WidgetStatus`/`WidgetStatusRefresher` never call
  `WidgetDataPlugin.update`/`.clear`, `setReasonActive`, or anything with a real
  side effect on parking state. A status indicator must only read — it must never
  be able to break the machinery it reports on, which is also why every entry
  point here is wrapped in try/catch.

## 9. Notification action buttons

The two confirmation notifications (GPS "car moved", BT "you arrived") are
answerable from the shade. Every check protects the property that makes them
worth having — that they work without opening the app:

- Confirm both buttons route through `performWidgetAction()` /
  `WidgetActionReceiver` rather than a notification-specific action path. That
  reuse is what gives them silent vehicle switching, the already-ended guard,
  the result notification, and the `PendingWidgetActionStore` fallback; a
  parallel implementation would have to re-earn all four and would drift.
- Confirm `Notify.registerActionTypes()` is called (from
  `#initNotificationActions()`, once, from `#init()`) BEFORE any notification
  using `Notify.CONFIRM_END` can be scheduled — Android silently drops actions
  for an unregistered type, so the failure mode is buttons just not appearing,
  with no error anywhere.
- Confirm `#notifyIfBackground()` forwards its third `opts` argument to
  `Notify.show()`, and that both confirmation call sites (`#suggestGpsEnd()` and
  the BT connect-confirm branch) pass `actionTypeId` **and** an
  `extra.vehicleId` — without the vehicle id the action would act on whichever
  vehicle happens to be active when the button is pressed, not the one the
  notification was about.
- Confirm the action handler ignores `dismiss` and a bare `tap`, acting only on
  `end` — `tap` fires for the notification body, which should just open the app.
- Confirm the handler closes BOTH `gpsEndModal` and `btParkingModal` before
  acting: answering in the shade makes the in-app modal stale, and returning to
  a question you already answered is its own bug.
- Confirm `BackgroundAlertNotifier.show()` gives each action a request code of
  `notifId + index`. A shared request code makes Android reuse one
  `PendingIntent` across buttons, so every button performs whichever was built
  last — a silent, easily-missed wrong-action bug.
- Confirm `WidgetActionReceiver` cancels `EXTRA_NOTIFICATION_ID` for *every*
  button before running the action (not only on success), and that
  `ACTION_DISMISS` returns immediately after cancelling without touching the
  WebView or recording a pending action.
- Confirm `ACTION_DISMISS` is handled inside `WidgetActionReceiver` rather than
  by a second receiver — one path for every notification button.

## Output format

A markdown table per channel (check | status | detail), then:

**Overall: READY TO MERGE** — every check passed (SKIPPED items noted but not
blocking if they're environment-only, like Playwright needing a browser).

**Overall: NOT READY** — list exactly what to fix, in priority order.
