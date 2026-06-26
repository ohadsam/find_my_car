'use strict';

const CACHE_NAME      = 'findmycar-v1.3.0';
const TILES_CACHE     = 'findmycar-tiles-v1.0.0';
const STATIC_ASSETS = [
  './',
  './index.html',
  './style.css',
  './js/app.js',
  './js/config.js',
  './js/store.js',
  './js/utils.js',
  './js/geocoder.js',
  './js/map.js',
  './js/camera.js',
  './js/voice.js',
  './js/ui.js',
  './js/return-modal.js',
  './js/vehicles.js',
  './manifest.json',
  './icons/icon.svg',
  './icons/icon-192.png',
  './icons/icon-512.png',
  './icons/icon-180.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(STATIC_ASSETS))
      .then(() => self.skipWaiting())
      .catch(err => console.warn('SW cache install failed:', err))
  );
});

self.addEventListener('activate', (event) => {
  const keepCaches = new Set([CACHE_NAME, TILES_CACHE]);
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => !keepCaches.has(k)).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.method !== 'GET') return;

  const url = new URL(event.request.url);
  if (!url.protocol.startsWith('http')) return;

  const h = url.hostname;
  const isNominatim  = h.includes('nominatim.openstreetmap');
  const isMapTile    = h.includes('tile.openstreetmap.org') || h.includes('a.tile.') || h.includes('b.tile.') || h.includes('c.tile.');
  const isCDN        = h.includes('unpkg.com') || h.includes('fonts.googleapis.com') || h.includes('fonts.gstatic.com');

  // Nominatim: network-only (real-time geo data)
  if (isNominatim) {
    event.respondWith(
      fetch(event.request)
        .catch(() => new Response('{}', { headers: { 'Content-Type': 'application/json' } }))
    );
    return;
  }

  // Map tiles: stale-while-revalidate
  if (isMapTile) {
    event.respondWith(
      caches.open(TILES_CACHE).then(async cache => {
        const cached = await cache.match(event.request);
        const networkFetch = fetch(event.request).then(res => {
          if (res.ok) cache.put(event.request, res.clone());
          return res;
        }).catch(() => null);
        return cached || networkFetch;
      })
    );
    return;
  }

  // External CDN (Leaflet, Google Fonts): cache-first
  if (isCDN) {
    event.respondWith(
      caches.match(event.request).then(cached => {
        if (cached) return cached;
        return fetch(event.request).then(res => {
          if (res.ok) {
            caches.open(CACHE_NAME).then(c => c.put(event.request, res.clone()));
          }
          return res;
        }).catch(() => null);
      })
    );
    return;
  }

  // App shell: cache-first with network fallback
  event.respondWith(
    caches.match(event.request).then(cached => {
      if (cached) return cached;
      return fetch(event.request).then(res => {
        if (res.ok) {
          caches.open(CACHE_NAME).then(c => c.put(event.request, res.clone()));
        }
        return res;
      }).catch(() => caches.match('./index.html'));
    })
  );
});
