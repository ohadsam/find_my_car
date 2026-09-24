import { describe, it, expect, vi, afterEach } from 'vitest';
import { reverseGeocodeDetailed } from '../../js/geocoder.js';

function mockFetch(impl) {
  globalThis.fetch = vi.fn(impl);
}

const okJson = body => async () => ({ ok: true, json: async () => body });

afterEach(() => { vi.restoreAllMocks(); delete globalThis.fetch; });

describe('reverseGeocodeDetailed', () => {
  it('returns ok with a street address', async () => {
    mockFetch(okJson({ address: { road: 'הרצל', house_number: '12', city: 'תל אביב', suburb: 'לב העיר' } }));
    const r = await reverseGeocodeDetailed(32, 34);
    expect(r.status).toBe('ok');
    expect(r.addr).toEqual({ display: 'הרצל 12 תל אביב', street: 'הרצל', houseNumber: '12', city: 'תל אביב', neighborhood: 'לב העיר' });
  });

  it('returns ok for a locality with no street (a village)', async () => {
    mockFetch(okJson({ address: { village: 'נהלל' } }));
    const r = await reverseGeocodeDetailed(32, 35);
    expect(r.status).toBe('ok');
    expect(r.addr.display).toBe('נהלל');
  });

  it('returns none for an open area with only a region name', async () => {
    mockFetch(okJson({ display_name: 'מועצה אזורית עמק יזרעאל, מחוז הצפון, ישראל', address: { county: 'עמק יזרעאל', state: 'מחוז הצפון' } }));
    expect((await reverseGeocodeDetailed(32.6, 35.2)).status).toBe('none');
  });

  it('returns none when Nominatim reports it cannot geocode the point', async () => {
    mockFetch(okJson({ error: 'Unable to geocode' }));
    expect((await reverseGeocodeDetailed(0, 0)).status).toBe('none');
  });

  it('returns failed for an empty body (a masked failure), never none', async () => {
    mockFetch(okJson({}));
    expect((await reverseGeocodeDetailed(32, 34)).status).toBe('failed');
  });

  it('returns failed on a network error, so the caller retries', async () => {
    mockFetch(async () => { throw new TypeError('network'); });
    expect((await reverseGeocodeDetailed(32, 34)).status).toBe('failed');
  });

  it('returns failed on an HTTP error such as rate limiting', async () => {
    mockFetch(async () => ({ ok: false, status: 429, json: async () => ({}) }));
    expect((await reverseGeocodeDetailed(32, 34)).status).toBe('failed');
  });
});
