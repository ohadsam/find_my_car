import { CFG } from './config.js';

/**
 * Reverse-geocode a coordinate.
 * Returns { display, street, houseNumber, city, neighborhood } | null
 */
export async function reverseGeocode(lat, lng) {
  try {
    const ctrl = new AbortController();
    const tid = setTimeout(() => ctrl.abort(), CFG.geocodeTimeout);
    const res = await fetch(
      `${CFG.nominatim}&lat=${lat}&lon=${lng}&zoom=18`,
      { signal: ctrl.signal, headers: { 'Accept-Language': 'he,en' } }
    );
    clearTimeout(tid);
    if (!res.ok) return null;
    const data = await res.json();
    return _parseAddress(data);
  } catch {
    return null;
  }
}

function _parseAddress(data) {
  const a = data.address || {};
  const street       = a.road || a.pedestrian || a.footway || a.path || null;
  const houseNumber  = a.house_number || null;
  const city         = a.city || a.town || a.village || a.municipality || null;
  const neighborhood = a.suburb || a.neighbourhood || a.quarter || null;

  const parts = [street, houseNumber, city].filter(Boolean);
  const display = parts.length > 0
    ? parts.join(' ')
    : data.display_name?.split(',').slice(0, 3).join(',') || null;

  if (!display) return null;
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
