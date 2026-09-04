// Pushes the active-parking snapshot to the native WidgetData plugin so the
// Android home-screen widgets (active parking / quick save / mini map) stay
// current. No-op in the browser/PWA — WidgetBridge.sync() is safe to call
// unconditionally from js/app.js.
import { normalizeAddress } from './geocoder.js';

export class WidgetBridge {
  static #plugin = window.Capacitor?.Plugins?.WidgetData ?? null;

  static sync(state) {
    if (!this.#plugin) return;

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
