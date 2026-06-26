import { CFG } from './config.js';
import { Store } from './store.js';
import { Utils } from './utils.js';

export const VehicleController = {
  migrate() {
    if (Store.get(CFG.keys.vehicles)) return;
    const id = Utils.uuid();
    Store.set(CFG.keys.vehicles, [{ id, name: 'הרכב שלי', icon: '🚗' }]);
    Store.set(CFG.keys.activeVehicle, id);
    const cur  = Store.get(CFG.keys.legacyCurrent);
    const hist = Store.get(CFG.keys.legacyHistory, []);
    if (cur)         Store.set(CFG.keys.curPrefix  + id, cur);
    if (hist.length) Store.set(CFG.keys.histPrefix + id, hist);
  },

  getAll() { return Store.get(CFG.keys.vehicles, []); },

  getActiveId() {
    const vehicles = this.getAll();
    if (!vehicles.length) return null;
    const saved = Store.get(CFG.keys.activeVehicle);
    return (saved && vehicles.find(v => v.id === saved)) ? saved : vehicles[0].id;
  },

  getById(id) { return this.getAll().find(v => v.id === id) || null; },

  add(name, icon, plate, color) {
    const vehicles = this.getAll();
    if (vehicles.length >= CFG.maxVehicles) return null;
    const v = {
      id:    Utils.uuid(),
      name:  (name || '').trim().slice(0, CFG.maxVehicleNameLen) || 'רכב',
      icon,
      plate: (plate || '').trim().slice(0, CFG.maxPlateLen) || null,
      color: (color || '').trim().slice(0, CFG.maxColorLen) || null,
    };
    vehicles.push(v);
    Store.set(CFG.keys.vehicles, vehicles);
    return v;
  },

  update(id, name, icon, plate, color) {
    const vehicles = this.getAll();
    const idx = vehicles.findIndex(v => v.id === id);
    if (idx === -1) return false;
    vehicles[idx] = {
      ...vehicles[idx],
      name:  (name || '').trim().slice(0, CFG.maxVehicleNameLen) || 'רכב',
      icon,
      plate: (plate || '').trim().slice(0, CFG.maxPlateLen) || null,
      color: (color || '').trim().slice(0, CFG.maxColorLen) || null,
    };
    Store.set(CFG.keys.vehicles, vehicles);
    return true;
  },

  remove(id) {
    const vehicles = this.getAll();
    if (vehicles.length <= 1) return false;
    Store.set(CFG.keys.vehicles, vehicles.filter(v => v.id !== id));
    Store.remove(CFG.keys.curPrefix  + id);
    Store.remove(CFG.keys.histPrefix + id);
    return true;
  },

  setActive(id)          { Store.set(CFG.keys.activeVehicle, id); },
  getCurrent(id)         { return Store.get(CFG.keys.curPrefix  + id); },
  setCurrent(id, p)      { Store.set(CFG.keys.curPrefix  + id, p); },
  removeCurrent(id)      { Store.remove(CFG.keys.curPrefix  + id); },
  getHistory(id)         { return Store.get(CFG.keys.histPrefix + id, []); },
  setHistory(id, hist)   { Store.set(CFG.keys.histPrefix + id, hist); },
};
