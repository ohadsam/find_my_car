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
    `ACCESS_FINE_LOCATION`
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

## 4. Cross-channel behavior parity

- Confirm `js/widget-bridge.js` and every `Capacitor.isNativePlatform()` /
  `window.Capacitor` branch in `js/app.js` is genuinely a no-op in the browser (no
  code path that throws or behaves differently for PWA users when `window.Capacitor`
  is `undefined`) — spot-read the guards, don't just grep for their existence.

## Output format

A markdown table per channel (check | status | detail), then:

**Overall: READY TO MERGE** — every check passed (SKIPPED items noted but not
blocking if they're environment-only, like Playwright needing a browser).

**Overall: NOT READY** — list exactly what to fix, in priority order.
