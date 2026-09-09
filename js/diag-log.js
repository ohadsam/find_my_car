import { Store } from './store.js';

// Deliberately NOT fmc_-prefixed — Store.exportAll()/importAll() (the
// PWA<->APK backup feature) scan by that prefix, and diagnostic log entries
// are ephemeral debugging data, not user data that belongs in a backup.
const KEY = 'findmycar_diag_log_v1';
const MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000; // 3 days
const MAX_ENTRIES = 800; // hard cap so a noisy loop can't grow this unbounded

// In-app diagnostic log for Bluetooth/GPS/notification background events —
// added because none of that is otherwise observable without a connected
// device and adb: this lets a user report back exactly what the app saw
// (or didn't see) instead of just "it doesn't work".
export class DiagLog {
  // `t` lets a caller record a real historical timestamp instead of "now" —
  // used only to merge NativeLogStore's native-only lifecycle events (which
  // happened at some point while the app was closed) so they show the time
  // they actually occurred, not the time they were read on the next resume.
  static log(category, message, meta = null, t = null) {
    try {
      const entries = this.#load();
      entries.push({ t: t ?? Date.now(), category, message, ...(meta || {}) });
      const pruned = this.#prune(entries).slice(-MAX_ENTRIES);
      Store.set(KEY, pruned);
    } catch {
      // Logging must never break the caller's actual flow.
    }
  }

  static getAll() {
    const pruned = this.#prune(this.#load());
    Store.set(KEY, pruned);
    return pruned;
  }

  static clear() {
    Store.set(KEY, []);
  }

  static formatText(entries) {
    return entries.map(e => {
      const time = new Date(e.t).toLocaleString('he-IL');
      const vehicle = e.vehicleName ? ` [${e.vehicleIcon || ''} ${e.vehicleName}]`.replace(/\s+/g, ' ') : '';
      return `${time} · ${e.category}${vehicle} — ${e.message}`;
    }).join('\n');
  }

  static #load() {
    return Store.get(KEY, []);
  }

  static #prune(entries) {
    const cutoff = Date.now() - MAX_AGE_MS;
    return Array.isArray(entries) ? entries.filter(e => e && e.t >= cutoff) : [];
  }
}
