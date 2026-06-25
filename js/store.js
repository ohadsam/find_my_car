export const Store = {
  get(k, def = null) {
    try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : def; }
    catch { return def; }
  },
  set(k, v) {
    try { localStorage.setItem(k, JSON.stringify(v)); return true; }
    catch (e) { console.warn('Storage write failed', e); return false; }
  },
  remove(k) { try { localStorage.removeItem(k); } catch {} }
};
