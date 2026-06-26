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
