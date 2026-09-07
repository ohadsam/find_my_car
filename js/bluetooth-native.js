// Drop-in replacement for BluetoothController (js/bluetooth.js) when running
// inside the Android app (see js/app.js). Same public interface — isSupported(),
// init(), startWatch(), stopWatch(), checkNow(), getDevices(), requestPermission()
// — but backed by real classic-Bluetooth ACL connect/disconnect broadcasts from
// the native BluetoothClassicPlugin (android/.../BluetoothClassicPlugin.kt)
// instead of the enumerateDevices()/devicechange proxy the browser is limited to.
import { DiagLog } from './diag-log.js';

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
    if (this.#listening || !this.#plugin) {
      DiagLog.log('BT', `startWatch skipped (listening=${this.#listening}, plugin=${!!this.#plugin})`);
      return;
    }
    this.#listening = true; // guard before await so rapid calls don't attach two listener sets

    const h1 = this.#plugin.addListener('connected', ({ label }) => {
      DiagLog.log('BT-RAW', `native "connected" event received, label=${label || '(empty)'}`);
      if (label) this.#onDeviceConnected?.(label);
    });
    const h2 = this.#plugin.addListener('disconnected', ({ label }) => {
      DiagLog.log('BT-RAW', `native "disconnected" event received, label=${label || '(empty)'}`);
      if (label) this.#onDeviceDisconnected?.(label);
    });
    this.#handles = await Promise.all([h1, h2]);

    try {
      await this.#plugin.startWatch();
      DiagLog.log('BT', 'startWatch: native plugin call resolved');
    } catch (e) {
      DiagLog.log('BT', `startWatch: native plugin call threw — ${e?.message || e}`);
      // Foreground service or permission failed to start; listeners stay
      // registered so a later checkNow()/permission grant still works.
    }

    // Confirm (rather than assume) that ParkingForegroundService actually
    // started — setReasonActive() on the native side is fire-and-forget, so
    // a silent startup failure there would otherwise be invisible from here.
    setTimeout(async () => {
      const running = await this.#plugin?.isForegroundServiceRunning?.().catch(() => null);
      DiagLog.log('BT', `background service running check: ${running?.running === true ? 'YES' : running?.running === false ? 'NO — background BT/GPS detection will not work' : 'unknown (check failed)'}`);
    }, 1500);
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
    DiagLog.log('BT', 'checkNow() invoked (app resumed / re-sync)');
    await this.#plugin.checkNow?.().catch(e => DiagLog.log('BT', `checkNow threw — ${e?.message || e}`));
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
      DiagLog.log('PERM', `Bluetooth permission request result: ${res?.granted ? 'granted' : 'denied'}`);
      return !!res?.granted;
    } catch (e) {
      DiagLog.log('PERM', `Bluetooth permission request threw — ${e?.message || e}`);
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
