// Pushes the active-parking snapshot to the native WidgetData plugin so the
// Android home-screen widgets (active parking / quick save / mini map) stay
// current. No-op in the browser/PWA — WidgetBridge.sync() is safe to call
// unconditionally from js/app.js.
import { CFG } from './config.js';
import { Store } from './store.js';
import { DiagLog } from './diag-log.js';
import { normalizeAddress } from './geocoder.js';

// Blank while a lookup is still pending (the widget shows "מיקום נשמר");
// coordinates once it is known there is no address at this spot.
function addressText(p) {
  const addr = normalizeAddress(p.address);
  if (addr) return addr;
  return p.addressLookup === 'none' ? `${p.location.lat.toFixed(5)}, ${p.location.lng.toFixed(5)}` : '';
}

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

  // A drive-away suggestion native raised while the app is open (v1.51.0) —
  // native decides for every parked vehicle, so the page no longer decides
  // itself. The callback reads the recorded suggestion, which also covers a
  // hand-off lost in transit. No-op in the browser/PWA.
  static onGpsSuggestion(cb) {
    this.#plugin?.addListener?.('gpsSuggestion', ({ vehicleId } = {}) => {
      DiagLog.log('GPS-PENDING', `native drive-away suggestion received live (vehicle=${vehicleId || '?'})`);
      cb(vehicleId);
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

  // The walk-away parking suggestion WalkAwayEngine raised natively while the
  // app wasn't in front of the user (see CLAUDE.md "Walk-away parking
  // suggestion"). Mirrors getPendingGpsSuggestion's shape exactly.
  static async getPendingParkingSuggestion() {
    if (!this.#plugin) return null;
    try {
      const { suggestionJson } = await this.#plugin.getPendingParkingSuggestion();
      const parsed = JSON.parse(suggestionJson ?? 'null');
      return parsed && typeof parsed === 'object' ? parsed : null;
    } catch {
      return null;
    }
  }

  static async clearPendingParkingSuggestion() {
    await this.#plugin?.clearPendingParkingSuggestion?.().catch(() => {});
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

  // Parkings native saved/ended while the app was closed — the real records,
  // adopted verbatim by FindMyCarApp.#adoptNativeParkingOps().
  static async getNativeParkingOps() {
    if (!this.#plugin?.getNativeParkingOps) return [];
    try {
      const { opsJson } = await this.#plugin.getNativeParkingOps();
      const parsed = JSON.parse(opsJson || '[]');
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  static async removeNativeParkingOps(opIds) {
    if (!opIds?.length) return;
    await this.#plugin?.removeNativeParkingOps?.({ opIds }).catch(() => {});
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
          // Lets native end exactly this parking when it commits an end
          // while the app is closed (NativeParkingCommitter.commitEnd).
          parkingId:           parking?.id ?? null,
          address:             parking ? addressText(parking) : '',
          lat:                 parking?.location?.lat ?? null,
          lng:                 parking?.location?.lng ?? null,
          timestamp:           parking?.timestamp ?? null,
          dailyStatusEnabled:  v.dailyStatusEnabled !== false,
          walkAwaySuggest:     !!v.walkAwaySuggest,
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
      // The Bluetooth master switch — mirrored so the native side can rebuild
      // its "bluetooth" foreground-service keep-alive reason after a process
      // death/reboot without the app being open (see
      // ParkingForegroundService.restoreReasons()). Default true matches
      // js/app.js's own #getBtSettings() default.
      bluetoothEnabled: Store.get(CFG.keys.bluetoothSettings, { enabled: true })?.enabled !== false,
    }).catch(() => {});

    const current = state.current;
    const vehicle = state.vehicles?.find(v => v.id === state.activeVehicleId) ?? null;

    if (!current) {
      this.#plugin.clear().catch(() => {});
      return;
    }

    this.#plugin.update({
      address:   addressText(current),
      lat:       current.location.lat,
      lng:       current.location.lng,
      timestamp: current.timestamp,
      vehicleIcon: vehicle?.icon ?? '🚗',
      vehicleName: vehicle?.name ?? '',
    }).catch(() => {});
  }
}
