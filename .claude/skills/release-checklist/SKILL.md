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
  effect. Shadow mode existing specifically to build confidence before Stage 4
  flips Bluetooth to live — a shadow-mode change that quietly starts taking real
  action skips that entire verification step.
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
- If `GpsDecisionEngine` has been wired into a real location watch (Stage 4+ —
  check CLAUDE.md's migration stage list for current status), apply the same
  shadow-mode-stays-inert check used for Bluetooth above: the wiring code must only
  log/emit a shadow event, never itself open `gpsEndModal`, call
  `WidgetDataPlugin.update`/`.clear`, or otherwise take real action, until the
  migration plan says GPS has been explicitly flipped to live.

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
  `MainActivity.getActiveWebView()` before falling back to launching `MainActivity`.
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

## 7. Cross-channel behavior parity

- Confirm `js/widget-bridge.js` and every `Capacitor.isNativePlatform()` /
  `window.Capacitor` branch in `js/app.js` is genuinely a no-op in the browser (no
  code path that throws or behaves differently for PWA users when `window.Capacitor`
  is `undefined`) — spot-read the guards, don't just grep for their existence.

## Output format

A markdown table per channel (check | status | detail), then:

**Overall: READY TO MERGE** — every check passed (SKIPPED items noted but not
blocking if they're environment-only, like Playwright needing a browser).

**Overall: NOT READY** — list exactly what to fix, in priority order.
