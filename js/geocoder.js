import { CFG } from './config.js';

/**
 * Reverse-geocode a coordinate to { display, street, houseNumber, city,
 * neighborhood }, telling "there is no address here" apart from "the lookup
 * failed". Only the latter is worth retrying: a request made while the
 * app is backgrounded (a widget or Bluetooth save) routinely times out, and
 * treating that the same as an open field left such parkings as bare
 * coordinates forever.
 *
 * @returns {Promise<{status:'ok', addr:object}|{status:'none'}|{status:'failed'}>}
 */
export async function reverseGeocodeDetailed(lat, lng, timeoutMs = CFG.geocodeTimeout) {
  let data;
  try {
    const ctrl = new AbortController();
    const tid = setTimeout(() => ctrl.abort(), timeoutMs);
    const res = await fetch(
      `${CFG.nominatim}&lat=${lat}&lon=${lng}&zoom=18`,
      { signal: ctrl.signal, headers: { 'Accept-Language': 'he,en' } }
    );
    clearTimeout(tid);
    if (!res.ok) return { status: 'failed' };
    data = await res.json();
  } catch {
    return { status: 'failed' };
  }
  // A real Nominatim answer always carries either `address` or `error`; an
  // empty body is some layer (an older cached service worker) masking a
  // failure, and must stay retryable.
  if (!data || (!data.address && !data.error)) return { status: 'failed' };
  const addr = _parseAddress(data);
  return addr ? { status: 'ok', addr } : { status: 'none' };
}

// Returns null for a spot with no street and no named locality (an open field,
// a forest) — Nominatim's display_name there is only a regional council or
// district, which is less useful than the coordinates themselves.
function _parseAddress(data) {
  if (!data || data.error) return null;
  const a = data.address || {};
  const street       = a.road || a.pedestrian || a.footway || a.path || null;
  const houseNumber  = a.house_number || null;
  const city         = a.city || a.town || a.village || a.hamlet || a.municipality || null;
  const neighborhood = a.suburb || a.neighbourhood || a.quarter || null;

  if (!street && !city) return null;
  const display = [street, houseNumber, city].filter(Boolean).join(' ');
  return { display, street, houseNumber, city, neighborhood };
}

/**
 * Convert either an AddressObj or a legacy string to a display string.
 */
export function normalizeAddress(addr) {
  if (!addr) return null;
  if (typeof addr === 'string') return addr;
  return addr.display || null;
}
