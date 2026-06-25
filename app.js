'use strict';

/* ============================================================
   FIND MY CAR - Application Logic
   ============================================================ */

// ── CONFIG ──────────────────────────────────────────────────
const CFG = {
  version: '1.0.0',
  keys: {
    current: 'fmc_current_v1',
    history: 'fmc_history_v1',
    theme:   'fmc_theme_v1'
  },
  maxHistory:      30,
  maxImgWidth:     900,
  imgQuality:      0.72,
  maxTextLen:      300,
  toastDuration:   3000,
  timerInterval:   1000,
  geocodeTimeout:  6000,
  geocodeRetry:    2,
  defaultCenter:   [31.7767, 35.2345],
  nominatim:       'https://nominatim.openstreetmap.org/reverse?format=json&addressdetails=1'
};

// ── STORAGE ──────────────────────────────────────────────────
const Store = {
  get(k, def = null) {
    try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : def; }
    catch { return def; }
  },
  set(k, v) {
    try { localStorage.setItem(k, JSON.stringify(v)); return true; }
    catch (e) { console.warn('Storage write failed', e); return false; }
  },
  remove(k) { try { localStorage.removeItem(k); } catch {} }
};

// ── UTILS ────────────────────────────────────────────────────
const Utils = {
  uuid() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  },

  formatTime(date) {
    return new Date(date).toLocaleTimeString('he-IL', { hour: '2-digit', minute: '2-digit' });
  },

  formatDate(date) {
    const d = new Date(date);
    const today = new Date();
    const yesterday = new Date(today); yesterday.setDate(yesterday.getDate() - 1);
    if (d.toDateString() === today.toDateString()) return 'היום';
    if (d.toDateString() === yesterday.toDateString()) return 'אתמול';
    return d.toLocaleDateString('he-IL', { day: 'numeric', month: 'long', year: 'numeric' });
  },

  formatElapsed(date) {
    const secs = Math.floor((Date.now() - new Date(date)) / 1000);
    if (secs < 60)   return `לפני ${secs} שניות`;
    const mins = Math.floor(secs / 60);
    if (mins < 60)   return `לפני ${mins} דקות`;
    const hrs  = Math.floor(mins / 60);
    if (hrs < 24)    return `לפני ${hrs} שעות`;
    return `לפני ${Math.floor(hrs / 24)} ימים`;
  },

  formatDuration(date) {
    const secs = Math.floor((Date.now() - new Date(date)) / 1000);
    const h = Math.floor(secs / 3600);
    const m = Math.floor((secs % 3600) / 60);
    const s = secs % 60;
    if (h > 0) return `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
    return `${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
  },

  // Haversine distance in meters
  distance(lat1, lng1, lat2, lng2) {
    const R = 6371000;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat/2)**2 +
              Math.cos(lat1 * Math.PI/180) * Math.cos(lat2 * Math.PI/180) *
              Math.sin(dLng/2)**2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
  },

  formatDistance(meters) {
    if (meters < 1000) return `${Math.round(meters)} מטר`;
    return `${(meters / 1000).toFixed(1)} ק"מ`;
  },

  async compressImage(dataUrl) {
    return new Promise(resolve => {
      const img = new Image();
      img.onload = () => {
        const canvas = document.createElement('canvas');
        const scale = Math.min(CFG.maxImgWidth / img.width, 1);
        canvas.width  = Math.round(img.width  * scale);
        canvas.height = Math.round(img.height * scale);
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
        resolve(canvas.toDataURL('image/jpeg', CFG.imgQuality));
      };
      img.onerror = () => resolve(dataUrl);
      img.src = dataUrl;
    });
  },

  blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onloadend = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  },

  el(id) { return document.getElementById(id); }
};

// ── MAIN APP ─────────────────────────────────────────────────
class FindMyCarApp {
  constructor() {
    this.state = {
      current:     null,
      history:     [],
      theme:       'dark',
      currentView: 'homeView',
      // Map
      map:          null,
      parkMarker:   null,
      userMarker:   null,
      accuracy:     null,
      // Location
      userPos:      null,
      watchId:      null,
      // Camera
      cameraStream: null,
      facingMode:   'environment',
      capturedPhoto: null,
      // Voice
      mediaRecorder:   null,
      audioChunks:     [],
      isRecording:     false,
      recTimerId:      null,
      recSeconds:      0,
      capturedVoice:   null,
      // UI
      timerIntervalId: null,
      installPrompt:   null,
      // Detail modal active item
      detailItemId:    null,
      detailMap:       null,
      // Voice blob URL (for revoke on re-record)
      voiceBlobUrl:    null
    };
    this._init();
  }

  async _init() {
    this._fixVH();
    window.addEventListener('resize', () => this._fixVH());

    // Load persisted data
    this.state.current = Store.get(CFG.keys.current);
    this.state.history = Store.get(CFG.keys.history, []);
    this.state.theme   = Store.get(CFG.keys.theme, 'dark');

    // Apply theme
    this._applyTheme(this.state.theme);

    // Register service worker
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('./sw.js').catch(e => console.warn('SW reg failed', e));
    }

    // Bind all events
    this._bindEvents();

    // Initialize map after a short delay to ensure DOM is ready
    setTimeout(() => this._initMap(), 100);

    // Update UI
    this._updateUI();

    // Start location tracking
    this._startLocationWatch();

    // PWA
    this._setupPWA();

    // Hide loading
    setTimeout(() => {
      const ls = Utils.el('loadingScreen');
      if (ls) ls.classList.add('fade-out');
    }, 1000);

    // Start parking timer if parked
    if (this.state.current) this._startTimer();

