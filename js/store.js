export const Store = {
  get(k, def = null) {
    try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : def; }
    catch { return def; }
  },
  set(k, v) {
    try { localStorage.setItem(k, JSON.stringify(v)); return true; }
    catch (e) { console.warn('Storage write failed', e); return false; }
  },
  remove(k) { try { localStorage.removeItem(k); } catch {} },

  // Generic backup/restore across every fmc_-prefixed key currently in use
  // (vehicles, per-vehicle current/history, settings) — scanning by prefix
  // instead of a hand-maintained field list means new keys are automatically
  // included without touching this file.
  exportAll() {
    const data = {};
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (key && key.startsWith('fmc_')) data[key] = localStorage.getItem(key);
    }
    return data;
  },

  importAll(data) {
    if (!data || typeof data !== 'object') throw new Error('Invalid backup data');
    for (const [key, value] of Object.entries(data)) {
      if (typeof key === 'string' && key.startsWith('fmc_') && typeof value === 'string') {
        localStorage.setItem(key, value);
      }
    }
  },
};
