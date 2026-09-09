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
  // `loggedAt` is always the real moment this call itself runs — for a live
  // entry that's essentially the same instant as `t`, but for a merged
  // native entry it's the (much later) moment the app actually resumed and
  // wrote it here, distinct from `t` (when the native event itself
  // happened) — both are kept so a delay between "it happened" and "it
  // made it into the log" is itself visible, not just the event time.
  // `source` identifies WHICH process actually logged this — 'WEB' (the
  // default, for genuine live JS-side entries) or a native process tag
  // (e.g. "FMC-FgService"/"FMC-BtPlugin"/"FMC-WidgetData", passed through
  // by #reconcileNativeLog() from NativeLogEntry.tag) — so one unified log
  // stays attributable to its actual origin at a glance.
  static log(category, message, meta = null, t = null, source = 'WEB') {
    try {
      const entries = this.#load();
      entries.push({ t: t ?? Date.now(), loggedAt: Date.now(), source, category, message, ...(meta || {}) });
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
      const source = e.source || 'WEB';
      // Only shown when the entry was actually written to the log well
      // after the event it describes happened (e.g. a native event merged
      // in on the next app resume, possibly hours later) — for a live
      // entry `t`/`loggedAt` are the same instant, so showing both would
      // just be noise; a >2s gap is exactly the "was this delayed reaching
      // the log" signal worth surfacing.
      const loggedNote = (e.loggedAt && Math.abs(e.loggedAt - e.t) > 2000)
        ? ` (נרשם בפועל ב-${new Date(e.loggedAt).toLocaleString('he-IL')})`
        : '';
      return `${time} · [${source}] ${e.category}${vehicle} — ${e.message}${loggedNote}`;
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
