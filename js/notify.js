// One-off system notifications for background BT/GPS events — separate from
// the persistent "active parking" notification in js/app.js
// (#showParkingNotification), which keeps using the service-worker path
// since it already works there.
//
// The plain Web Notifications API (new Notification()/ServiceWorkerRegistration
// .showNotification()) is known to be unreliable inside a bare Android
// WebView — Capacitor apps use the official @capacitor/local-notifications
// plugin for real native notifications instead. This module picks the right
// mechanism per platform so callers just call Notify.show(title, body).
import { DiagLog } from './diag-log.js';

export class Notify {
  static #nextId = 1;
  static #actionTypesRegistered = false;

  // Action-type id for the two notifications that ask the user to CONFIRM
  // something (GPS "the car seems to have moved", Bluetooth "you connected —
  // end the parking?"). Both previously said "open the app to confirm", which
  // is exactly the wrong thing to ask of someone who is driving: the decision
  // is one tap, and the shade is where it belongs. The buttons route through
  // the same performWidgetAction() the widgets use, so there is one headless
  // action path, not two.
  static CONFIRM_END = 'FMC_CONFIRM_END';

  // Must match BackgroundAlertNotifier.CHANNEL_ID (Kotlin) — the two paths
  // deliberately share one channel so the user has a single switch for "the
  // app's alerts", regardless of whether a given alert happened to be posted
  // natively (app killed) or through this plugin (app alive, backgrounded).
  //
  // Importance 4 = Android's IMPORTANCE_HIGH, which is what actually produces a
  // heads-up banner; 3 (DEFAULT, the plugin's default channel) only makes a
  // sound and a status-bar icon. Reported as "the notification was in the shade
  // but never popped up while I was in Waze". The `_v2` suffix is load-bearing:
  // an existing channel's importance can never be raised, so reusing the old id
  // would have changed nothing for anyone who already had the app installed.
  static ALERT_CHANNEL = 'findmycar_alerts_v2';
  static #channelReady = false;

  static async #ensureChannel() {
    const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
    if (!window.Capacitor?.isNativePlatform?.() || !LocalNotifications?.createChannel) return null;
    if (this.#channelReady) return this.ALERT_CHANNEL;
    try {
      await LocalNotifications.createChannel({
        id:          this.ALERT_CHANNEL,
        name:        'התראות רקע',
        description: 'זיהוי נסיעה, חיבור Bluetooth והצעות חניה — מופיעות על המסך',
        importance:  4,
        visibility:  1,
        vibration:   true,
      });
      await LocalNotifications.deleteChannel?.({ id: 'findmycar_bt_alerts' }).catch(() => {});
      this.#channelReady = true;
      DiagLog.log('NOTIFY', `created high-importance channel ${this.ALERT_CHANNEL} (heads-up enabled)`);
      return this.ALERT_CHANNEL;
    } catch (e) {
      // Falling back to the plugin's default channel still delivers the
      // notification — it just won't pop up. Better than not notifying at all.
      DiagLog.log('NOTIFY', `createChannel failed, falling back to the default channel — ${e?.message || e}`);
      return null;
    }
  }

