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

  static sync(state) {
    if (!this.#plugin) return;

    // Mirrors the vehicle list + active vehicle + the global GPS auto-end
    // setting into native SharedPreferences so the widgets' quick-actions
    // popup can show a vehicle picker, and so the native BtDecisionEngine/
    // GpsDecisionEngine (android/.../core/) can make headless decisions,
    // without either needing the WebView's own localStorage. Bluetooth
    // fields and hasParking (read directly from each vehicle's own
    // fmc_cur_{id} key — not just the active vehicle's in-memory `current`)
    // are included too.
    this.#plugin.syncVehicles?.({
      vehicles: (state.vehicles ?? []).map(v => ({
        id:                  v.id,
        name:                v.name,
        icon:                v.icon,
        bluetoothDevice:     v.bluetoothDevice ?? '',
        bluetoothAutoEnd:    !!v.bluetoothAutoEnd,
        bluetoothAutoStart:  !!v.bluetoothAutoStart,
        bluetoothStartPopup: v.bluetoothStartPopup !== false,
        hasParking:          !!Store.get(CFG.keys.curPrefix + v.id),
      })),
      activeVehicleId: state.activeVehicleId ?? '',
      gpsAutoEndEnabled: !!Store.get(CFG.keys.gpsAutoEnd, { enabled: false })?.enabled,
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
