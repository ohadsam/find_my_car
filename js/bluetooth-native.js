// Drop-in replacement for BluetoothController (js/bluetooth.js) when running
// inside the Android app (see js/app.js). Same public interface — isSupported(),
// init(), startWatch(), stopWatch(), checkNow(), getDevices(), requestPermission()
// — but backed by real classic-Bluetooth ACL connect/disconnect broadcasts from
// the native BluetoothClassicPlugin (android/.../BluetoothClassicPlugin.kt)
// instead of the enumerateDevices()/devicechange proxy the browser is limited to.
export class NativeBluetoothController {
  #onDeviceConnected    = null;
  #onDeviceDisconnected = null;
  #plugin               = null;
  #handles              = [];
  #listening            = false;

  static isSupported() {
    return !!(window.Capacitor?.isNativePlatform?.() && window.Capacitor?.Plugins?.BluetoothClassic);
  }

  init({ onDeviceConnected, onDeviceDisconnected }) {
    this.#onDeviceConnected    = onDeviceConnected;
    this.#onDeviceDisconnected = onDeviceDisconnected;
    this.#plugin = window.Capacitor?.Plugins?.BluetoothClassic ?? null;
  }

  async startWatch() {
    if (this.#listening || !this.#plugin) return;
    this.#listening = true; // guard before await so rapid calls don't attach two listener sets

    const h1 = this.#plugin.addListener('connected', ({ label }) => {
      if (label) this.#onDeviceConnected?.(label);
    });
    const h2 = this.#plugin.addListener('disconnected', ({ label }) => {
      if (label) this.#onDeviceDisconnected?.(label);
    });
    this.#handles = await Promise.all([h1, h2]);

    try {
      await this.#plugin.startWatch();
    } catch {
      // Foreground service or permission failed to start; listeners stay
      // registered so a later checkNow()/permission grant still works.
    }
  }

  stopWatch() {
    if (!this.#listening) return;
    this.#listening = false;
    this.#handles.forEach(h => h.remove?.());
    this.#handles = [];
    this.#plugin?.stopWatch?.().catch(() => {});
  }

  async checkNow() {
    if (!this.#listening || !this.#plugin) return;
    await this.#plugin.checkNow?.().catch(() => {});
  }

  async getDevices() {
    if (!this.#plugin) return [];
    try {
      const { devices } = await this.#plugin.getBondedDevices();
      return devices ?? [];
    } catch {
      return [];
    }
  }

  async requestPermission() {
    if (!this.#plugin) return false;
    try {
      const res = await this.#plugin.requestBtPermission();
      return !!res?.granted;
    } catch {
      return false;
    }
  }

  // Distinguishes "not granted yet, will still prompt" from "permanently
  // denied" (Android stops showing the dialog after the user declines twice
  // or checks "don't ask again") — requestPermission() alone can't tell
  // these apart, and a permanently-denied permission makes every future
  // request silently resolve to granted:false with no dialog at all, which
  // otherwise looks indistinguishable from a bug.
  async permissionStatus() {
    if (!this.#plugin) return { granted: false, permanentlyDenied: false };
    try {
      return await this.#plugin.permissionStatus();
    } catch {
      return { granted: false, permanentlyDenied: false };
    }
  }

  async openAppSettings() {
    await this.#plugin?.openAppSettings?.().catch(() => {});
  }
}