  // Must run before any notification that uses CONFIRM_END is scheduled —
  // Android silently drops actions for an unregistered type. Called once from
  // js/app.js's #init(); no-op in the browser.
  static async registerActionTypes() {
    const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
    if (!window.Capacitor?.isNativePlatform?.() || !LocalNotifications) return;
    if (this.#actionTypesRegistered) return;
    try {
      await LocalNotifications.registerActionTypes({
        types: [{
          id: this.CONFIRM_END,
          actions: [
            { id: 'end',     title: 'סיים חניה' },
            { id: 'dismiss', title: 'התעלם' },
          ],
        }],
      });
      this.#actionTypesRegistered = true;
      DiagLog.log('NOTIFY', 'registered notification action types');
      // Same one-time-at-startup slot: the channel must exist before the first
      // notification is scheduled, or that one lands on the default channel.
      await this.#ensureChannel();
    } catch (e) {
      DiagLog.log('NOTIFY', `registerActionTypes failed — ${e?.message || e}`);
    }
  }

  /**
   * Fires `handler({ actionId, extra })` when a notification button (or the
   * notification body, actionId 'tap') is used. No-op in the browser.
   */
  static async addActionListener(handler) {
    const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
    if (!window.Capacitor?.isNativePlatform?.() || !LocalNotifications) return;
    try {
      await LocalNotifications.addListener('localNotificationActionPerformed', (e) => {
        handler({ actionId: e?.actionId ?? 'tap', extra: e?.notification?.extra ?? {} });
      });
    } catch (e) {
      DiagLog.log('NOTIFY', `addActionListener failed — ${e?.message || e}`);
    }
  }

  // Public so app.js can prime the notification permission at first launch
  // (see #primeNativePermissions()) instead of only asking reactively the
  // first time a notification would actually fire.
  static async ensurePermission() {
    const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
    if (window.Capacitor?.isNativePlatform?.() && LocalNotifications) {
      try {
        const { display } = await LocalNotifications.checkPermissions();
        if (display === 'granted') return true;
        const res = await LocalNotifications.requestPermissions();
        return res.display === 'granted';
      } catch {
        return false;
      }
    }

    if (!('Notification' in window)) return false;
    if (Notification.permission === 'granted') return true;
    if (Notification.permission === 'denied') return false;
    try {
      return (await Notification.requestPermission()) === 'granted';
    } catch {
      return false;
    }
  }

  // Non-requesting check, used to surface a settings nudge in the UI instead
  // of only reactively finding out a notification never showed up.
  static async checkPermission() {
    const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
    if (window.Capacitor?.isNativePlatform?.() && LocalNotifications) {
      try {
        const { display } = await LocalNotifications.checkPermissions();
        return display === 'granted';
      } catch {
        return false;
      }
    }
    if (!('Notification' in window)) return false;
    return Notification.permission === 'granted';
  }

  /**
   * @param {object} [opts] `{ actionTypeId, extra }` — adds shade buttons on
   *   native. Ignored in the browser, which has no equivalent here.
   */
  static async show(title, body, opts = {}) {
    try {
      const granted = await this.ensurePermission();
      if (!granted) {
        DiagLog.log('NOTIFY', `show() skipped — permission not granted: "${title}"`);
        return;
      }

      const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
      if (window.Capacitor?.isNativePlatform?.() && LocalNotifications) {
        const n = { id: this.#nextId++, title, body };
        if (opts.actionTypeId) n.actionTypeId = opts.actionTypeId;
        if (opts.extra)        n.extra        = opts.extra;
        // Re-checked on every show rather than assumed: registerActionTypes()
        // normally creates it at startup, but show() can be reached first on a
        // replay path, and #ensureChannel() is a no-op once it has succeeded.
        const channelId = await this.#ensureChannel();
        if (channelId) n.channelId = channelId;
        await LocalNotifications.schedule({ notifications: [n] });
        DiagLog.log('NOTIFY', `scheduled native notification: "${title}"` +
          (opts.actionTypeId ? ` (with actions: ${opts.actionTypeId})` : '') +
          (channelId ? ` [channel ${channelId}, heads-up]` : ' [default channel — no heads-up]'));
        return;
      }

      if (!('Notification' in window)) {
        DiagLog.log('NOTIFY', `show() failed — Notification API unavailable: "${title}"`);
        return;
      }
      const reg = await navigator.serviceWorker?.ready?.catch(() => null);
      if (reg) {
        reg.showNotification(title, {
          body,
          icon:  './icons/icon-192.png',
          badge: './icons/icon-192.png',
        });
        DiagLog.log('NOTIFY', `shown via service worker: "${title}"`);
      } else {
        DiagLog.log('NOTIFY', `show() failed — no ready service worker: "${title}"`);
      }
    } catch (e) {
      DiagLog.log('NOTIFY', `show() threw — ${e?.message || e}: "${title}"`);
      // Notifications are a best-effort convenience — never break the
      // caller's own flow (BT/GPS handling) if this fails.
    }
  }
}
