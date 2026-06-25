import { CFG } from './config.js';
import { Store } from './store.js';
import { Utils } from './utils.js';
import { reverseGeocode, normalizeAddress } from './geocoder.js';
import { MapController } from './map.js';
import { CameraController } from './camera.js';
import { VoiceController } from './voice.js';
import { UIController } from './ui.js';
import { ReturnModal } from './return-modal.js';

class FindMyCarApp {
  #state = {
    current:         null,
    history:         [],
    theme:           'dark',
    currentView:     'homeView',
    userPos:         null,
    watchId:         null,
    timerIntervalId: null,
    installPrompt:   null,
    activeNavTarget: null,
    detailItemId:    null,
  };

  #map         = new MapController();
  #camera      = new CameraController();
  #voice       = new VoiceController();
  #ui;
  #returnModal;

  constructor() {
    this.#ui = new UIController({
      onHistoryItemClick: item => this.#openDetailModal(item),
      onHistoryItemNav:   item => this.#openNavModal(item),
      onPhotoClick:       src  => this.#camera.viewPhoto(src),
      onVoiceClick:       async src => {
        try { await this.#voice.playVoice(src); }
        catch { this.#ui.showToast('לא ניתן להפעיל הקלטה', 'error'); }
      },
    });

    this.#returnModal = new ReturnModal({
      onMove:    () => this.#resetParking(),
      onDismiss: () => {},
    });

    this.#init();
  }

  async #init() {
    this.#fixVH();
    window.addEventListener('resize', () => this.#fixVH());

    this.#state.current = Store.get(CFG.keys.current);
    this.#state.history = Store.get(CFG.keys.history, []);
    this.#state.theme   = Store.get(CFG.keys.theme, 'dark');

    this.#ui.applyTheme(this.#state.theme);

    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('./sw.js').catch(e => console.warn('SW reg failed', e));
    }

    this.#bindEvents();
    this.#returnModal.bindEvents();

    setTimeout(() => this.#map.init(this.#state.current), 100);

    this.#ui.updateAll(this.#state);

    if (this.#state.current && !this.#state.current.address) {
      this.#geocodeCurrentParking();
    }

    this.#startLocationWatch();
    this.#setupPWA();

    setTimeout(() => Utils.el('loadingScreen')?.classList.add('fade-out'), 1000);

    if (this.#state.current) this.#startTimer();

    if (this.#state.current) {
      setTimeout(() => this.#returnModal.show(this.#state.current), 1200);
    }

    const params = new URLSearchParams(window.location.search);
    if (params.get('action') === 'save') {
      setTimeout(() => this.#handleSaveNew(), 500);
    }
  }

  #fixVH() {
    document.documentElement.style.setProperty('--vh', `${window.innerHeight * 0.01}px`);
  }

  // ── EVENTS ────────────────────────────────────────────────────
  #bindEvents() {
    document.querySelectorAll('.nav-btn[data-view]').forEach(btn => {
      btn.addEventListener('click', () => this.#showView(btn.dataset.view));
    });

    Utils.el('themeToggleBtn')?.addEventListener('click', () => this.#toggleTheme());

    Utils.el('saveFirstParkingBtn')?.addEventListener('click', () => this.#handleSaveNew());
    Utils.el('fabSaveParking')?.addEventListener('click',      () => this.#handleSaveNew());

    Utils.el('navigateBtn')?.addEventListener('click',     () => this.#openNavModal(this.#state.current));
    Utils.el('shareBtn')?.addEventListener('click',        () => this.#shareParking(this.#state.current));
    Utils.el('resetParkingBtn')?.addEventListener('click', () => this.#ui.openModal('resetModal'));

    Utils.el('confirmResetBtn')?.addEventListener('click', () => {
      this.#ui.closeModal('resetModal');
      this.#resetParking();
    });

    Utils.el('centerParkingBtn')?.addEventListener('click', () => this.#centerOnParking());
    Utils.el('centerUserBtn')?.addEventListener('click',    () => this.#centerOnUser());

    Utils.el('addPhotoBtn')?.addEventListener('click',        () => this.#openCameraModal());
    Utils.el('addVoiceBtn')?.addEventListener('click',        () => this.#openVoiceModal());
    Utils.el('addTextBtn')?.addEventListener('click',         () => this.#openTextModal());
    Utils.el('updateLocationBtn')?.addEventListener('click',  () => this.#updateCurrentLocation());

    Utils.el('switchCameraBtn')?.addEventListener('click', () => this.#camera.switchCamera());
    Utils.el('captureBtn')?.addEventListener('click',      () => this.#camera.capture());
    Utils.el('retakeBtn')?.addEventListener('click',       () => this.#camera.retake());
    Utils.el('savePhotoBtn')?.addEventListener('click',    () => this.#savePhoto());
    Utils.el('photoFilePicker')?.addEventListener('change', e => this.#camera.handleFile(e));

    Utils.el('voiceMicBtn')?.addEventListener('click',   () => this.#voice.toggle());
    Utils.el('voiceMicBtn')?.addEventListener('keydown', e => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this.#voice.toggle(); }
    });
    Utils.el('recordToggleBtn')?.addEventListener('click', () => this.#voice.toggle());
    Utils.el('rerecordBtn')?.addEventListener('click',     () => this.#voice.rerecord());
    Utils.el('saveVoiceBtn')?.addEventListener('click',    () => this.#saveVoice());
    Utils.el('voiceFilePicker')?.addEventListener('change', e => this.#voice.handleFile(e));

    Utils.el('descriptionInput')?.addEventListener('input', e => {
      Utils.el('charCount').textContent = e.target.value.length;
    });
    Utils.el('saveDescBtn')?.addEventListener('click', () => this.#saveDescription());

    Utils.el('clearHistoryBtn')?.addEventListener('click', () => this.#clearHistory());

    Utils.el('detailNavBtn')?.addEventListener('click',    () => this.#navFromDetail());
    Utils.el('detailDeleteBtn')?.addEventListener('click', () => this.#deleteFromDetail());

    Utils.el('openWazeBtn')?.addEventListener('click',       () => this.#navOpen('waze'));
    Utils.el('openGoogleMapsBtn')?.addEventListener('click', () => this.#navOpen('google'));
    Utils.el('openAppleMapsBtn')?.addEventListener('click',  () => this.#navOpen('apple'));

    // Global close handler (data-close attribute on backdrops and close buttons)
    document.addEventListener('click', e => {
      const closeId = e.target.dataset.close || e.target.closest('[data-close]')?.dataset?.close;
      if (closeId) this.#closeModal(closeId);
    });

    Utils.el('installAcceptBtn')?.addEventListener('click',  () => this.#promptInstall());
    Utils.el('installDismissBtn')?.addEventListener('click', () => {
      Utils.el('installBanner').style.display = 'none';
    });

    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') {
        const open = document.querySelector('.modal[style*="flex"]');
        if (open) this.#closeModal(open.id);
      }
    });
  }

  // ── MAP ───────────────────────────────────────────────────────
  #centerOnParking() {
    if (!this.#state.current) return;
    const { lat, lng } = this.#state.current.location;
    this.#map.centerOnParking(lat, lng);
  }

  #centerOnUser() {
    if (!this.#state.userPos) {
      this.#ui.showToast('מחפש מיקום...', 'info');
      return;
    }
    this.#map.centerOnUser(this.#state.userPos.lat, this.#state.userPos.lng);
  }

  // ── GEOLOCATION ───────────────────────────────────────────────
  #startLocationWatch() {
    if (!navigator.geolocation) return;
    this.#state.watchId = navigator.geolocation.watchPosition(
      pos => this.#onPosition(pos),
      err => console.warn('GPS error', err.code),
      { enableHighAccuracy: true, maximumAge: 10000, timeout: 15000 }
    );
  }

  #onPosition(pos) {
    const { latitude: lat, longitude: lng, accuracy } = pos.coords;
    this.#state.userPos = { lat, lng, accuracy };
    this.#map.updateUserMarker(lat, lng);
    this.#ui.updateDistance(this.#state);
  }

  #getCurrentLocation() {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) { reject(new Error('Geolocation not supported')); return; }
      navigator.geolocation.getCurrentPosition(
        pos => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy }),
        err => reject(err),
        { enableHighAccuracy: true, timeout: 12000, maximumAge: 5000 }
      );
    });
  }

  // ── GEOCODING ─────────────────────────────────────────────────
  #geocodeCurrentParking() {
    const p = this.#state.current;
    if (!p) return;
    reverseGeocode(p.location.lat, p.location.lng).then(addr => {
      if (!this.#state.current || this.#state.current.id !== p.id) return;
      if (addr) {
        this.#state.current.address = addr;
        Store.set(CFG.keys.current, this.#state.current);
        this.#ui.updateAddress(this.#state.current);
        this.#map.updateParkingMarkerPopup(addr);
      } else {
        const addrEl = Utils.el('parkingAddressDisplay');
        if (addrEl) addrEl.textContent = `${p.location.lat.toFixed(5)}, ${p.location.lng.toFixed(5)}`;
      }
    });
  }

  // ── PARKING MANAGEMENT ────────────────────────────────────────
  async #handleSaveNew() {
    if (this.#state.current) {
      this.#ui.openModal('resetModal');
    } else {
      await this.#saveNewParking();
    }
  }

  async #saveNewParking() {
    this.#ui.showToast('מאתר מיקום... ⏳', 'info');
    let loc;
    try {
      loc = await this.#getCurrentLocation();
    } catch {
      if (this.#state.userPos) {
        loc = this.#state.userPos;
      } else {
        this.#ui.showToast('לא ניתן לאתר מיקום. בדוק הרשאות GPS.', 'error');
        return;
      }
    }

    const parking = {
      id:          Utils.uuid(),
      timestamp:   new Date().toISOString(),
      location:    { lat: loc.lat, lng: loc.lng, accuracy: loc.accuracy || 0 },
      address:     null,
      description: null,
      photo:       null,
      voice:       null,
      voiceDuration: 0
    };

    this.#state.current = parking;
    Store.set(CFG.keys.current, parking);

    this.#map.addParkingMarker(loc.lat, loc.lng, null);
    this.#map.flyTo(loc.lat, loc.lng, 17);
    this.#ui.updateAll(this.#state);
    this.#startTimer();
    this.#ui.showToast('✅ מיקום חניה נשמר!', 'success');

    reverseGeocode(loc.lat, loc.lng).then(addr => {
      if (!addr || !this.#state.current || this.#state.current.id !== parking.id) return;
      this.#state.current.address = addr;
      Store.set(CFG.keys.current, this.#state.current);
      this.#ui.updateAddress(this.#state.current);
      this.#map.updateParkingMarkerPopup(addr);
    });
  }

  async #updateCurrentLocation() {
    if (!this.#state.current) return;
    this.#ui.showToast('מעדכן מיקום... 🎯', 'info');
    try {
      const loc = await this.#getCurrentLocation();
      this.#state.current.location = { lat: loc.lat, lng: loc.lng, accuracy: loc.accuracy || 0 };
      this.#state.current.address  = null;
      Store.set(CFG.keys.current, this.#state.current);

      this.#map.addParkingMarker(loc.lat, loc.lng, null);
      this.#map.flyTo(loc.lat, loc.lng, 17);

      const addrEl = Utils.el('parkingAddressDisplay');
      if (addrEl) addrEl.textContent = 'מחשב כתובת...';
      const cityEl = Utils.el('parkingCityDisplay');
      if (cityEl) cityEl.style.display = 'none';

      this.#ui.showToast('✅ מיקום עודכן!', 'success');

      reverseGeocode(loc.lat, loc.lng).then(addr => {
        if (!addr || !this.#state.current) return;
        this.#state.current.address = addr;
        Store.set(CFG.keys.current, this.#state.current);
        this.#ui.updateAddress(this.#state.current);
        this.#map.updateParkingMarkerPopup(addr);
      });
    } catch {
      this.#ui.showToast('לא ניתן לעדכן מיקום', 'error');
    }
  }

  #resetParking() {
    if (!this.#state.current) return;
    this.#addToHistory(this.#state.current);
    this.#state.current = null;
    Store.remove(CFG.keys.current);
    this.#map.removeParkingMarker();
    this.#stopTimer();
    this.#ui.updateAll(this.#state);
    this.#ui.showToast('✅ החניה הועברה להיסטוריה', 'success');
  }

  #addToHistory(parking) {
    if (!parking) return;
    this.#state.history.unshift({ ...parking });
    if (this.#state.history.length > CFG.maxHistory) {
      this.#state.history = this.#state.history.slice(0, CFG.maxHistory);
    }
    Store.set(CFG.keys.history, this.#state.history);
  }

  // ── TIMER ─────────────────────────────────────────────────────
  #startTimer() {
    this.#stopTimer();
    this.#state.timerIntervalId = setInterval(() => {
      if (!this.#state.current) { this.#stopTimer(); return; }
      const el = Utils.el('parkingTimerDisplay');
      if (el) el.textContent = Utils.formatDuration(this.#state.current.timestamp);
      const ago = Utils.el('parkingAgoDisplay');
      if (ago) ago.textContent = Utils.formatElapsed(this.#state.current.timestamp);
    }, CFG.timerInterval);
  }

  #stopTimer() {
    if (this.#state.timerIntervalId) {
      clearInterval(this.#state.timerIntervalId);
      this.#state.timerIntervalId = null;
    }
  }

  // ── CAMERA ────────────────────────────────────────────────────
  async #openCameraModal() {
    if (!this.#state.current) { this.#ui.showToast('שמור חניה קודם', 'warning'); return; }
    this.#ui.openModal('photoModal');
    await this.#camera.open();
  }

  async #savePhoto() {
    const photo = this.#camera.getPhoto();
    if (!photo || !this.#state.current) return;
    this.#state.current.photo = photo;
    Store.set(CFG.keys.current, this.#state.current);
    this.#closeModal('photoModal');
    this.#ui.renderAttachments(this.#state.current);
    this.#ui.updateMediaTiles(this.#state.current);
    this.#ui.showToast('📷 תמונה נשמרה!', 'success');
  }

  // ── VOICE ─────────────────────────────────────────────────────
  #openVoiceModal() {
    if (!this.#state.current) { this.#ui.showToast('שמור חניה קודם', 'warning'); return; }
    this.#voice.open();
    this.#ui.openModal('voiceModal');
  }

  async #saveVoice() {
    const { voice, seconds } = this.#voice.getCaptured();
    if (!voice || !this.#state.current) return;
    this.#state.current.voice         = voice;
    this.#state.current.voiceDuration = seconds;
    Store.set(CFG.keys.current, this.#state.current);
    this.#closeModal('voiceModal');
    this.#ui.renderAttachments(this.#state.current);
    this.#ui.updateMediaTiles(this.#state.current);
    this.#ui.showToast('🎙️ הקלטה נשמרה!', 'success');
  }

  // ── DESCRIPTION ───────────────────────────────────────────────
  #openTextModal() {
    if (!this.#state.current) { this.#ui.showToast('שמור חניה קודם', 'warning'); return; }
    const input = Utils.el('descriptionInput');
    if (input) {
      input.value = this.#state.current.description || '';
      Utils.el('charCount').textContent = input.value.length;
    }
    this.#ui.openModal('textModal');
  }

  #saveDescription() {
    const input = Utils.el('descriptionInput');
    if (!input || !this.#state.current) return;
    const text = input.value.trim().slice(0, CFG.maxTextLen);
    this.#state.current.description = text || null;
    Store.set(CFG.keys.current, this.#state.current);
    this.#ui.updateDescription(this.#state.current);
    this.#ui.updateMediaTiles(this.#state.current);
    this.#ui.closeModal('textModal');
    this.#ui.showToast(text ? '✅ תיאור נשמר!' : '🗑️ תיאור נמחק', 'success');
  }

  // ── NAVIGATION & SHARING ──────────────────────────────────────
  #openNavModal(parking) {
    if (!parking?.location) return;
    this.#state.activeNavTarget = parking;
    this.#ui.openModal('navModal');
  }

  #navOpen(app) {
    const p = this.#state.activeNavTarget;
    if (!p?.location) return;
    const { lat, lng } = p.location;
    const urls = {
      waze:   `https://waze.com/ul?ll=${lat},${lng}&navigate=yes`,
      google: `https://maps.google.com/maps?daddr=${lat},${lng}`,
      apple:  `maps://maps.apple.com/?daddr=${lat},${lng}`
    };
    window.open(urls[app], '_blank');
    this.#ui.closeModal('navModal');
  }

  async #shareParking(parking) {
    if (!parking?.location) return;
    const { lat, lng } = parking.location;
    const addrStr = normalizeAddress(parking.address) || `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
    const text = [
      '🚗 מיקום הרכב שלי:',
      addrStr,
      parking.description ? `📝 ${parking.description}` : '',
      `⏰ ${Utils.formatDate(parking.timestamp)} ${Utils.formatTime(parking.timestamp)}`,
      `🗺️ https://maps.google.com/maps?q=${lat},${lng}`
    ].filter(Boolean).join('\n');

    if (navigator.share) {
      try { await navigator.share({ title: 'FindMyCar - מיקום הרכב', text }); return; } catch {}
    }
    try {
      await navigator.clipboard.writeText(text);
      this.#ui.showToast('📋 מיקום הועתק ללוח', 'success');
    } catch {
      this.#ui.showToast('לא ניתן לשתף כעת', 'error');
    }
  }

  // ── HISTORY ───────────────────────────────────────────────────
  #clearHistory() {
    if (!this.#state.history.length) return;
    if (!confirm(`למחוק ${this.#state.history.length} חניות מההיסטוריה?`)) return;
    this.#state.history = [];
    Store.remove(CFG.keys.history);
    this.#ui.updateHistoryView(this.#state);
    this.#ui.updateHistoryBadge(this.#state);
    this.#ui.showToast('🗑️ ההיסטוריה נמחקה', 'info');
  }

  #deleteHistoryItem(id) {
    this.#state.history = this.#state.history.filter(i => i.id !== id);
    Store.set(CFG.keys.history, this.#state.history);
    this.#ui.updateHistoryView(this.#state);
    this.#ui.updateHistoryBadge(this.#state);
    this.#ui.showToast('🗑️ חניה נמחקה', 'info');
  }

  // ── DETAIL MODAL ──────────────────────────────────────────────
  #openDetailModal(item) {
    this.#state.detailItemId    = item.id;
    this.#state.activeNavTarget = item;
    this.#ui.buildDetailModal(item, { onPhotoClick: src => this.#camera.viewPhoto(src) });
    this.#map.destroyDetailMap();
    this.#ui.openModal('detailModal');
    setTimeout(() => {
      const container = Utils.el('detailMapContainer');
      this.#map.initDetailMap(item, container);
    }, 100);
  }

  #navFromDetail() {
    this.#ui.closeModal('detailModal');
    if (this.#state.activeNavTarget) this.#openNavModal(this.#state.activeNavTarget);
  }

  #deleteFromDetail() {
    const id = this.#state.detailItemId;
    this.#closeModal('detailModal');
    if (id) this.#deleteHistoryItem(id);
  }

  // ── MODALS ────────────────────────────────────────────────────
  #closeModal(id) {
    if (id === 'photoModal')  this.#camera.close();
    if (id === 'voiceModal')  this.#voice.close();
    if (id === 'detailModal') this.#map.destroyDetailMap();
    this.#ui.closeModal(id);
  }

  // ── VIEWS ─────────────────────────────────────────────────────
  #showView(viewId) {
    this.#state.currentView = viewId;
    this.#ui.showView(viewId, this.#map);
    if (viewId === 'historyView') this.#ui.updateHistoryView(this.#state);
  }

  // ── THEME ─────────────────────────────────────────────────────
  #toggleTheme() {
    const next = this.#state.theme === 'dark' ? 'light' : 'dark';
    this.#state.theme = next;
    Store.set(CFG.keys.theme, next);
    this.#ui.applyTheme(next);
  }

  // ── PWA ───────────────────────────────────────────────────────
  #setupPWA() {
    window.addEventListener('beforeinstallprompt', e => {
      e.preventDefault();
      this.#state.installPrompt = e;
      setTimeout(() => {
        const banner = Utils.el('installBanner');
        if (banner && !sessionStorage.getItem('fmc_install_dismissed')) {
          banner.style.display = '';
        }
      }, 3000);
    });

    window.addEventListener('appinstalled', () => {
      Utils.el('installBanner').style.display = 'none';
      this.#state.installPrompt = null;
      this.#ui.showToast('✅ האפליקציה הותקנה!', 'success');
    });

    window.addEventListener('online',  () => { Utils.el('offlineIndicator').style.display = 'none'; });
    window.addEventListener('offline', () => { Utils.el('offlineIndicator').style.display = ''; });
  }

  async #promptInstall() {
    Utils.el('installBanner').style.display = 'none';
    if (!this.#state.installPrompt) return;
    this.#state.installPrompt.prompt();
    const { outcome } = await this.#state.installPrompt.userChoice;
    if (outcome === 'dismissed') sessionStorage.setItem('fmc_install_dismissed', '1');
    this.#state.installPrompt = null;
  }
}

window.addEventListener('DOMContentLoaded', () => {
  window.app = new FindMyCarApp();
});
