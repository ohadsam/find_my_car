// Pushes the active-parking snapshot to the native WidgetData plugin so the
// Android home-screen widgets (active parking / quick save / mini map) stay
// current. No-op in the browser/PWA — WidgetBridge.sync() is safe to call
// unconditionally from js/app.js.
import { CFG } from './config.js';
import { Store } from './store.js';
import { DiagLog } from './diag-log.js';
import { normalizeAddress } from './geocoder.js';

export class WidgetBridge {
  static #plugin = window.Capacitor?.Plugins?.WidgetData ?? null;
  static #shadowListenerInitialized = false;

  // Stage 4 of the native background-detection migration (see CLAUDE.md):
  // ParkingForegroundService's location watch computes what GpsDecisionEngine
  // would decide, purely for comparison against the real JS decision — this
  // just logs it under its own diagnostic-log category, mirroring how
  // js/bluetooth-native.js logs BT-SHADOW. Safe to call unconditionally
  // (no-op in the browser); call once from app.js's #init().
  static initShadowListener() {
    if (!this.#plugin || this.#shadowListenerInitialized) return;
    this.#shadowListenerInitialized = true;
    this.#plugin.addListener?.('gpsShadowDecision', ({ trigger, decision }) => {
      DiagLog.log('GPS-SHADOW', `native would decide (trigger=${trigger || '?'}): ${decision || '?'}`);
    });
  }

  // Stage 7 of the native background-detection migration (see CLAUDE.md):
  // reads/clears the GPS end-suggestion ParkingForegroundService recorded
  // while the WebView was unreachable — js/app.js replays it as the same
  // gpsEndModal confirmation on resume, never an automatic end. No-op in
  // the browser/PWA.
  static async getPendingGpsSuggestion() {
    if (!this.#plugin) return null;
    try {
      const { pendingJson } = await this.#plugin.getPendingGpsSuggestion();
      const parsed = JSON.parse(pendingJson ?? 'null');
      return parsed && typeof parsed === 'object' ? parsed : null;
    } catch {
      return null;
    }
  }

  static async clearPendingGpsSuggestion() {
    await this.#plugin?.clearPendingGpsSuggestion?.().catch(() => {});
  }

  // Stage 8 of the native background-detection migration (see CLAUDE.md):
  // reads/clears widget quick-actions (save/swap/end) WidgetActionReceiver
  // recorded while the WebView was unreachable — js/app.js replays them
  // through the real performWidgetAction() on resume. No-op in the
  // browser/PWA.
  static async getPendingWidgetActions() {
    if (!this.#plugin) return [];
    try {
      const { actionsJson } = await this.#plugin.getPendingWidgetActions();
      const parsed = JSON.parse(actionsJson || '[]');
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  static async clearPendingWidgetActions() {
    await this.#plugin?.clearPendingWidgetActions?.().catch(() => {});
  }

  // Reads/clears NativeLogStore's native-only lifecycle log (foreground
  // service start/stop, GPS watch start/stop, raw BT ACL broadcast receipt)
  // — js/app.js merges each entry into DiagLog under the SERVICE category
  // on resume, with its real historical timestamp, so "was the background
  // service actually alive, and when" is provable from inside the app
  // without adb. No-op in the browser/PWA.
  static async getNativeLog() {
    if (!this.#plugin) return [];
    try {
      const { entriesJson } = await this.#plugin.getNativeLog();
      const parsed = JSON.parse(entriesJson || '[]');
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  static async clearNativeLog() {
    await this.#plugin?.clearNativeLog?.().catch(() => {});
  }

  static sync(state) {
    if (!this.#plugin) return;

    // Mirrors the vehicle list + active vehicle + the global GPS auto-end
    // setting into native SharedPreferences so the widgets' quick-actions
    // popup can show a vehicle picker, and so the native BtDecisionEngine/
    // GpsDecisionEngine (android/.../core/) can make headless decisions,
    // without either needing the WebView's own localStorage. Bluetooth
    // fields and each vehicle's own parking snapshot (read directly from
    // its own fmc_cur_{id} key — not just the active vehicle's in-memory
    // `current`) are included too, so the "חניה פעילה"/"מפה מוקטנת" widgets
    // can show more than one simultaneously-parked vehicle when they're
    // resized large enough (see ActiveParkingWidgetProvider/
    // MiniMapWidgetProvider) instead of only ever the active vehicle.
    this.#plugin.syncVehicles?.({
      vehicles: (state.vehicles ?? []).map(v => {
        const parking = Store.get(CFG.keys.curPrefix + v.id);
        return {
          id:                  v.id,
          name:                v.name,
          icon:                v.icon,
          bluetoothDevice:     v.bluetoothDevice ?? '',
          bluetoothAutoEnd:    !!v.bluetoothAutoEnd,
          bluetoothAutoStart:  !!v.bluetoothAutoStart,
          bluetoothStartPopup: v.bluetoothStartPopup !== false,
          hasParking:          !!parking,
          address:             parking ? (normalizeAddress(parking.address) || '') : '',
          lat:                 parking?.location?.lat ?? null,
          lng:                 parking?.location?.lng ?? null,
          timestamp:           parking?.timestamp ?? null,
          dailyStatusEnabled:  v.dailyStatusEnabled !== false,
        };
      }),
      activeVehicleId: state.activeVehicleId ?? '',
      gpsAutoEndEnabled: !!Store.get(CFG.keys.gpsAutoEnd, { enabled: false })?.enabled,
      // Global master switch for the once-daily "is anything parked, and
      // where" system notification (see WidgetDataPlugin.kt/
      // DailyStatusReceiver.kt) — read fresh on every sync so toggling it
      // in Settings takes effect on the native side immediately, not only
      // on the next unrelated parking-state change.
      dailyStatusNotificationEnabled: !!Store.get(CFG.keys.dailyStatus, { enabled: false })?.enabled,
    }).catch(() => {});

    const current = state.current;
    const vehicle = state.vehicles?.find(v => v.id === state.activeVehicleId) ?? null;

    if (!current) {
      this.#plugin.clear().catch(() => {});
      return;
    }

    this.#plugin.update({
      address:   normalizeAddress(current.address) || '',
      lat:       current.location.lat,
      lng:       current.location.lng,
      timestamp: current.timestamp,
      vehicleIcon: vehicle?.icon ?? '🚗',
      vehicleName: vehicle?.name ?? '',
    }).catch(() => {});
  }
}
