// Pushes the active-parking snapshot to the native WidgetData plugin so the
// Android home-screen widgets (active parking / quick save / mini map) stay
// current. No-op in the browser/PWA — WidgetBridge.sync() is safe to call
// unconditionally from js/app.js.
import { CFG } from './config.js';
import { Store } from './store.js';
import { normalizeAddress } from './geocoder.js';

export class WidgetBridge {
  static #plugin = window.Capacitor?.Plugins?.WidgetData ?? null;

  static sync(state) {
    if (!this.#plugin) return;

    // Mirrors the vehicle list + active vehicle into native SharedPreferences
    // so the widgets' quick-actions popup can show a vehicle picker without
    // needing to read the WebView's own localStorage. Bluetooth-related
    // fields (and hasParking, read directly from each vehicle's own
    // fmc_cur_{id} key — not just the active vehicle's in-memory `current`)
    // are included too — this is the same mirror the native BtDecisionEngine
    // (android/.../core/) reads to make headless connect/disconnect
    // decisions without the WebView.
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
