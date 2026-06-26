import { describe, it, expect, beforeEach, vi } from 'vitest';

// Mock CFG before importing utils
vi.mock('../../js/config.js', () => ({
  CFG: {
    maxImgWidth: 900,
    imgQuality: 0.72,
  },
}));

const { Utils } = await import('../../js/utils.js');

describe('Utils.uuid', () => {
  it('generates a non-empty string', () => {
    expect(typeof Utils.uuid()).toBe('string');
    expect(Utils.uuid().length).toBeGreaterThan(0);
  });

  it('generates unique values', () => {
    const ids = new Set(Array.from({ length: 100 }, () => Utils.uuid()));
    expect(ids.size).toBe(100);
  });
});

describe('Utils.escHtml', () => {
  it('escapes & < > "', () => {
    expect(Utils.escHtml('a&b')).toBe('a&amp;b');
    expect(Utils.escHtml('<script>')).toBe('&lt;script&gt;');
    expect(Utils.escHtml('"hello"')).toBe('&quot;hello&quot;');
  });

  it('returns empty string for falsy input', () => {
    expect(Utils.escHtml('')).toBe('');
    expect(Utils.escHtml(null)).toBe('');
    expect(Utils.escHtml(undefined)).toBe('');
  });

  it('handles plain strings without special chars', () => {
    expect(Utils.escHtml('hello world')).toBe('hello world');
  });
});

describe('Utils.distance (Haversine)', () => {
  it('returns 0 for same coordinates', () => {
    expect(Utils.distance(32, 34, 32, 34)).toBe(0);
  });

  it('returns a positive number for different points', () => {
    const d = Utils.distance(32.0853, 34.7818, 31.7767, 35.2345);
    expect(d).toBeGreaterThan(0);
  });

  it('Tel Aviv to Jerusalem is roughly 50-65 km', () => {
    const d = Utils.distance(32.0853, 34.7818, 31.7767, 35.2345);
    expect(d).toBeGreaterThan(50000);
    expect(d).toBeLessThan(65000);
  });
});

describe('Utils.formatDistance', () => {
  it('formats meters below 1000', () => {
    expect(Utils.formatDistance(500)).toBe('500 מטר');
  });

  it('formats km above 1000', () => {
    expect(Utils.formatDistance(1500)).toBe('1.5 ק"מ');
  });
});

describe('Utils.dataUrlToFile', () => {
  it('converts a data URL to a File object', () => {
    // Minimal 1px white JPEG
    const jpeg1x1 = 'data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAT8AEP/Z';
    const file = Utils.dataUrlToFile(jpeg1x1, 'test.jpg');
    expect(file).toBeInstanceOf(File);
    expect(file.name).toBe('test.jpg');
    expect(file.type).toBe('image/jpeg');
    expect(file.size).toBeGreaterThan(0);
  });
});
