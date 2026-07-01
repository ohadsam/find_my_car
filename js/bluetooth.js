export class BluetoothController {
  #onDeviceConnected    = null;
  #onDeviceDisconnected = null;
  #prevLabels           = new Set();
  #listening            = false;
  #boundHandler         = null;
  #changeTimer          = null;
  #handling             = false;

  static isSupported() {
    return !!navigator.mediaDevices?.enumerateDevices;
  }

  init({ onDeviceConnected, onDeviceDisconnected }) {
    this.#onDeviceConnected    = onDeviceConnected;
    this.#onDeviceDisconnected = onDeviceDisconnected;
  }

  async startWatch() {
    if (this.#listening || !BluetoothController.isSupported()) return;
    this.#listening = true;  // guard before await so rapid calls don't attach two listeners
    const devices = await this.#audioDevices();
    this.#prevLabels  = new Set((devices ?? []).map(d => d.label).filter(Boolean));
    this.#boundHandler = () => {
      clearTimeout(this.#changeTimer);
      this.#changeTimer = setTimeout(() => this.#handleChange(), 100);
    };
    navigator.mediaDevices.addEventListener('devicechange', this.#boundHandler);
  }

  stopWatch() {
    if (!this.#listening || !this.#boundHandler) return;
    clearTimeout(this.#changeTimer);
    navigator.mediaDevices.removeEventListener('devicechange', this.#boundHandler);
    this.#boundHandler = null;
    this.#listening    = false;
  }

  async checkNow() {
    if (!this.#listening) return;
    await this.#handleChange();
  }

  async getDevices() {
    return (await this.#audioDevices()) ?? [];
  }

  static async requestPermission() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      stream.getTracks().forEach(t => t.stop());
      return true;
    } catch {
      return false;
    }
  }

  async #handleChange() {
    if (this.#handling) return;
    this.#handling = true;
    try {
      const prev = this.#prevLabels;  // snapshot before await so concurrent calls don't share state
      const devices = await this.#audioDevices();
      if (!this.#listening || devices === null) return;  // stopped or error — don't diff (avoids false disconnects)
      const current = new Set(devices.map(d => d.label).filter(Boolean));
      this.#prevLabels = current;  // update before firing callbacks to prevent re-entrancy confusion

      for (const label of current) {
        if (!prev.has(label)) this.#onDeviceConnected?.(label);
      }
      for (const label of prev) {
        if (!current.has(label)) this.#onDeviceDisconnected?.(label);
      }
    } finally {
      this.#handling = false;
    }
  }

  async #audioDevices() {
    if (!BluetoothController.isSupported()) return [];
    try {
      const all = await navigator.mediaDevices.enumerateDevices();
      return all.filter(d =>
        (d.kind === 'audiooutput' || d.kind === 'audioinput') &&
        d.deviceId !== 'default' &&
        d.deviceId !== 'communications'
      );
    } catch {
      return null;  // null signals error; caller must not diff against prevLabels on error
    }
  }
}
