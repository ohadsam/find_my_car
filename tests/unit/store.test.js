import { describe, it, expect, beforeEach, vi } from 'vitest';

// Vitest/jsdom includes localStorage; use it directly
const { Store } = await import('../../js/store.js');

describe('Store', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns default when key is absent', () => {
    expect(Store.get('missing_key')).toBeNull();
    expect(Store.get('missing_key', 'default')).toBe('default');
  });

  it('sets and gets a string value', () => {
    Store.set('key1', 'hello');
    expect(Store.get('key1')).toBe('hello');
  });

  it('sets and gets an object', () => {
    const obj = { lat: 32.08, lng: 34.78 };
    Store.set('parking', obj);
    expect(Store.get('parking')).toEqual(obj);
  });

  it('sets and gets an array', () => {
    Store.set('history', [1, 2, 3]);
    expect(Store.get('history')).toEqual([1, 2, 3]);
  });

  it('removes a key', () => {
    Store.set('temp', 'value');
    Store.remove('temp');
    expect(Store.get('temp')).toBeNull();
  });

  it('returns true on successful set', () => {
    expect(Store.set('x', 1)).toBe(true);
  });

  it('handles null value gracefully', () => {
    Store.set('nullval', null);
    expect(Store.get('nullval')).toBeNull();
  });
});

describe('Store.exportAll / importAll', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('exports only fmc_-prefixed keys', () => {
    Store.set('fmc_theme_v1', 'dark');
    Store.set('fmc_vehicles_v1', [{ id: '1' }]);
    localStorage.setItem('unrelated_key', 'should not appear');

    const exported = Store.exportAll();
    expect(Object.keys(exported).sort()).toEqual(['fmc_theme_v1', 'fmc_vehicles_v1']);
    expect(exported.fmc_theme_v1).toBe(JSON.stringify('dark'));
  });

  it('round-trips data through export then import into a clean store', () => {
    Store.set('fmc_theme_v1', 'light');
    Store.set('fmc_vehicles_v1', [{ id: 'abc', name: 'Tesla' }]);
    const backup = Store.exportAll();

    localStorage.clear();
    Store.importAll(backup);

    expect(Store.get('fmc_theme_v1')).toBe('light');
    expect(Store.get('fmc_vehicles_v1')).toEqual([{ id: 'abc', name: 'Tesla' }]);
  });

  it('ignores non-fmc_ keys and rejects invalid input', () => {
    expect(() => Store.importAll(null)).toThrow();
    expect(() => Store.importAll('not an object')).toThrow();

    Store.importAll({ fmc_theme_v1: JSON.stringify('dark'), evil_key: JSON.stringify('x') });
    expect(Store.get('fmc_theme_v1')).toBe('dark');
    expect(localStorage.getItem('evil_key')).toBeNull();
  });
});