    // Handle shortcut from manifest
    const params = new URLSearchParams(window.location.search);
    if (params.get('action') === 'save') {
      setTimeout(() => this._handleSaveNew(), 500);
    }
  }

  _fixVH() {
    document.documentElement.style.setProperty('--vh', `${window.innerHeight * 0.01}px`);
  }

  // ── EVENTS ──────────────────────────────────────────────────
  _bindEvents() {
    // Navigation tabs
    document.querySelectorAll('.nav-btn[data-view]').forEach(btn => {
      btn.addEventListener('click', () => this._showView(btn.dataset.view));
    });

    // Theme toggle
    Utils.el('themeToggleBtn')?.addEventListener('click', () => this._toggleTheme());

    // Save parking buttons
    Utils.el('saveFirstParkingBtn')?.addEventListener('click', () => this._handleSaveNew());
    Utils.el('fabSaveParking')?.addEventListener('click',      () => this._handleSaveNew());

    // Parking card actions
    Utils.el('navigateBtn')?.addEventListener('click',    () => this._openNavModal(this.state.current));
    Utils.el('shareBtn')?.addEventListener('click',       () => this._shareParking(this.state.current));
    Utils.el('resetParkingBtn')?.addEventListener('click', () => this._openModal('resetModal'));

    // Reset confirm
    Utils.el('confirmResetBtn')?.addEventListener('click', () => {
      this._closeModal('resetModal');
      this._resetParking();
    });

    // Map controls
    Utils.el('centerParkingBtn')?.addEventListener('click', () => this._centerOnParking());
    Utils.el('centerUserBtn')?.addEventListener('click',    () => this._centerOnUser());

    // Media tiles
    Utils.el('addPhotoBtn')?.addEventListener('click',    () => this._openCameraModal());
    Utils.el('addVoiceBtn')?.addEventListener('click',    () => this._openVoiceModal());
    Utils.el('addTextBtn')?.addEventListener('click',     () => this._openTextModal());
    Utils.el('updateLocationBtn')?.addEventListener('click', () => this._updateCurrentLocation());

    // Camera modal
    Utils.el('switchCameraBtn')?.addEventListener('click', () => this._switchCamera());
    Utils.el('captureBtn')?.addEventListener('click',      () => this._capturePhoto());
    Utils.el('retakeBtn')?.addEventListener('click',       () => this._retakePhoto());
    Utils.el('savePhotoBtn')?.addEventListener('click',    () => this._savePhoto());
    Utils.el('photoFilePicker')?.addEventListener('change', e => this._handlePhotoFile(e));

    // Voice modal
    Utils.el('voiceMicBtn')?.addEventListener('click',    () => this._toggleRecording());
    Utils.el('voiceMicBtn')?.addEventListener('keydown', e => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); this._toggleRecording(); }
    });
    Utils.el('recordToggleBtn')?.addEventListener('click', () => this._toggleRecording());
    Utils.el('rerecordBtn')?.addEventListener('click',    () => this._rerecord());
    Utils.el('saveVoiceBtn')?.addEventListener('click',   () => this._saveVoice());
    Utils.el('voiceFilePicker')?.addEventListener('change', e => this._handleVoiceFile(e));

    // Text modal
    Utils.el('descriptionInput')?.addEventListener('input', e => {
      Utils.el('charCount').textContent = e.target.value.length;
    });
    Utils.el('saveDescBtn')?.addEventListener('click', () => this._saveDescription());

    // History
    Utils.el('clearHistoryBtn')?.addEventListener('click', () => this._clearHistory());

    // Detail modal
    Utils.el('detailNavBtn')?.addEventListener('click',    () => this._navFromDetail());
    Utils.el('detailDeleteBtn')?.addEventListener('click', () => this._deleteFromDetail());

    // Navigation modal
    Utils.el('openWazeBtn')?.addEventListener('click',       () => this._navOpen('waze'));
    Utils.el('openGoogleMapsBtn')?.addEventListener('click', () => this._navOpen('google'));
    Utils.el('openAppleMapsBtn')?.addEventListener('click',  () => this._navOpen('apple'));

    // Modal backdrop and close buttons
    document.addEventListener('click', e => {
      const closeId = e.target.dataset.close || e.target.closest('[data-close]')?.dataset?.close;
      if (closeId) this._closeModal(closeId);
    });

    // Install banner
    Utils.el('installAcceptBtn')?.addEventListener('click',  () => this._promptInstall());
    Utils.el('installDismissBtn')?.addEventListener('click', () => {
      Utils.el('installBanner').style.display = 'none';
    });

    // Keyboard: close modal on Escape
    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') {
        const open = document.querySelector('.modal[style*="flex"], .modal:not([style*="none"])');
        if (open && open.style.display !== 'none') this._closeModal(open.id);
      }
    });
  }

  // ── MAP ─────────────────────────────────────────────────────
  _initMap() {
    if (this.state.map) return;
    try {
      const center = this.state.current?.location
        ? [this.state.current.location.lat, this.state.current.location.lng]
        : CFG.defaultCenter;

      const map = L.map('map', {
        center,
        zoom: 15,
        zoomControl: true,
        attributionControl: true
      });

      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© <a href="https://openstreetmap.org">OpenStreetMap</a>',
        maxZoom: 19,
        crossOrigin: true
      }).addTo(map);

      map.zoomControl.setPosition('bottomleft');

      this.state.map = map;

      if (this.state.current) {
        this._addParkingMarker(this.state.current.location.lat, this.state.current.location.lng);
      }

      map.on('tileloaderror', () => {
        const ind = Utils.el('offlineIndicator');
        if (ind) ind.style.display = 'block';
      });

    } catch (e) {
      console.error('Map init failed', e);
    }
  }

  _addParkingMarker(lat, lng) {
    if (!this.state.map) return;
    if (this.state.parkMarker) this.state.map.removeLayer(this.state.parkMarker);

    const icon = L.divIcon({
      className: '',
      html: `<div class="parking-marker-icon" style="width:22px;height:22px;"></div>`,
      iconSize: [22, 22],
      iconAnchor: [11, 22],
      popupAnchor: [0, -24]
    });

    this.state.parkMarker = L.marker([lat, lng], { icon })
      .addTo(this.state.map)
      .bindPopup(`<div style="direction:rtl;text-align:right;font-family:'Heebo',sans-serif;font-size:13px;"><b>🚗 מיקום הרכב</b>${this.state.current?.address ? '<br>' + this.state.current.address : ''}</div>`);
  }

  _updateUserMarker(lat, lng) {
    if (!this.state.map) return;
    const icon = L.divIcon({
      className: '',
      html: `<div class="user-marker-icon" style="width:16px;height:16px;"></div>`,
      iconSize: [16, 16],
      iconAnchor: [8, 8]
    });
    if (this.state.userMarker) {
      this.state.userMarker.setLatLng([lat, lng]);
    } else {
      this.state.userMarker = L.marker([lat, lng], { icon }).addTo(this.state.map);
    }
  }

  _centerOnParking() {
    if (!this.state.map || !this.state.current) return;
    const { lat, lng } = this.state.current.location;
    this.state.map.flyTo([lat, lng], 17, { duration: 1 });
    if (this.state.parkMarker) this.state.parkMarker.openPopup();
  }

  _centerOnUser() {
    if (!this.state.map || !this.state.userPos) {
      this.showToast('מחפש מיקום...', 'info');
      return;
    }
    this.state.map.flyTo([this.state.userPos.lat, this.state.userPos.lng], 16, { duration: 1 });
  }

  // ── GEOLOCATION ─────────────────────────────────────────────
  _startLocationWatch() {
    if (!navigator.geolocation) return;
    this.state.watchId = navigator.geolocation.watchPosition(
      pos => this._onPosition(pos),
      err => console.warn('GPS error', err.code),
      { enableHighAccuracy: true, maximumAge: 10000, timeout: 15000 }
    );
  }

  _onPosition(pos) {
    const { latitude: lat, longitude: lng, accuracy } = pos.coords;
    this.state.userPos = { lat, lng, accuracy };
    this._updateUserMarker(lat, lng);
    this._updateDistance();
  }

  async _getCurrentLocation() {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) { reject(new Error('Geolocation not supported')); return; }
      navigator.geolocation.getCurrentPosition(
        pos => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy }),
        err => reject(err),
        { enableHighAccuracy: true, timeout: 12000, maximumAge: 5000 }
      );
    });
  }

  async _reverseGeocode(lat, lng) {
    try {
      const ctrl = new AbortController();
      const timeout = setTimeout(() => ctrl.abort(), CFG.geocodeTimeout);
      const res = await fetch(
        `${CFG.nominatim}&lat=${lat}&lon=${lng}&zoom=18`,
        { signal: ctrl.signal, headers: { 'Accept-Language': 'he,en' } }
      );
      clearTimeout(timeout);
      if (!res.ok) return null;
      const data = await res.json();
      const a = data.address || {};
      const parts = [
        a.road || a.pedestrian || a.footway || a.path,
        a.house_number,
        a.city || a.town || a.village || a.suburb || a.municipality
      ].filter(Boolean);
      return parts.length > 0 ? parts.join(' ') : data.display_name?.split(',').slice(0,3).join(',') || null;
    } catch {
      return null;
    }
  }

  _updateDistance() {
    if (!this.state.current || !this.state.userPos) return;
    const { lat: plat, lng: plng } = this.state.current.location;
    const { lat: ulat, lng: ulng } = this.state.userPos;
    const dist = Utils.distance(ulat, ulng, plat, plng);
    const el = Utils.el('distanceDisplay');
    if (el) el.textContent = `${Utils.formatDistance(dist)} מהמיקום הנוכחי`;
    const row = Utils.el('distanceRow');
    if (row) row.style.display = '';
  }

  // ── PARKING MANAGEMENT ──────────────────────────────────────
  async _handleSaveNew() {
    if (this.state.current) {
      this._openModal('resetModal');
    } else {
      await this._saveNewParking();
    }
  }

  async _saveNewParking() {
    this.showToast('מאתר מיקום... ⏳', 'info');
    let loc;
    try {
      loc = await this._getCurrentLocation();
    } catch {
      // Use cached position if available
      if (this.state.userPos) {
        loc = this.state.userPos;
      } else {
        this.showToast('לא ניתן לאתר מיקום. בדוק הרשאות GPS.', 'error');
        return;
      }
    }

    const parking = {
      id: Utils.uuid(),
      timestamp: new Date().toISOString(),
      location: { lat: loc.lat, lng: loc.lng, accuracy: loc.accuracy || 0 },
      address: null,
      description: null,
      photo: null,
      voice: null,
      voiceDuration: 0
    };

    this.state.current = parking;
    Store.set(CFG.keys.current, parking);

    // Update map immediately
    this._addParkingMarker(loc.lat, loc.lng);
    if (this.state.map) this.state.map.flyTo([loc.lat, loc.lng], 17, { duration: 1 });

    this._updateUI();
    this._startTimer();
    this.showToast('✅ מיקום חניה נשמר!', 'success');

    // Geocode in background
    this._reverseGeocode(loc.lat, loc.lng).then(addr => {
      if (addr && this.state.current?.id === parking.id) {
        this.state.current.address = addr;
        Store.set(CFG.keys.current, this.state.current);
        const el = Utils.el('parkingAddressDisplay');
        if (el) el.textContent = addr;
        if (this.state.parkMarker) {
          this.state.parkMarker.bindPopup(
            `<div style="direction:rtl;text-align:right;font-family:'Heebo',sans-serif;font-size:13px;"><b>🚗 מיקום הרכב</b><br>${addr}</div>`
          );
        }
      }
    });
  }

  async _updateCurrentLocation() {
    if (!this.state.current) return;
    this.showToast('מעדכן מיקום... 🎯', 'info');
    try {
      const loc = await this._getCurrentLocation();
      this.state.current.location = { lat: loc.lat, lng: loc.lng, accuracy: loc.accuracy || 0 };
      this.state.current.address = null;
      Store.set(CFG.keys.current, this.state.current);

      this._addParkingMarker(loc.lat, loc.lng);
      if (this.state.map) this.state.map.flyTo([loc.lat, loc.lng], 17, { duration: 1 });

      const el = Utils.el('parkingAddressDisplay');
      if (el) el.textContent = 'מחשב כתובת...';

      this.showToast('✅ מיקום עודכן!', 'success');

      this._reverseGeocode(loc.lat, loc.lng).then(addr => {
        if (addr && this.state.current) {
          this.state.current.address = addr;
          Store.set(CFG.keys.current, this.state.current);
          if (el) el.textContent = addr;
        }
      });
    } catch {
      this.showToast('לא ניתן לעדכן מיקום', 'error');
    }
  }

  _resetParking() {
    if (!this.state.current) return;
    // Move to history
    this._addToHistory(this.state.current);
    this.state.current = null;
    Store.remove(CFG.keys.current);

    // Remove map marker
    if (this.state.parkMarker) {
      this.state.map?.removeLayer(this.state.parkMarker);
      this.state.parkMarker = null;
    }

    this._stopTimer();
    this._updateUI();
    this.showToast('✅ החניה הועברה להיסטוריה', 'success');
  }

  _addToHistory(parking) {
    if (!parking) return;
    this.state.history.unshift({ ...parking });
    if (this.state.history.length > CFG.maxHistory) {
      this.state.history = this.state.history.slice(0, CFG.maxHistory);
    }
    Store.set(CFG.keys.history, this.state.history);
    this._updateHistoryBadge();
  }

  // ── TIMER ────────────────────────────────────────────────────
  _startTimer() {
    this._stopTimer();
    this.state.timerIntervalId = setInterval(() => {
      if (!this.state.current) { this._stopTimer(); return; }
      const el = Utils.el('parkingTimerDisplay');
      if (el) el.textContent = Utils.formatDuration(this.state.current.timestamp);
      const ago = Utils.el('parkingAgoDisplay');
      if (ago) ago.textContent = Utils.formatElapsed(this.state.current.timestamp);
    }, CFG.timerInterval);
  }

  _stopTimer() {
    if (this.state.timerIntervalId) {
      clearInterval(this.state.timerIntervalId);
      this.state.timerIntervalId = null;
    }
  }

  // ── UI UPDATE ────────────────────────────────────────────────
  _updateUI() {
    this._updateStatusChip();
    this._updateHomeView();
    this._updateHistoryView();
    this._updateHistoryBadge();
  }

  _updateStatusChip() {
    const chip = Utils.el('statusChip');
    const dot  = Utils.el('statusDot');
    const lbl  = Utils.el('statusLabel');
    if (!chip || !dot || !lbl) return;
    if (this.state.current) {
      chip.classList.add('has-parking');
      dot.classList.add('active');
      lbl.textContent = 'חניה פעילה';
    } else {
      chip.classList.remove('has-parking');
      dot.classList.remove('active');
      lbl.textContent = 'אין חניה';
    }
  }

  _updateHomeView() {
    const noState  = Utils.el('noParkingState');
    const hasState = Utils.el('hasParkingState');
    const fab      = Utils.el('fabSaveParking');
    if (!noState || !hasState) return;

    if (this.state.current) {
      noState.style.display  = 'none';
      hasState.style.display = 'block';
      if (fab) fab.style.display = 'none';
      this._populateParkingCard();
    } else {
      noState.style.display  = '';
      hasState.style.display = 'none';
      if (fab) fab.style.display = 'none';
    }
  }

  _populateParkingCard() {
    const p = this.state.current;
    if (!p) return;

    // Date & time
    const dateEl = Utils.el('parkingDateDisplay');
    const timeEl = Utils.el('parkingTimeDisplay');
    const agoEl  = Utils.el('parkingAgoDisplay');
    const timerEl = Utils.el('parkingTimerDisplay');
    if (dateEl) dateEl.textContent = Utils.formatDate(p.timestamp);
    if (timeEl) timeEl.textContent = Utils.formatTime(p.timestamp);
    if (agoEl)  agoEl.textContent  = Utils.formatElapsed(p.timestamp);
    if (timerEl) timerEl.textContent = Utils.formatDuration(p.timestamp);

    // Address
    const addrEl = Utils.el('parkingAddressDisplay');
    if (addrEl) {
      addrEl.textContent = p.address || 'מחשב כתובת...';
      if (!p.address) {
        this._reverseGeocode(p.location.lat, p.location.lng).then(addr => {
          if (addr) {
            p.address = addr;
            Store.set(CFG.keys.current, p);
            addrEl.textContent = addr;
          } else {
            addrEl.textContent = `${p.location.lat.toFixed(5)}, ${p.location.lng.toFixed(5)}`;
          }
        });
      }
    }

    // Description
    const descRow = Utils.el('parkingDescRow');
    const descEl  = Utils.el('parkingDescDisplay');
    if (descRow && descEl) {
      if (p.description) {
        descRow.style.display = '';
        descEl.textContent = p.description;
      } else {
        descRow.style.display = 'none';
      }
    }

    // Distance
    if (this.state.userPos) this._updateDistance();

    // Attachments
    this._renderAttachments();

    // Media tiles state
    this._updateMediaTiles();
  }

  _renderAttachments() {
    const container = Utils.el('parkingAttachments');
    if (!container) return;
    container.innerHTML = '';
    const p = this.state.current;
    if (!p) return;

    if (p.photo) {
      const chip = document.createElement('div');
      chip.className = 'attachment-chip has-photo';
      chip.innerHTML = `<img class="photo-thumb" src="${p.photo}" alt="תמונה"><span>📷 תמונה</span>`;
      chip.addEventListener('click', () => this._viewPhoto(p.photo));
      container.appendChild(chip);
    }

    if (p.voice) {
      const chip = document.createElement('div');
      chip.className = 'attachment-chip has-voice';
      chip.innerHTML = `<span>🎙️</span><span>הקלטה קולית</span>`;
      chip.addEventListener('click', () => this._playVoice(p.voice));
      container.appendChild(chip);
    }
  }

  _updateMediaTiles() {
    const p = this.state.current;
    if (!p) return;
    const photoTile = Utils.el('addPhotoBtn');
    const voiceTile = Utils.el('addVoiceBtn');
    const textTile  = Utils.el('addTextBtn');
    if (photoTile) photoTile.classList.toggle('has-data', !!p.photo);
    if (voiceTile) voiceTile.classList.toggle('has-data', !!p.voice);
    if (textTile)  textTile.classList.toggle('has-data',  !!p.description);
  }

  _updateHistoryBadge() {
    const badge = Utils.el('historyCountBadge');
    if (!badge) return;
    const count = this.state.history.length;
    badge.textContent = count;
    badge.style.display = count > 0 ? '' : 'none';
  }

  _updateHistoryView() {
    const list    = Utils.el('historyList');
    const noState = Utils.el('noHistoryState');
    if (!list) return;

    list.innerHTML = '';

    if (this.state.history.length === 0) {
      if (noState) noState.style.display = '';
      return;
    }
    if (noState) noState.style.display = 'none';

    this.state.history.forEach((item, idx) => {
      const el = this._renderHistoryItem(item, idx);
      list.appendChild(el);
    });
  }

  _renderHistoryItem(item, idx) {
    const div = document.createElement('div');
    div.className = 'history-item';
    div.style.animationDelay = `${idx * 40}ms`;
    div.setAttribute('role', 'listitem');

    const thumb = item.photo
      ? `<img src="${item.photo}" alt="תמונה" loading="lazy">`
      : `<span>🅿️</span>`;

    const badges = [];
    if (item.photo)       badges.push('<span class="media-badge photo">📷 תמונה</span>');
    if (item.voice)       badges.push('<span class="media-badge voice">🎙️ הקלטה</span>');
    if (item.description) badges.push('<span class="media-badge text">✏️ תיאור</span>');

    div.innerHTML = `
      <div class="history-item-thumbnail">${thumb}</div>
      <div class="history-item-info">
        <div class="history-item-time">${Utils.formatDate(item.timestamp)} • ${Utils.formatTime(item.timestamp)}</div>
        <div class="history-item-address">${item.address || 'מיקום ידוע'}</div>
        ${item.description ? `<div class="history-item-desc">${item.description}</div>` : ''}
        <div class="history-item-badges">${badges.join('')}</div>
      </div>
      <button class="history-item-nav" aria-label="נווט לחניה זו">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polygon points="3 11 22 2 13 21 11 13 3 11"/>
        </svg>
      </button>
    `;

    // Open detail on item click (not nav button)
    div.addEventListener('click', e => {
      if (!e.target.closest('.history-item-nav')) {
        this._openDetailModal(item);
      }
    });

    // Nav button
    div.querySelector('.history-item-nav').addEventListener('click', e => {
      e.stopPropagation();
      this._openNavModal(item);
    });

    return div;
  }

  // ── CAMERA ──────────────────────────────────────────────────
  async _openCameraModal() {
    if (!this.state.current) { this.showToast('שמור חניה קודם', 'warning'); return; }
    this._openModal('photoModal');
    this.state.capturedPhoto = null;
    this._showCameraPreview();
    await this._startCamera();
  }

  _showCameraPreview() {
    Utils.el('cameraContainer').style.display = '';
    Utils.el('photoPreviewContainer').style.display = 'none';
    Utils.el('cameraControls').style.display = '';
    Utils.el('photoPreviewControls').style.display = 'none';
    Utils.el('cameraPermissionError').style.display = 'none';
  }

  async _startCamera() {
    this._stopCamera();
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: this.state.facingMode }, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false
      });
      this.state.cameraStream = stream;
      const vid = Utils.el('cameraVideo');
      vid.srcObject = stream;
      vid.play().catch(() => {});
    } catch (err) {
      console.warn('Camera error', err);
      Utils.el('cameraContainer').style.display = 'none';
      Utils.el('cameraControls').style.display = 'none';
      Utils.el('cameraPermissionError').style.display = '';
    }
  }

  _stopCamera() {
    if (this.state.cameraStream) {
      this.state.cameraStream.getTracks().forEach(t => t.stop());
      this.state.cameraStream = null;
    }
    const vid = Utils.el('cameraVideo');
    if (vid) vid.srcObject = null;
  }

  async _switchCamera() {
    this.state.facingMode = this.state.facingMode === 'environment' ? 'user' : 'environment';
    await this._startCamera();
  }

  async _capturePhoto() {
    const vid    = Utils.el('cameraVideo');
    const canvas = Utils.el('cameraCanvas');
    if (!vid || !canvas) return;

    canvas.width  = vid.videoWidth  || 640;
    canvas.height = vid.videoHeight || 480;
    canvas.getContext('2d').drawImage(vid, 0, 0);

    const raw = canvas.toDataURL('image/jpeg', 1.0);
    this.state.capturedPhoto = await Utils.compressImage(raw);

    // Show preview
    Utils.el('cameraContainer').style.display = 'none';
    const preview = Utils.el('photoPreviewContainer');
    preview.style.display = '';
    Utils.el('photoPreviewImg').src = this.state.capturedPhoto;
    Utils.el('cameraControls').style.display = 'none';
    Utils.el('photoPreviewControls').style.display = '';
    this._stopCamera();
  }

  _retakePhoto() {
    this.state.capturedPhoto = null;
    this._showCameraPreview();
    this._startCamera();
  }

  async _savePhoto() {
    if (!this.state.capturedPhoto || !this.state.current) return;
    this.state.current.photo = this.state.capturedPhoto;
    Store.set(CFG.keys.current, this.state.current);
    this._closeModal('photoModal');
    this._renderAttachments();
    this._updateMediaTiles();
    this.showToast('📷 תמונה נשמרה!', 'success');
  }

  async _handlePhotoFile(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = async ev => {
      this.state.capturedPhoto = await Utils.compressImage(ev.target.result);
      Utils.el('cameraContainer').style.display = 'none';
      Utils.el('photoPreviewContainer').style.display = '';
      Utils.el('photoPreviewImg').src = this.state.capturedPhoto;
      Utils.el('cameraControls').style.display = 'none';
      Utils.el('photoPreviewControls').style.display = '';
      Utils.el('cameraPermissionError').style.display = 'none';
    };
    reader.readAsDataURL(file);
  }

  _viewPhoto(src) {
    const modal = document.createElement('div');
    modal.style.cssText = 'position:fixed;inset:0;z-index:900;background:rgba(0,0,0,0.92);display:flex;align-items:center;justify-content:center;padding:20px;cursor:pointer';
    const img = document.createElement('img');
    img.src = src;
    img.style.cssText = 'max-width:100%;max-height:100%;border-radius:12px;object-fit:contain';
    modal.appendChild(img);
    modal.addEventListener('click', () => document.body.removeChild(modal));
    document.body.appendChild(modal);
  }

  // ── VOICE RECORDING ─────────────────────────────────────────
  _openVoiceModal() {
    if (!this.state.current) { this.showToast('שמור חניה קודם', 'warning'); return; }
    this._resetVoiceUI();
    this._openModal('voiceModal');
  }

  _resetVoiceUI() {
    this.state.capturedVoice = null;
    this.state.audioChunks = [];
    this.state.recSeconds = 0;

    const viz = Utils.el('voiceVisualizer');
    if (viz) { viz.classList.remove('recording', 'done'); }

    Utils.el('voiceStatusText').textContent = 'לחץ על המיקרופון להתחלת הקלטה';
    Utils.el('voiceTimerDisplay').style.display = 'none';
    Utils.el('voiceTimerDisplay').textContent = '0:00';
    Utils.el('voicePlaybackContainer').style.display = 'none';
    Utils.el('voicePermissionError').style.display = 'none';
    Utils.el('voiceRecordControls').style.display = '';
    Utils.el('voiceSaveControls').style.display = 'none';

    const btn = Utils.el('recordToggleBtn');
    if (btn) {
      btn.classList.remove('recording');
      Utils.el('recordToggleIcon').textContent = '⏺️';
      Utils.el('recordToggleText').textContent = 'התחל הקלטה';
    }
  }

  async _toggleRecording() {
    if (this.state.isRecording) {
      this._stopRecording();
    } else {
      await this._startRecording();
    }
  }

  async _startRecording() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });

      // Determine best supported format
      const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/ogg', 'audio/mp4']
        .find(t => MediaRecorder.isTypeSupported(t)) || '';

      this.state.mediaRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
      this.state.audioChunks = [];

      this.state.mediaRecorder.ondataavailable = e => {
        if (e.data.size > 0) this.state.audioChunks.push(e.data);
      };

      this.state.mediaRecorder.onstop = () => {
        stream.getTracks().forEach(t => t.stop());
        const blob = new Blob(this.state.audioChunks, { type: this.state.mediaRecorder.mimeType || 'audio/webm' });
        Utils.blobToBase64(blob).then(b64 => {
          this.state.capturedVoice = b64;
          if (this.state.voiceBlobUrl) URL.revokeObjectURL(this.state.voiceBlobUrl);
          this.state.voiceBlobUrl = URL.createObjectURL(blob);
          const player = Utils.el('voiceAudioPlayer');
          player.src = this.state.voiceBlobUrl;
          Utils.el('voicePlaybackContainer').style.display = '';
        });
      };

      this.state.mediaRecorder.start(100);
      this.state.isRecording = true;
      this.state.recSeconds  = 0;

      // Update UI
      Utils.el('voiceVisualizer')?.classList.add('recording');
      Utils.el('voiceStatusText').textContent = '● מקליט...';
      Utils.el('voiceTimerDisplay').style.display = '';

      const btn = Utils.el('recordToggleBtn');
      btn?.classList.add('recording');
      Utils.el('recordToggleIcon').textContent = '⏹️';
      Utils.el('recordToggleText').textContent = 'עצור הקלטה';

      // Timer
      this.state.recTimerId = setInterval(() => {
        this.state.recSeconds++;
        const m = Math.floor(this.state.recSeconds / 60);
        const s = this.state.recSeconds % 60;
        const el = Utils.el('voiceTimerDisplay');
        if (el) el.textContent = `${m}:${String(s).padStart(2,'0')}`;
        // Auto-stop at 5 min
        if (this.state.recSeconds >= 300) this._stopRecording();
      }, 1000);

    } catch (err) {
      console.warn('Microphone error', err);
      Utils.el('voicePermissionError').style.display = '';
      Utils.el('voiceRecordControls').style.display = 'none';
    }
  }

  _stopRecording() {
    if (!this.state.isRecording) return;
    clearInterval(this.state.recTimerId);
    this.state.recTimerId = null;
    this.state.isRecording = false;

    if (this.state.mediaRecorder && this.state.mediaRecorder.state !== 'inactive') {
      this.state.mediaRecorder.stop();
    }

    // Update UI
    Utils.el('voiceVisualizer')?.classList.remove('recording');
    Utils.el('voiceVisualizer')?.classList.add('done');
    Utils.el('voiceStatusText').textContent = '✅ הקלטה הסתיימה';

    Utils.el('voiceRecordControls').style.display = 'none';
    Utils.el('voiceSaveControls').style.display = '';
  }

  _rerecord() {
    if (this.state.isRecording) this._stopRecording();
    this._resetVoiceUI();
  }

  async _saveVoice() {
    if (!this.state.capturedVoice || !this.state.current) return;
    this.state.current.voice = this.state.capturedVoice;
    this.state.current.voiceDuration = this.state.recSeconds;
    Store.set(CFG.keys.current, this.state.current);
    this._closeModal('voiceModal');
    this._renderAttachments();
    this._updateMediaTiles();
    this.showToast('🎙️ הקלטה נשמרה!', 'success');
  }

  async _handleVoiceFile(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    const b64 = await Utils.blobToBase64(file);
    this.state.capturedVoice = b64;
    if (this.state.voiceBlobUrl) URL.revokeObjectURL(this.state.voiceBlobUrl);
    this.state.voiceBlobUrl = URL.createObjectURL(file);
    const player = Utils.el('voiceAudioPlayer');
    player.src = this.state.voiceBlobUrl;
    Utils.el('voicePlaybackContainer').style.display = '';
    Utils.el('voiceRecordControls').style.display = 'none';
    Utils.el('voiceSaveControls').style.display = '';
    Utils.el('voiceVisualizer')?.classList.add('done');
    Utils.el('voiceStatusText').textContent = '✅ קובץ נטען';
  }

  _playVoice(src) {
    if (!src) return;
    const audio = new Audio(src);
    audio.play().catch(() => this.showToast('לא ניתן להפעיל הקלטה', 'error'));
  }

  // ── DESCRIPTION ─────────────────────────────────────────────
  _openTextModal() {
    if (!this.state.current) { this.showToast('שמור חניה קודם', 'warning'); return; }
    const input = Utils.el('descriptionInput');
    if (input) {
      input.value = this.state.current.description || '';
      Utils.el('charCount').textContent = input.value.length;
    }
    this._openModal('textModal');
  }

  _saveDescription() {
    const input = Utils.el('descriptionInput');
    if (!input || !this.state.current) return;
    const text = input.value.trim().slice(0, CFG.maxTextLen);
    this.state.current.description = text || null;
    Store.set(CFG.keys.current, this.state.current);

    const row  = Utils.el('parkingDescRow');
    const span = Utils.el('parkingDescDisplay');
    if (row && span) {
      if (text) { row.style.display = ''; span.textContent = text; }
      else { row.style.display = 'none'; }
    }

    this._updateMediaTiles();
    this._closeModal('textModal');
    this.showToast(text ? '✅ תיאור נשמר!' : '🗑️ תיאור נמחק', 'success');
  }

  // ── NAVIGATION & SHARING ────────────────────────────────────
  _openNavModal(parking) {
    if (!parking?.location) return;
    this._activeNavTarget = parking;
    this._openModal('navModal');
  }

  _navOpen(app) {
    const p = this._activeNavTarget;
    if (!p?.location) return;
    const { lat, lng } = p.location;
    const urls = {
      waze:   `https://waze.com/ul?ll=${lat},${lng}&navigate=yes`,
      google: `https://maps.google.com/maps?daddr=${lat},${lng}`,
      apple:  `maps://maps.apple.com/?daddr=${lat},${lng}`
    };
    window.open(urls[app], '_blank');
    this._closeModal('navModal');
  }

  async _shareParking(parking) {
    if (!parking?.location) return;
    const { lat, lng } = parking.location;
    const text = [
      '🚗 מיקום הרכב שלי:',
      parking.address || `${lat.toFixed(5)}, ${lng.toFixed(5)}`,
      parking.description ? `📝 ${parking.description}` : '',
      `⏰ ${Utils.formatDate(parking.timestamp)} ${Utils.formatTime(parking.timestamp)}`,
      `🗺️ https://maps.google.com/maps?q=${lat},${lng}`
    ].filter(Boolean).join('\n');

    if (navigator.share) {
      try {
        await navigator.share({ title: 'FindMyCar - מיקום הרכב', text });
        return;
      } catch {}
    }
    // Fallback: copy to clipboard
    try {
      await navigator.clipboard.writeText(text);
      this.showToast('📋 מיקום הועתק ללוח', 'success');
    } catch {
      this.showToast('לא ניתן לשתף כעת', 'error');
    }
  }

  // ── HISTORY MANAGEMENT ──────────────────────────────────────
  _clearHistory() {
    if (this.state.history.length === 0) return;
    const confirmed = confirm(`למחוק ${this.state.history.length} חניות מההיסטוריה?`);
    if (!confirmed) return;
    this.state.history = [];
    Store.remove(CFG.keys.history);
    this._updateHistoryView();
    this._updateHistoryBadge();
    this.showToast('🗑️ ההיסטוריה נמחקה', 'info');
  }

  _deleteHistoryItem(id) {
    this.state.history = this.state.history.filter(i => i.id !== id);
    Store.set(CFG.keys.history, this.state.history);
    this._updateHistoryView();
    this._updateHistoryBadge();
    this.showToast('🗑️ חניה נמחקה', 'info');
  }

  // ── DETAIL MODAL ─────────────────────────────────────────────
  _openDetailModal(item) {
    this.state.detailItemId = item.id;
    Utils.el('detailModalTitle').textContent =
      `${Utils.formatDate(item.timestamp)} • ${Utils.formatTime(item.timestamp)}`;

    const body = Utils.el('detailModalContent');
    body.innerHTML = '';

    // Photo
    if (item.photo) {
      const img = document.createElement('img');
      img.src = item.photo;
      img.className = 'detail-photo';
      img.alt = 'תמונת חניה';
      img.addEventListener('click', () => this._viewPhoto(item.photo));
      body.appendChild(img);
    }

    // Location section
    const locSec = this._makeDetailSection('📍 מיקום', item.address || `${item.location.lat.toFixed(5)}, ${item.location.lng.toFixed(5)}`);
    body.appendChild(locSec);

    // Time section
    const timeSec = this._makeDetailSection('⏰ זמן חניה', `${Utils.formatDate(item.timestamp)}, ${Utils.formatTime(item.timestamp)}`);
    body.appendChild(timeSec);

    // Description
    if (item.description) {
      body.appendChild(this._makeDetailSection('✏️ תיאור', item.description));
    }

    // Voice
    if (item.voice) {
      const sec = document.createElement('div');
      sec.className = 'detail-section';
      sec.innerHTML = '<div class="detail-section-label">🎙️ הקלטה קולית</div>';
      const audio = document.createElement('audio');
      audio.src = item.voice;
      audio.controls = true;
      audio.className = 'audio-player';
      sec.appendChild(audio);
      body.appendChild(sec);
    }

    // Map preview (small)
    const mapSec = document.createElement('div');
    mapSec.className = 'detail-section';
    mapSec.innerHTML = `
      <div class="detail-section-label">🗺️ מיקום במפה</div>
      <div class="detail-map-mini" id="detailMapContainer">טוען מפה...</div>
    `;
    body.appendChild(mapSec);

    // Nav button target
    this._activeNavTarget = item;

    this._openModal('detailModal');

    // Init mini map after open
    setTimeout(() => this._initDetailMap(item), 100);
  }

  _makeDetailSection(label, value) {
    const sec = document.createElement('div');
    sec.className = 'detail-section';
    sec.innerHTML = `
      <div class="detail-section-label">${label}</div>
      <div class="detail-section-value">${value}</div>
    `;
    return sec;
  }

  _initDetailMap(item) {
    // Destroy previous mini map to prevent memory leak
    if (this.state.detailMap) {
      try { this.state.detailMap.remove(); } catch {}
      this.state.detailMap = null;
    }

    const container = Utils.el('detailMapContainer');
    if (!container) return;
    container.innerHTML = '';
    container.style.cssText = 'height:150px;border-radius:10px;overflow:hidden;';

    try {
      const miniMap = L.map(container, { zoomControl: false, dragging: false, touchZoom: false, scrollWheelZoom: false, doubleClickZoom: false });
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 18 }).addTo(miniMap);
      const { lat, lng } = item.location;
      miniMap.setView([lat, lng], 16);
      const icon = L.divIcon({
        className: '',
        html: `<div class="parking-marker-icon" style="width:18px;height:18px;"></div>`,
        iconSize: [18, 18],
        iconAnchor: [9, 18]
      });
      L.marker([lat, lng], { icon }).addTo(miniMap);
      this.state.detailMap = miniMap;
    } catch (e) {
      container.innerHTML = '<div style="text-align:center;padding-top:60px;color:var(--text-secondary)">🗺️ מפה לא זמינה</div>';
    }
  }

  _navFromDetail() {
    this._closeModal('detailModal');
    if (this._activeNavTarget) this._openNavModal(this._activeNavTarget);
  }

  _deleteFromDetail() {
    const id = this.state.detailItemId;
    this._closeModal('detailModal');
    if (id) this._deleteHistoryItem(id);
  }

  // ── MODALS ───────────────────────────────────────────────────
  _openModal(id) {
    const modal = Utils.el(id);
    if (!modal) return;
    modal.style.display = 'flex';
    document.body.style.overflow = 'hidden';
    // Trap focus
    setTimeout(() => {
      const firstFocusable = modal.querySelector('button, input, textarea, [tabindex]');
      firstFocusable?.focus();
    }, 50);
  }

  _closeModal(id) {
    const modal = Utils.el(id);
    if (!modal) return;
    modal.style.display = 'none';
    document.body.style.overflow = '';
    // Stop camera if closing photo modal
    if (id === 'photoModal') this._stopCamera();
    // Cleanup voice modal
    if (id === 'voiceModal') {
      if (this.state.isRecording) this._stopRecording();
      if (this.state.voiceBlobUrl) {
        URL.revokeObjectURL(this.state.voiceBlobUrl);
        this.state.voiceBlobUrl = null;
      }
    }
    // Cleanup detail map
    if (id === 'detailModal' && this.state.detailMap) {
      try { this.state.detailMap.remove(); } catch {}
      this.state.detailMap = null;
    }
  }

  // ── VIEW NAVIGATION ──────────────────────────────────────────
  _showView(viewId) {
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));

    const view = Utils.el(viewId);
    if (view) view.classList.add('active');

    const btn = document.querySelector(`.nav-btn[data-view="${viewId}"]`);
    if (btn) btn.classList.add('active');

    this.state.currentView = viewId;

    // Refresh history view when opened
    if (viewId === 'historyView') this._updateHistoryView();

    // Invalidate map size when switching to home
    if (viewId === 'homeView' && this.state.map) {
      setTimeout(() => this.state.map.invalidateSize(), 50);
    }
  }

  // ── THEME ────────────────────────────────────────────────────
  _toggleTheme() {
    const next = this.state.theme === 'dark' ? 'light' : 'dark';
    this._applyTheme(next);
  }

  _applyTheme(theme) {
    this.state.theme = theme;
    document.documentElement.setAttribute('data-theme', theme);
    Store.set(CFG.keys.theme, theme);
    const meta = Utils.el('themeColorMeta');
    if (meta) meta.content = theme === 'dark' ? '#060B18' : '#F0F4FF';
  }

  // ── PWA ──────────────────────────────────────────────────────
  _setupPWA() {
    window.addEventListener('beforeinstallprompt', e => {
      e.preventDefault();
      this.state.installPrompt = e;
      // Show install banner after 3 seconds
      setTimeout(() => {
        const banner = Utils.el('installBanner');
        if (banner && !sessionStorage.getItem('fmc_install_dismissed')) {
          banner.style.display = '';
        }
      }, 3000);
    });

    window.addEventListener('appinstalled', () => {
      Utils.el('installBanner').style.display = 'none';
      this.state.installPrompt = null;
      this.showToast('✅ האפליקציה הותקנה!', 'success');
    });

    // Online/offline
    window.addEventListener('online',  () => { Utils.el('offlineIndicator').style.display = 'none'; });
    window.addEventListener('offline', () => { Utils.el('offlineIndicator').style.display = ''; });
  }

  async _promptInstall() {
    Utils.el('installBanner').style.display = 'none';
    if (!this.state.installPrompt) return;
    this.state.installPrompt.prompt();
    const { outcome } = await this.state.installPrompt.userChoice;
    if (outcome === 'dismissed') {
      sessionStorage.setItem('fmc_install_dismissed', '1');
    }
    this.state.installPrompt = null;
  }

  // ── TOAST ────────────────────────────────────────────────────
  showToast(message, type = 'info') {
    const container = Utils.el('toastContainer');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
      toast.classList.add('toast-hide');
      toast.addEventListener('animationend', () => toast.remove(), { once: true });
    }, CFG.toastDuration);
  }
}

// ── BOOT ─────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', () => {
  window.app = new FindMyCarApp();
});
