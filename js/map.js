import { CFG } from './config.js';
import { Utils } from './utils.js';

export class MapController {
  #map        = null;
  #parkMarker = null;
  #userMarker = null;
  #detailMap  = null;

  init(currentParking) {
    if (this.#map) return;
    try {
      const L = window.L;
      const center = currentParking?.location
        ? [currentParking.location.lat, currentParking.location.lng]
        : CFG.defaultCenter;

      this.#map = L.map('map', { center, zoom: 15, zoomControl: true, attributionControl: true });

      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© <a href="https://openstreetmap.org">OpenStreetMap</a>',
        maxZoom: 19,
        crossOrigin: true
      }).addTo(this.#map);

      this.#map.zoomControl.setPosition('bottomleft');

      if (currentParking) {
        this.addParkingMarker(
          currentParking.location.lat,
          currentParking.location.lng,
          currentParking.address
        );
      }

      this.#map.on('tileloaderror', () => {
        const el = document.getElementById('offlineIndicator');
        if (el) el.style.display = 'block';
      });
    } catch (e) {
      console.error('Map init failed', e);
    }
  }

  addParkingMarker(lat, lng, address) {
    if (!this.#map) return;
    const L = window.L;
    if (this.#parkMarker) this.#map.removeLayer(this.#parkMarker);

    const displayAddr = address && typeof address === 'object' ? address.display : address;

    const icon = L.divIcon({
      className: '',
      html: `<div class="parking-marker-icon" style="width:22px;height:22px;"></div>`,
      iconSize: [22, 22], iconAnchor: [11, 22], popupAnchor: [0, -24]
    });

    this.#parkMarker = L.marker([lat, lng], { icon })
      .addTo(this.#map)
      .bindPopup(this.#popupHtml(displayAddr));
  }

  updateParkingMarkerPopup(address) {
    if (!this.#parkMarker) return;
    const displayAddr = address && typeof address === 'object' ? address.display : address;
    this.#parkMarker.bindPopup(this.#popupHtml(displayAddr));
  }

  #popupHtml(addr) {
    const safe = Utils.escHtml(addr);
    return `<div style="direction:rtl;text-align:right;font-family:'Heebo',sans-serif;font-size:13px;">` +
      `<b>🚗 מיקום הרכב</b>${safe ? '<br>' + safe : ''}</div>`;
  }

  removeParkingMarker() {
    if (this.#parkMarker && this.#map) {
      this.#map.removeLayer(this.#parkMarker);
      this.#parkMarker = null;
    }
  }

  updateUserMarker(lat, lng) {
    if (!this.#map) return;
    const L = window.L;
    const icon = L.divIcon({
      className: '',
      html: `<div class="user-marker-icon" style="width:16px;height:16px;"></div>`,
      iconSize: [16, 16], iconAnchor: [8, 8]
    });
    if (this.#userMarker) {
      this.#userMarker.setLatLng([lat, lng]);
    } else {
      this.#userMarker = L.marker([lat, lng], { icon }).addTo(this.#map);
    }
  }

  flyTo(lat, lng, zoom = 17) {
    this.#map?.flyTo([lat, lng], zoom, { duration: 1 });
  }

  centerOnParking(lat, lng) {
    if (!this.#map) return;
    this.#map.flyTo([lat, lng], 17, { duration: 1 });
    this.#parkMarker?.openPopup();
  }

  centerOnUser(lat, lng) {
    this.#map?.flyTo([lat, lng], 16, { duration: 1 });
  }

  invalidateSize() {
    this.#map?.invalidateSize();
  }

  initDetailMap(item, container) {
    this.destroyDetailMap();
    if (!container) return;
    container.innerHTML = '';
    container.style.cssText = 'height:150px;border-radius:10px;overflow:hidden;';
    try {
      const L = window.L;
      const miniMap = L.map(container, {
        zoomControl: false, dragging: false, touchZoom: false,
        scrollWheelZoom: false, doubleClickZoom: false
      });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 18 })
        .addTo(miniMap);
      const { lat, lng } = item.location;
      miniMap.setView([lat, lng], 16);
      const icon = L.divIcon({
        className: '',
        html: `<div class="parking-marker-icon" style="width:18px;height:18px;"></div>`,
        iconSize: [18, 18], iconAnchor: [9, 18]
      });
      L.marker([lat, lng], { icon }).addTo(miniMap);
      this.#detailMap = miniMap;
    } catch {
      container.innerHTML =
        '<div style="text-align:center;padding-top:60px;color:var(--text-secondary)">🗺️ מפה לא זמינה</div>';
    }
  }

  destroyDetailMap() {
    if (this.#detailMap) {
      try { this.#detailMap.remove(); } catch {}
      this.#detailMap = null;
    }
  }
}
