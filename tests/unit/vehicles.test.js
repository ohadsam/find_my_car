import { describe, it, expect, beforeEach, vi } from 'vitest';

vi.mock('../../js/config.js', () => ({
  CFG: {
    keys: {
      vehicles:      'fmc_vehicles_v1',
      activeVehicle: 'fmc_active_v1',
      curPrefix:     'fmc_cur_',
      histPrefix:    'fmc_hist_',
      legacyCurrent: 'fmc_current_v1',
      legacyHistory: 'fmc_history_v1',
    },
    maxVehicles:       5,
    maxVehicleNameLen: 30,
  },
}));

vi.mock('../../js/utils.js', () => ({
  Utils: {
    uuid: () => 'test-id-' + Math.random().toString(36).slice(2, 7),
    escHtml: s => s,
    el: () => null,
  },
}));

const { VehicleController } = await import('../../js/vehicles.js');

describe('VehicleController', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  describe('migrate', () => {
    it('creates a default vehicle if none exist', () => {
      VehicleController.migrate();
      const vehicles = VehicleController.getAll();
      expect(vehicles).toHaveLength(1);
      expect(vehicles[0].name).toBe('הרכב שלי');
      expect(vehicles[0].icon).toBe('🚗');
    });

    it('migrates legacy current data', () => {
      const legacyParking = { id: 'abc', timestamp: '2025-01-01T00:00:00.000Z', location: { lat: 32, lng: 34 } };
      localStorage.setItem('fmc_current_v1', JSON.stringify(legacyParking));
      VehicleController.migrate();
      const id = VehicleController.getActiveId();
      expect(VehicleController.getCurrent(id)).toEqual(legacyParking);
    });

    it('does not overwrite if vehicles already exist', () => {
      VehicleController.migrate();
      const firstId = VehicleController.getActiveId();
      VehicleController.migrate(); // second call
      expect(VehicleController.getActiveId()).toBe(firstId);
      expect(VehicleController.getAll()).toHaveLength(1);
    });
  });

  describe('add / getAll', () => {
    beforeEach(() => { VehicleController.migrate(); });

    it('adds a new vehicle', () => {
      const v = VehicleController.add('My Van', '🚐');
      expect(v).not.toBeNull();
      expect(VehicleController.getAll()).toHaveLength(2);
    });

    it('trims name and enforces max length', () => {
      const long = 'a'.repeat(50);
      const v = VehicleController.add(long, '🚗');
      expect(v.name.length).toBeLessThanOrEqual(30);
    });

    it('refuses to add beyond maxVehicles', () => {
      for (let i = 0; i < 4; i++) VehicleController.add(`car${i}`, '🚗');
      const overflow = VehicleController.add('one too many', '🚗');
      expect(overflow).toBeNull();
    });

    it('uses "רכב" as fallback for empty name', () => {
      const v = VehicleController.add('', '🚗');
      expect(v.name).toBe('רכב');
    });
  });

  describe('update', () => {
    beforeEach(() => { VehicleController.migrate(); });

    it('updates name and icon of existing vehicle', () => {
      const id = VehicleController.getActiveId();
      VehicleController.update(id, 'Updated Name', '🚙');
      const v = VehicleController.getById(id);
      expect(v.name).toBe('Updated Name');
      expect(v.icon).toBe('🚙');
    });

    it('returns false for unknown id', () => {
      expect(VehicleController.update('nonexistent', 'x', '🚗')).toBe(false);
    });
  });

  describe('remove', () => {
    beforeEach(() => { VehicleController.migrate(); });

    it('refuses to remove the last vehicle', () => {
      const id = VehicleController.getActiveId();
      expect(VehicleController.remove(id)).toBe(false);
    });

    it('removes a vehicle when more than one exist', () => {
      const v = VehicleController.add('Second', '🚙');
      expect(VehicleController.remove(v.id)).toBe(true);
      expect(VehicleController.getAll()).toHaveLength(1);
    });

    it('cleans up associated storage on remove', () => {
      const v = VehicleController.add('Temp', '🚗');
      VehicleController.setCurrent(v.id, { id: 'x' });
      VehicleController.remove(v.id);
      expect(VehicleController.getCurrent(v.id)).toBeNull();
    });
  });

  describe('current / history storage', () => {
    beforeEach(() => { VehicleController.migrate(); });

    it('sets and gets current parking per vehicle', () => {
      const id = VehicleController.getActiveId();
      const p = { id: 'p1', timestamp: new Date().toISOString() };
      VehicleController.setCurrent(id, p);
      expect(VehicleController.getCurrent(id)).toEqual(p);
    });

    it('removes current parking', () => {
      const id = VehicleController.getActiveId();
      VehicleController.setCurrent(id, { id: 'p1' });
      VehicleController.removeCurrent(id);
      expect(VehicleController.getCurrent(id)).toBeNull();
    });

    it('sets and gets history per vehicle', () => {
      const id = VehicleController.getActiveId();
      const hist = [{ id: 'h1' }, { id: 'h2' }];
      VehicleController.setHistory(id, hist);
      expect(VehicleController.getHistory(id)).toEqual(hist);
    });
  });
});
