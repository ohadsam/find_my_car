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
export class Notify {
  static #nextId = 1;

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

  static async show(title, body) {
    try {
      const granted = await this.ensurePermission();
      if (!granted) return;

      const LocalNotifications = window.Capacitor?.Plugins?.LocalNotifications;
      if (window.Capacitor?.isNativePlatform?.() && LocalNotifications) {
        await LocalNotifications.schedule({
          notifications: [{ id: this.#nextId++, title, body }],
        });
        return;
      }

      if (!('Notification' in window)) return;
      const reg = await navigator.serviceWorker?.ready?.catch(() => null);
      if (reg) {
        reg.showNotification(title, {
          body,
          icon:  './icons/icon-192.png',
          badge: './icons/icon-192.png',
        });
      }
    } catch {
      // Notifications are a best-effort convenience — never break the
      // caller's own flow (BT/GPS handling) if this fails.
    }
  }
}
