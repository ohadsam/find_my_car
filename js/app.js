import { CFG } from './config.js';
import { Store } from './store.js';
import { Utils } from './utils.js';
import { reverseGeocode, normalizeAddress } from './geocoder.js';
import { MapController } from './map.js';
import { CameraController } from './camera.js';
import { VoiceController } from './voice.js';
import { UIController } from './ui.js';
import { ReturnModal } from './return-modal.js';
import { VehicleController } from './vehicles.js';
import { BluetoothController } from './bluetooth.js';
import { NativeBluetoothController } from './bluetooth-native.js';
import { WidgetBridge } from './widget-bridge.js';
import { Notify } from './notify.js';

class FindMyCarApp {
  #state = {
    current:              null,
    history:              [],
    theme:                'dark',
    currentView:          'homeView',
    userPos:              null,
    watchId:              null,
    timerIntervalId:      null,
    installPrompt:        null,
    activeNavTarget:      null,
    detailItemId:         null,
    vehicles:             [],
    activeVehicleId:      null,
    vehicleEditId:        null,
    vehicleDeleteId:      null,
    btPendingVehicleId:   null,  // vehicle awaiting end-parking confirmation
    btPendingLabel:       null,  // BT device label that triggered the confirm modal
    gpsSpeedSince:        null,  // Date.now() when speed first exceeded threshold
    gpsEndSuggested:      false, // true after GPS end suggestion shown this session
  };

  #swapping  = false;  // guard against concurrent #swapParking() calls

  #map       = new MapController();
  #camera    = new CameraController();
  #voice     = new VoiceController();
  #bluetooth = NativeBluetoothController.isSupported()
    ? new NativeBluetoothController()
    : new BluetoothController();
  #wakeLock  = null;
  #ui;
  #returnModal;

  constructor() {
    this.#ui = new UIController({
      onHistoryItemClick: item => this.#openDetailModal(item),
      onHistoryItemNav:   item => this.#openNavModal(item),
      onPhotoClick:       src  => this.#camera.viewPhoto(src),
      onVehicleSelect:    id   => this.#switchVehicle(id),
      onDeletePhoto:      ()   => this.#deletePhoto(),
      onDeleteVoice:      ()   => this.#deleteVoice(),
      onDeleteText:       ()   => this.#deleteText(),
      onEditText:         ()   => this.#openTextModal(),
    });

    this.#returnModal = new ReturnModal({
      onMove:    () => this.#resetParking(),
      onDismiss: () => {},
      onSwap:    () => this.#swapParking(),
    });

    this.#init();
  }

  // ── VEHICLE STORAGE HELPERS ───────────────────────────────────
  get #currentKey()  { return CFG.keys.curPrefix  + this.#state.activeVehicleId; }
  get #historyKey()  { return CFG.keys.histPrefix + this.#state.activeVehicleId; }

  async #init() {
    this.#fixVH();
    window.addEventListener('resize', () => this.#fixVH());

    VehicleController.migrate();
    this.#state.vehicles        = VehicleController.getAll();
    this.#state.activeVehicleId = VehicleController.getActiveId();
    this.#state.current         = VehicleController.getCurrent(this.#state.activeVehicleId);
    this.#state.history         = VehicleController.getHistory(this.#state.activeVehicleId);
    this.#state.theme            = this.#getTheme();

    this.#ui.applyTheme(this.#state.theme);

    // Restore map collapsed state before init
    const mapCollapsed = Store.get(CFG.keys.mapCollapsed, false);
    if (mapCollapsed) this.#setMapCollapsed(true, false);

    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('./sw.js').catch(e => console.warn('SW reg failed', e));
    }

    this.#bindEvents();
    this.#returnModal.bindEvents();

    // Ask for everything the native app can possibly need right after
    // install, instead of only surprising the user with scattered
    // permission dialogs the first time they touch BT settings/camera/voice.
    // Not awaited — runs alongside the rest of init, doesn't block the UI.
    this.#primeNativePermissions();

    // Bluetooth setup
    this.#bluetooth.init({
      onDeviceConnected:    label => this.#onBtConnected(label),
      onDeviceDisconnected: label => this.#onBtDisconnected(label),
    });
    if (this.#getBtSettings().enabled) {
      this.#bluetooth.startWatch();
    }

    const gpsToggle = Utils.el('gpsAutoEndToggle');
    if (gpsToggle) gpsToggle.checked = this.#getGpsSettings().enabled;

    // Init map; after loading screen fades, invalidate size to handle any CSS transition artifacts
    setTimeout(() => {
      this.#map.init(this.#state.current);
    }, 100);

    this.#syncUI();

    if (this.#state.current && !this.#state.current.address) {
      this.#geocodeCurrentParking();
    }

    this.#startLocationWatch();
    this.#setupPWA();

    if (this.#state.current) {
      this.#acquireWakeLock();
      this.#showParkingNotification(this.#state.current);
    }

    setTimeout(() => {
      Utils.el('loadingScreen')?.classList.add('fade-out');
      // Invalidate map size after loading screen CSS transition (500ms) completes
      setTimeout(() => this.#map.invalidateSize(), 600);
    }, 1000);

    if (this.#state.current) this.#startTimer();

    if (this.#state.current) {
      const v = VehicleController.getById(this.#state.activeVehicleId);
      setTimeout(() => this.#returnModal.show(this.#state.current, v?.name), 1200);
    }

    // Show what's new whenever this version hasn't been seen yet (first install or upgrade)
    const seenVersion = Store.get(CFG.keys.seenVersion, null);
    if (seenVersion !== CFG.version) {
      if (!this.#state.current) {
        // No active parking — safe to show without conflicting with returnModal
        setTimeout(() => {
          this.#ui.showWhatsNew(CFG.changelog[0]);
          Store.set(CFG.keys.seenVersion, CFG.version);
        }, 1800);
      } else {
        // Active parking will trigger returnModal — skip popup, mark as seen
        Store.set(CFG.keys.seenVersion, CFG.version);
      }
    }

    const action = new URLSearchParams(window.location.search).get('action');
    if (action === 'save') {
      setTimeout(() => this.#handleSaveNew(), 500);
    } else if (action === 'swap') {
      setTimeout(() => this.#swapParking(), 500);
    } else if (action === 'end') {
      setTimeout(() => this.#resetParking(), 500);
    } else if (action === 'vehicles') {
      setTimeout(() => this.#showView('settingsView'), 500);
    }
  }

  // No-op in the browser — each permission there is requested contextually
  // by the browser itself the first time a feature actually needs it, which
  // is already the right UX for a website. On native, ask for everything up
  // front instead of only reactively (geolocation already prompts on its own
  // via #startLocationWatch(), included here too for a single clear
  // onboarding sequence rather than depending on init() call order).
  async #primeNativePermissions() {
    if (!window.Capacitor?.isNativePlatform?.()) return;

    await new Promise(resolve => {
      if (!navigator.geolocation) { resolve(); return; }
      navigator.geolocation.getCurrentPosition(() => resolve(), () => resolve(), { timeout: 8000 });
    });

    try {
      const stream = await navigator.mediaDevices?.getUserMedia?.({ video: true, audio: true });
      stream?.getTracks().forEach(t => t.stop());
    } catch {
      // Denied or no camera/mic — the camera/voice modals already fall back
      // to their own permission-error UI when actually opened.
    }

    await this.#bluetooth.requestPermission?.().catch(() => {});
    await Notify.ensurePermission().catch(() => {});
  }

  #getTheme() {
    return Store.get(CFG.keys.theme, 'dark');
  }

  #fixVH() {
    document.documentElement.style.setProperty('--vh', `${window.innerHeight * 0.01}px`);
  }

  // ── EVENTS ────────────────────────────────────────────────────
  #bindEvents() {
    document.querySelectorAll('.bottom-nav .nav-btn[data-view]').forEach(btn => {
      btn.addEventListener('click', () => this.#showView(btn.dataset.view));
    });

    Utils.el('themeToggleBtn')?.addEventListener('click', () => this.#toggleTheme());

    Utils.el('saveFirstParkingBtn')?.addEventListener('click', () => this.#handleSaveNew());
    Utils.el('fabSaveParking')?.addEventListener('click',      () => this.#handleSaveNew());

    Utils.el('navigateBtn')?.addEventListener('click',     () => this.#openNavModal(this.#state.current));
    Utils.el('shareBtn')?.addEventListener('click',        () => this.#shareParking(this.#state.current));
    Utils.el('whatsappBtn')?.addEventListener('click',     () => this.#openWhatsAppModal());
    Utils.el('resetParkingBtn')?.addEventListener('click', () => this.#ui.openModal('resetModal'));
    Utils.el('swapParkingBtn')?.addEventListener('click',  () => this.#swapParking());
    Utils.el('endParkingBtn')?.addEventListener('click',   () => this.#resetParking());

    Utils.el('confirmResetBtn')?.addEventListener('click', () => {
      this.#ui.closeModal('resetModal');
      this.#resetParking();
    });

    const openResetFromChip = () => { if (this.#state.current) this.#ui.openModal('resetModal'); };
    Utils.el('statusChip')?.addEventListener('click', openResetFromChip);
    Utils.el('statusChip')?.addEventListener('keydown', e => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openResetFromChip(); }
    });

    Utils.el('centerParkingBtn')?.addEventListener('click', () => this.#centerOnParking());
    Utils.el('centerUserBtn')?.addEventListener('click',    () => this.#centerOnUser());
    Utils.el('mapCollapseBtn')?.addEventListener('click',   () => this.#toggleMapCollapse());
    Utils.el('reloadAppBtn')?.addEventListener('click',     () => this.#reloadApp());
    Utils.el('exportDataBtn')?.addEventListener('click',    () => this.#exportData());
    Utils.el('importDataBtn')?.addEventListener('click',    () => Utils.el('importDataInput')?.click());
    Utils.el('importDataInput')?.addEventListener('change', e => {
      const file = e.target.files?.[0];
      e.target.value = ''; // allow re-selecting the same file next time
      if (file) this.#importData(file);
    });

    const vBtn = Utils.el('versionTagBtn');
    if (vBtn) {
      vBtn.textContent = `v${CFG.version}`;
      vBtn.addEventListener('click', () => this.#ui.showWhatsNew(CFG.changelog[0]));
    }

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

    // WhatsApp modal
    Utils.el('waSendBtn')?.addEventListener('click', () => this.#executeWhatsAppShare());

    // Vehicle settings
    Utils.el('addVehicleBtn')?.addEventListener('click', () => this.#openVehicleModal(null));
    Utils.el('saveVehicleBtn')?.addEventListener('click', () => this.#saveVehicle());
    Utils.el('confirmVehicleDeleteBtn')?.addEventListener('click', () => this.#confirmDeleteVehicle());

    // GPS auto-end
    Utils.el('gpsEndConfirmBtn')?.addEventListener('click', () => {
      this.#closeModal('gpsEndModal');
      this.#resetParking();
    });
    Utils.el('gpsEndDismissBtn')?.addEventListener('click', () => this.#closeModal('gpsEndModal'));
    Utils.el('gpsAutoEndToggle')?.addEventListener('change', e => {
      Store.set(CFG.keys.gpsAutoEnd, { enabled: e.target.checked });
    });

    // Bluetooth
    Utils.el('vehicleBtScanBtn')?.addEventListener('click',   () => this.#btScanDevices());
    Utils.el('vehicleBtUnlinkBtn')?.addEventListener('click', () => this.#ui.setBtDeviceValue(null));
    Utils.el('openBtSettingsBtn')?.addEventListener('click',  () => this.#openBtSettingsModal());
    Utils.el('btParkingEndBtn')?.addEventListener('click', () => {
      const vid   = this.#state.btPendingVehicleId;
      const label = this.#state.btPendingLabel;
      this.#closeModal('btParkingModal');
      if (vid) {
        this.#markBtEnd(vid, label);
        this.#btEndParking(vid);
      }
    });
    Utils.el('btStartAddPhotoBtn')?.addEventListener('click', () => {
      this.#closeModal('btStartPopupModal');
      if (this.#state.current) this.#openCameraModal();
    });
    Utils.el('btStartAddVoiceBtn')?.addEventListener('click', () => {
      this.#closeModal('btStartPopupModal');
      if (this.#state.current) this.#openVoiceModal();
    });
    Utils.el('btStartAddTextBtn')?.addEventListener('click', () => {
      this.#closeModal('btStartPopupModal');
      if (this.#state.current) this.#openTextModal();
    });

    // Global close handler (data-close attribute on backdrops and close buttons)
    document.addEventListener('click', e => {
      const closeId = e.target.dataset.close || e.target.closest('[data-close]')?.dataset?.close;
      if (closeId) this.#closeModal(closeId);
    });

    Utils.el('installAcceptBtn')?.addEventListener('click',  () => this.#promptInstall());
    Utils.el('installDismissBtn')?.addEventListener('click', () => {
      Utils.el('installBanner').style.display = 'none';
    });

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState !== 'visible') return;
      if (this.#state.current) this.#acquireWakeLock();
      if (this.#getBtSettings().enabled) this.#bluetooth.checkNow();
    });

    document.addEventListener('keydown', e => {
      if (e.key === 'Escape') {
        const open = document.querySelector('.modal[style*="flex"]');
        if (open) this.#closeModal(open.id);
      }
    });
  }

  // ── MAP COLLAPSE ──────────────────────────────────────────────
  #toggleMapCollapse() {
    const collapsed = !Store.get(CFG.keys.mapCollapsed, false);
    this.#setMapCollapsed(collapsed, true);
  }

  #setMapCollapsed(collapsed, animate) {
    Store.set(CFG.keys.mapCollapsed, collapsed);
    const wrapper = Utils.el('mapWrapper');
    const label   = Utils.el('mapToggleText');
    const btn     = Utils.el('mapCollapseBtn');
    if (!wrapper) return;
    if (!animate) wrapper.classList.add('no-transition');
    if (collapsed) {
      wrapper.classList.add('map-collapsed');
      if (btn)     btn.setAttribute('aria-label', 'הצג מפה');
      if (label)   label.textContent = 'הצג מפה';
    } else {
      wrapper.classList.remove('map-collapsed');
      if (btn)     btn.setAttribute('aria-label', 'הסתר מפה');
      if (label)   label.textContent = 'הסתר מפה';
      setTimeout(() => this.#map.invalidateSize(), 380);
    }
    if (!animate) {
      // Force a reflow then re-enable transitions
      wrapper.getBoundingClientRect();
      wrapper.classList.remove('no-transition');
    }
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
    const { latitude: lat, longitude: lng, accuracy, speed } = pos.coords;
    this.#state.userPos = { lat, lng, accuracy };
    this.#map.updateUserMarker(lat, lng);
    this.#ui.updateDistance(this.#state);
    this.#checkGpsSpeed(speed);
    this.#checkGpsDistance(lat, lng);
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
        VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
        this.#ui.updateAddress(this.#state.current);
        this.#map.updateParkingMarkerPopup(addr);
      } else {
        const addrEl = Utils.el('parkingAddressDisplay');
        if (addrEl) addrEl.textContent = `${p.location.lat.toFixed(5)}, ${p.location.lng.toFixed(5)}`;
      }
    });
  }

  // ── PARKING MANAGEMENT ────────────────────────────────────────
  #syncUI() {
    this.#ui.updateAll(this.#state);
    WidgetBridge.sync(this.#state);
  }

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
      id:            Utils.uuid(),
      timestamp:     new Date().toISOString(),
      location:      { lat: loc.lat, lng: loc.lng, accuracy: loc.accuracy || 0 },
      address:       null,
      description:   null,
      photo:         null,
      voice:         null,
      voiceDuration: 0,
      btStartDevice: null,
      btEndDevice:   null,
      btEndTime:     null,
    };

    this.#state.current       = parking;
    this.#state.gpsEndSuggested = false;
    this.#state.gpsSpeedSince   = null;
    VehicleController.setCurrent(this.#state.activeVehicleId, parking);

    this.#map.addParkingMarker(loc.lat, loc.lng, null);
    this.#map.flyTo(loc.lat, loc.lng, 17);
    this.#syncUI();
    this.#startTimer();
    this.#ui.showToast('✅ מיקום חניה נשמר!', 'success');
    this.#acquireWakeLock();
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission()
        .then(() => {
          if (this.#state.current?.id === parking.id) this.#showParkingNotification(parking);
        })
        .catch(() => {});
    } else {
      this.#showParkingNotification(parking);
    }

    reverseGeocode(loc.lat, loc.lng).then(addr => {
      if (!addr || !this.#state.current || this.#state.current.id !== parking.id) return;
      this.#state.current.address = addr;
      VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
      this.#ui.updateAddress(this.#state.current);
      this.#map.updateParkingMarkerPopup(addr);
      this.#showParkingNotification(this.#state.current);
    });
  }

  async #swapParking() {
    if (this.#swapping) return;
    if (!this.#state.current) { this.#ui.showToast('אין חניה פעילה להחלפה.', 'info'); return; }
    this.#swapping = true;

    // Close modals that could end the new parking if confirmed after the swap
    this.#closeModal('gpsEndModal');
    if (this.#state.btPendingVehicleId === this.#state.activeVehicleId) {
      this.#closeModal('btParkingModal');
    }

    // Snapshot identity before the async GPS call so we can detect mid-swap state changes
    const prevId    = this.#state.current.id;
    const vehicleId = this.#state.activeVehicleId;

    this.#ui.showToast('מחפש מיקום... ⏳', 'info');
    let loc;
    try {
      loc = await this.#getCurrentLocation();
    } catch {
      loc = this.#state.userPos ?? null;
    } finally {
      this.#swapping = false;
    }

    if (!loc) {
      this.#ui.showToast('לא ניתן לאתר מיקום. בדוק הרשאות GPS.', 'error');
      return;
    }

    // Bail if the user ended/switched parking while GPS was pending
    if (this.#state.activeVehicleId !== vehicleId || this.#state.current?.id !== prevId) return;

    this.#addToHistory(this.#state.current);

    const parking = {
      id:            Utils.uuid(),
      timestamp:     new Date().toISOString(),
      location:      { lat: loc.lat, lng: loc.lng, accuracy: loc.accuracy || 0 },
      address:       null,
      description:   null,
      photo:         null,
      voice:         null,
      voiceDuration: 0,
      btStartDevice: null,
      btEndDevice:   null,
      btEndTime:     null,
    };

    this.#state.current         = parking;
    this.#state.gpsEndSuggested = false;
    this.#state.gpsSpeedSince   = null;
    VehicleController.setCurrent(vehicleId, parking);

    this.#map.addParkingMarker(loc.lat, loc.lng, null);
    this.#map.flyTo(loc.lat, loc.lng, 17);
    this.#stopTimer();
    this.#startTimer();
    this.#acquireWakeLock();
    this.#syncUI();
    this.#ui.showToast('🔄 מיקום החניה הוחלף!', 'success');
    this.#showParkingNotification(parking);

    reverseGeocode(loc.lat, loc.lng).then(addr => {
      if (!addr || !this.#state.current || this.#state.current.id !== parking.id) return;
      this.#state.current.address = addr;
      VehicleController.setCurrent(vehicleId, this.#state.current);
      this.#ui.updateAddress(this.#state.current);
      this.#map.updateParkingMarkerPopup(addr);
      this.#showParkingNotification(this.#state.current);
    });
  }

  async #updateCurrentLocation() {
    if (!this.#state.current) return;
    this.#ui.showToast('מעדכן מיקום... 🎯', 'info');
    try {
      const loc = await this.#getCurrentLocation();
      this.#state.current.location = { lat: loc.lat, lng: loc.lng, accuracy: loc.accuracy || 0 };
      this.#state.current.address  = null;
      VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);

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
        VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
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
    this.#state.current       = null;
    this.#state.gpsSpeedSince   = null;
    this.#state.gpsEndSuggested = false;
    VehicleController.removeCurrent(this.#state.activeVehicleId);
    this.#map.removeParkingMarker();
    this.#stopTimer();
    this.#releaseWakeLock();
    this.#cancelParkingNotification();
    this.#syncUI();
    this.#ui.showToast('✅ החניה הועברה להיסטוריה', 'success');
  }

  #addToHistory(parking) {
    if (!parking) return;
    this.#state.history.unshift({ ...parking });
    if (this.#state.history.length > CFG.maxHistory) {
      this.#state.history = this.#state.history.slice(0, CFG.maxHistory);
    }
    VehicleController.setHistory(this.#state.activeVehicleId, this.#state.history);
  }

  // ── VEHICLE MANAGEMENT ────────────────────────────────────────
  #switchVehicle(id, { silent = false } = {}) {
    if (id === this.#state.activeVehicleId) return;
    this.#stopTimer();
    this.#map.removeParkingMarker();

    VehicleController.setActive(id);
    this.#state.activeVehicleId  = id;
    this.#state.current          = VehicleController.getCurrent(id);
    this.#state.history          = VehicleController.getHistory(id);
    this.#state.gpsSpeedSince    = null;
    this.#state.gpsEndSuggested  = false;

    if (this.#state.current) {
      this.#map.addParkingMarker(
        this.#state.current.location.lat,
        this.#state.current.location.lng,
        this.#state.current.address
      );
      this.#map.flyTo(this.#state.current.location.lat, this.#state.current.location.lng, 15);
      this.#startTimer();
    }
    this.#syncUI();
    if (this.#state.current) {
      this.#acquireWakeLock();
      this.#showParkingNotification(this.#state.current);
    } else {
      this.#releaseWakeLock();
      this.#cancelParkingNotification();
    }
    if (!silent) {
      const v = VehicleController.getById(id);
      this.#ui.showToast(`${v?.icon || '🚗'} עבר ל${v?.name || 'רכב'}`, 'info');
    }
  }

  #openVehicleModal(vehicle) {
    this.#state.vehicleEditId = vehicle ? vehicle.id : null;
    const title = Utils.el('vehicleModalTitle');
    if (title) title.textContent = vehicle ? 'ערוך רכב' : 'הוסף רכב';
    this.#ui.populateVehicleModal(vehicle);
    this.#ui.openModal('vehicleModal');
  }

  #saveVehicle() {
    const { name, icon, plate, color, bluetoothDevice } = this.#ui.getVehicleModalValues();
    if (!name) { this.#ui.showToast('יש להזין שם לרכב', 'warning'); return; }

    if (this.#state.vehicleEditId) {
      VehicleController.update(this.#state.vehicleEditId, name, icon, plate, color, bluetoothDevice);
      this.#ui.showToast('✅ הרכב עודכן', 'success');
    } else {
      const v = VehicleController.add(name, icon, plate, color, bluetoothDevice);
      if (!v) { this.#ui.showToast(`ניתן להוסיף עד ${CFG.maxVehicles} רכבים`, 'warning'); return; }
      this.#ui.showToast(`${icon} ${name} נוסף!`, 'success');
    }

    this.#state.vehicles = VehicleController.getAll();
    this.#closeModal('vehicleModal');
    this.#syncUI();
    this.#ui.renderSettingsView(this.#state, this.#settingsCbs());
    this.#updateBtBadge();
  }

  #openVehicleDeleteModal(id, name) {
    this.#state.vehicleDeleteId = id;
    const desc = Utils.el('vehicleDeleteDesc');
    if (desc) {
      const nameEl = document.createElement('strong');
      nameEl.textContent = name;
      desc.innerHTML = '';
      desc.appendChild(nameEl);
      const txt = document.createTextNode(' — כל נתוני החניה יימחקו לצמיתות.');
      desc.appendChild(txt);
    }
    this.#ui.openModal('vehicleDeleteModal');
  }

  #confirmDeleteVehicle() {
    const id = this.#state.vehicleDeleteId;
    if (!id) return;
    const wasActive = id === this.#state.activeVehicleId;
    const ok = VehicleController.remove(id);
    if (!ok) { this.#ui.showToast('לא ניתן למחוק את הרכב האחרון', 'error'); return; }

    this.#state.vehicles = VehicleController.getAll();
    if (this.#state.btPendingVehicleId === id) this.#closeModal('btParkingModal');
    this.#closeModal('vehicleDeleteModal');

    if (wasActive) {
      const nextId = this.#state.vehicles[0]?.id;
      if (nextId) this.#switchVehicle(nextId, { silent: true });
    }
    this.#ui.renderSettingsView(this.#state, this.#settingsCbs());
    this.#updateBtBadge();
    this.#ui.showToast('🗑️ הרכב נמחק', 'info');
  }

  // Returns a callbacks object for renderSettingsView (DRY helper)
  #settingsCbs() {
    return {
      onEdit:          v  => this.#openVehicleModal(v),
      onDelete:        (id, nm) => this.#openVehicleDeleteModal(id, nm),
      onAdd:           () => this.#openVehicleModal(null),
      onClearParking:  id => this.#clearVehicleParking(id),
      hasParking:      id => !!VehicleController.getCurrent(id),
    };
  }

  #clearVehicleParking(vehicleId) {
    const current = VehicleController.getCurrent(vehicleId);
    if (!current) return;

    // Move to history
    const hist = VehicleController.getHistory(vehicleId);
    hist.unshift({ ...current });
    if (hist.length > CFG.maxHistory) hist.splice(CFG.maxHistory);
    VehicleController.setHistory(vehicleId, hist);
    VehicleController.removeCurrent(vehicleId);

    const isActive = vehicleId === this.#state.activeVehicleId;
    if (isActive) {
      this.#state.current         = null;
      this.#state.history         = hist;
      this.#state.gpsSpeedSince   = null;
      this.#state.gpsEndSuggested = false;
      this.#map.removeParkingMarker();
      this.#stopTimer();
      this.#releaseWakeLock();
      this.#cancelParkingNotification();
      this.#syncUI();
    }

    this.#ui.renderSettingsView(this.#state, this.#settingsCbs());
    const v = VehicleController.getById(vehicleId);
    this.#ui.showToast(`${v?.icon || '🚗'} החניה הועברה להיסטוריה`, 'info');
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
    VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
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
    VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
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
    VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
    this.#ui.renderAttachments(this.#state.current);
    this.#ui.updateMediaTiles(this.#state.current);
    this.#ui.closeModal('textModal');
    this.#ui.showToast(text ? '✅ תיאור נשמר!' : '🗑️ תיאור נמחק', 'success');
  }

  #deletePhoto() {
    if (!this.#state.current) return;
    if (!confirm('למחוק את התמונה?')) return;
    this.#state.current.photo = null;
    VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
    this.#ui.renderAttachments(this.#state.current);
    this.#ui.updateMediaTiles(this.#state.current);
    this.#ui.showToast('🗑️ תמונה נמחקה', 'info');
  }

  #deleteVoice() {
    if (!this.#state.current) return;
    if (!confirm('למחוק את ההקלטה?')) return;
    this.#state.current.voice = null;
    this.#state.current.voiceDuration = 0;
    VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
    this.#ui.renderAttachments(this.#state.current);
    this.#ui.updateMediaTiles(this.#state.current);
    this.#ui.showToast('🗑️ הקלטה נמחקה', 'info');
  }

  #deleteText() {
    if (!this.#state.current) return;
    this.#state.current.description = null;
    VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
    this.#ui.renderAttachments(this.#state.current);
    this.#ui.updateMediaTiles(this.#state.current);
    this.#ui.showToast('🗑️ תיאור נמחק', 'info');
  }

  // ── WAKE LOCK ─────────────────────────────────────────────────
  async #acquireWakeLock() {
    if (!('wakeLock' in navigator)) return;
    if (this.#wakeLock && !this.#wakeLock.released) return;
    try {
      this.#wakeLock = await navigator.wakeLock.request('screen');
    } catch { /* non-fatal; wake lock is a progressive enhancement */ }
  }

  async #releaseWakeLock() {
    if (!this.#wakeLock) return;
    try { await this.#wakeLock.release(); } catch { /* ignore */ }
    this.#wakeLock = null;
  }

  // ── PARKING NOTIFICATION ──────────────────────────────────────
  async #showParkingNotification(parking) {
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    const reg = await navigator.serviceWorker.ready.catch(() => null);
    if (!reg) return;
    const body = normalizeAddress(parking.address) ||
      `${parking.location.lat.toFixed(5)}, ${parking.location.lng.toFixed(5)}`;
    reg.showNotification('FindMyCar — חניה פעילה 🅿️', {
      body,
      tag:      CFG.keys.notifTag,
      icon:     './icons/icon-192.png',
      badge:    './icons/icon-192.png',
      renotify: false,
      silent:   true,
    });
  }

  async #cancelParkingNotification() {
    if (!('Notification' in window) || Notification.permission !== 'granted') return;
    const reg = await navigator.serviceWorker.ready.catch(() => null);
    if (!reg) return;
    const notifs = await reg.getNotifications({ tag: CFG.keys.notifTag }).catch(() => []);
    notifs.forEach(n => n.close());
  }

  // ── GPS AUTO-END ──────────────────────────────────────────────
  #getGpsSettings() {
    return Store.get(CFG.keys.gpsAutoEnd, { enabled: false });
  }

  #checkGpsSpeed(speed) {
    if (!this.#state.current || this.#state.gpsEndSuggested) return;
    if (!this.#getGpsSettings().enabled) return;
    if (speed === null || speed === undefined || Number.isNaN(speed) || speed < CFG.gpsSpeedThreshold) {
      this.#state.gpsSpeedSince = null;
      return;
    }
    if (!this.#state.gpsSpeedSince) {
      this.#state.gpsSpeedSince = Date.now();
    } else if (Date.now() - this.#state.gpsSpeedSince >= CFG.gpsSpeedDuration) {
      this.#state.gpsSpeedSince = null;
      this.#suggestGpsEnd();
    }
  }

  // Second, independent signal alongside speed: catches movement that
  // wouldn't cross the speed threshold (e.g. a device that never reports
  // coords.speed, or being driven away slowly in traffic).
  #checkGpsDistance(lat, lng) {
    if (!this.#state.current || this.#state.gpsEndSuggested) return;
    if (!this.#getGpsSettings().enabled) return;
    const { lat: pLat, lng: pLng } = this.#state.current.location;
    if (Utils.distance(lat, lng, pLat, pLng) >= CFG.gpsDistanceThreshold) this.#suggestGpsEnd();
  }

  #suggestGpsEnd() {
    if (this.#state.gpsEndSuggested) return; // race guard: speed+distance can both fire on the same position update
    this.#state.gpsEndSuggested = true;
    this.#state.gpsSpeedSince   = null;
    this.#ui.openModal('gpsEndModal');
    this.#notifyIfBackground('🚗 מזוהה נסיעה', 'ייתכן שהרכב זז ממקום החניה. פתח את האפליקציה לסיים את החניה.');
  }

  // Background-only system notification alongside an in-app toast/modal —
  // if the app is visible the on-screen UI already alerts the user, so a
  // notification would just be redundant noise.
  #notifyIfBackground(title, body) {
    if (document.visibilityState === 'visible') return;
    Notify.show(title, body);
  }

  // ── BLUETOOTH ─────────────────────────────────────────────────
  #getBtSettings() {
    return Store.get(CFG.keys.bluetoothSettings, { enabled: true });
  }

  #onBtConnected(label) {
    const vehicles = this.#state.vehicles;
    for (const v of vehicles) {
      if (v.bluetoothDevice !== label) continue;
      if (!VehicleController.getCurrent(v.id)) continue;
      if (v.bluetoothAutoEnd) {
        this.#markBtEnd(v.id, label);
        this.#btEndParking(v.id);
        this.#ui.showToast(`🔵 ${v.icon} ${v.name} — חניה הסתיימה אוטומטית`, 'success');
        this.#notifyIfBackground('🔵 חניה הסתיימה אוטומטית', `${v.icon} ${v.name} — זוהה חיבור Bluetooth`);
      } else {
        if (this.#state.btPendingVehicleId) continue; // confirm modal already open; keep processing autoEnd vehicles
        this.#state.btPendingVehicleId = v.id;
        this.#state.btPendingLabel     = label;
        const title = Utils.el('btParkingTitle');
        const desc  = Utils.el('btParkingDesc');
        if (title) title.textContent = `${v.icon} הגעת לרכב?`;
        if (desc)  desc.textContent  = `זוהה חיבור Bluetooth — יש חניה פעילה של ${v.name}`;
        this.#ui.openModal('btParkingModal');
        this.#notifyIfBackground(`${v.icon} הגעת לרכב?`, `זוהה חיבור Bluetooth — יש חניה פעילה של ${v.name}. פתח את האפליקציה לאישור.`);
      }
    }
  }

  async #onBtDisconnected(label) {
    const vehicles = this.#state.vehicles;
    for (const v of vehicles) {
      if (v.bluetoothDevice !== label) continue;
      if (!v.bluetoothAutoStart) continue;
      if (VehicleController.getCurrent(v.id)) continue; // already has parking

      // Switch to this vehicle if needed silently, then save parking.
      // Roll back the switch if GPS fails so the user's active parking remains visible.
      const needsSwitch = v.id !== this.#state.activeVehicleId;
      const prevId      = this.#state.activeVehicleId;
      if (needsSwitch) this.#switchVehicle(v.id, { silent: true });
      await this.#saveNewParking();
      if (!this.#state.current) {
        if (needsSwitch) this.#switchVehicle(prevId, { silent: true }); // GPS failed — restore previous vehicle
        break;
      }

      const saved = VehicleController.getCurrent(v.id);
      if (saved) {
        saved.btStartDevice = label;
        VehicleController.setCurrent(v.id, saved);
        if (v.id === this.#state.activeVehicleId && this.#state.current?.id === saved.id) {
          this.#state.current.btStartDevice = label;
        }
      }

      this.#notifyIfBackground('🅿️ חניה חדשה נשמרה אוטומטית', `${v.icon} ${v.name} — זוהה ניתוק Bluetooth`);

      if (v.bluetoothStartPopup) {
        const subtitle = Utils.el('btStartPopupSubtitle');
        if (subtitle) subtitle.textContent = `${v.icon} ${v.name}`;
        this.#ui.openModal('btStartPopupModal');
      }
    }
  }

  #markBtEnd(vehicleId, label) {
    const now = new Date().toISOString();
    if (vehicleId === this.#state.activeVehicleId) {
      if (!this.#state.current) return;
      this.#state.current.btEndDevice = label;
      this.#state.current.btEndTime   = now;
      VehicleController.setCurrent(vehicleId, this.#state.current);
    } else {
      const parking = VehicleController.getCurrent(vehicleId);
      if (!parking) return;
      parking.btEndDevice = label;
      parking.btEndTime   = now;
      VehicleController.setCurrent(vehicleId, parking);
    }
  }

  #btEndParking(vehicleId) {
    if (vehicleId === this.#state.activeVehicleId) {
      this.#resetParking();
    } else {
      this.#clearVehicleParking(vehicleId);
    }
  }

  async #btScanDevices(retried = false) {
    const devices   = await this.#bluetooth.getDevices();
    const hasLabels = devices.some(d => d.label);
    if (!hasLabels) {
      if (retried) {
        this.#ui.showToast('לא ניתן לזהות מכשירי Bluetooth', 'error');
        return;
      }
      const isNative = NativeBluetoothController.isSupported();
      this.#ui.showBtPermissionRequest(async () => {
        const granted = await this.#bluetooth.requestPermission();
        if (granted) {
          await this.#btScanDevices(true);
          return;
        }
        // Android stops showing the permission dialog after the user denies
        // it a couple of times — requestPermission() then just silently
        // resolves false forever, which looks exactly like a bug. Detect
        // that state and send the user straight to the app's system
        // settings screen instead of a dead-end error toast.
        const status = isNative ? await this.#bluetooth.permissionStatus?.() : null;
        if (status?.permanentlyDenied) {
          this.#ui.showToast('הרשאת Bluetooth נחסמה. פתח את הגדרות האפליקציה כדי לאשר אותה', 'error');
          await this.#bluetooth.openAppSettings?.();
        } else {
          this.#ui.showToast(isNative ? 'לא ניתן לגשת ל-Bluetooth' : 'לא ניתן לגשת למיקרופון', 'error');
        }
      }, isNative ? {
        message:     'נדרש אישור גישה ל-Bluetooth כדי לזהות מכשירים מקושרים',
        buttonLabel: '🔵 אשר גישה',
      } : undefined);
    } else {
      const emptyMessage = NativeBluetoothController.isSupported()
        ? 'לא נמצאו מכשירים מזווגים. זווג מכשיר Bluetooth בהגדרות האנדרואיד ונסה שוב.'
        : undefined;
      this.#ui.showBtDeviceList(devices, label => {
        this.#ui.setBtDeviceValue(label);
        this.#ui.showToast('🔵 מכשיר קושר! פתח את הגדרות ה-Bluetooth כדי להפעיל התחלה/סיום חניה אוטומטיים', 'info');
      }, emptyMessage);
    }
  }

  #btSettingsCbs() {
    return {
      onToggleEnabled: async enabled => {
        Store.set(CFG.keys.bluetoothSettings, { ...this.#getBtSettings(), enabled });
        if (enabled) {
          // Re-check/re-prompt here too — priming at app open may have been
          // denied, or the user could be granting it for the first time
          // right now by turning this on.
          await this.#bluetooth.requestPermission?.().catch(() => {});
          this.#bluetooth.startWatch();
        } else {
          this.#bluetooth.stopWatch();
        }
        this.#refreshBtModal();
      },
      onToggleVehicle: (vehicleId, updates) => {
        VehicleController.updateBluetooth(vehicleId, updates);
        this.#state.vehicles = VehicleController.getAll();
        this.#refreshBtModal();
      },
      onSetAll: updates => {
        VehicleController.updateAllBluetooth(updates);
        this.#state.vehicles = VehicleController.getAll();
        this.#refreshBtModal();
      },
    };
  }

  async #refreshBtModal() {
    const isNative = NativeBluetoothController.isSupported();
    const [btStatus, notifGranted] = isNative
      ? await Promise.all([this.#bluetooth.permissionStatus?.(), Notify.checkPermission()])
      : [null, true];
    const needsSettings = !!btStatus?.permanentlyDenied || notifGranted === false;
    this.#ui.renderBtSettingsModal(
      this.#getBtSettings(),
      this.#state.vehicles,
      this.#btSettingsCbs(),
      needsSettings ? { onOpenSettings: () => this.#bluetooth.openAppSettings?.() } : null
    );
    this.#updateBtBadge();
  }

  #openBtSettingsModal() {
    this.#refreshBtModal();
    this.#ui.openModal('btSettingsModal');
  }

  #updateBtBadge() {
    const count = this.#state.vehicles.filter(v => v.bluetoothDevice).length;
    this.#ui.updateBtSettingsBtn(count);
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
    const v = VehicleController.getById(this.#state.activeVehicleId);
    const text = [
      `${v?.icon || '🚗'} ${v?.name || 'הרכב'} - FindMyCar`,
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

  // ── WHATSAPP SHARING ──────────────────────────────────────────
  #openWhatsAppModal() {
    if (!this.#state.current) { this.#ui.showToast('שמור חניה קודם', 'warning'); return; }
    const v = VehicleController.getById(this.#state.activeVehicleId);
    this.#ui.populateWhatsAppModal(this.#state.current, v?.name || 'הרכב');
    this.#ui.openModal('whatsappModal');
  }

  async #executeWhatsAppShare() {
    const p = this.#state.current;
    if (!p) return;
    const opts = this.#ui.getWhatsAppOptions();
    const { lat, lng } = p.location;
    const v = VehicleController.getById(this.#state.activeVehicleId);

    const lines = [];
    if (opts.includeVehicle) lines.push(`${v?.icon || '🚗'} ${v?.name || 'הרכב'} - FindMyCar`);
    if (opts.includeAddress) {
      const addr = normalizeAddress(p.address) || `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
      lines.push(`📍 ${addr}`);
    }
    if (opts.includeTime)    lines.push(`⏰ ${Utils.formatDate(p.timestamp)} ${Utils.formatTime(p.timestamp)}`);
    if (opts.includeDesc && p.description)  lines.push(`📝 ${p.description}`);
    if (opts.includeMapLink) lines.push(`🗺️ https://maps.google.com/maps?q=${lat},${lng}`);

    const text = lines.join('\n');

    this.#ui.closeModal('whatsappModal');

    if (opts.includePhoto && p.photo) {
      try {
        const file = Utils.dataUrlToFile(p.photo, 'parking.jpg');
        if (navigator.canShare?.({ files: [file] })) {
          await navigator.share({ text, files: [file] });
          return;
        }
      } catch (err) {
        if (err.name !== 'AbortError') {
          this.#ui.showToast('לא ניתן לשתף תמונה, שולח טקסט בלבד', 'info');
        } else {
          return;
        }
      }
    }

    // Text-only WhatsApp link
    window.open(`https://wa.me/?text=${encodeURIComponent(text)}`, '_blank');
  }

  // ── HISTORY ───────────────────────────────────────────────────
  #clearHistory() {
    if (!this.#state.history.length) return;
    if (!confirm(`למחוק ${this.#state.history.length} חניות מההיסטוריה?`)) return;
    this.#state.history = [];
    VehicleController.setHistory(this.#state.activeVehicleId, []);
    this.#ui.updateHistoryView(this.#state);
    this.#ui.updateHistoryBadge(this.#state);
    this.#ui.showToast('🗑️ ההיסטוריה נמחקה', 'info');
  }

  #deleteHistoryItem(id) {
    this.#state.history = this.#state.history.filter(i => i.id !== id);
    VehicleController.setHistory(this.#state.activeVehicleId, this.#state.history);
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
    if (id === 'photoModal')        this.#camera.close();
    if (id === 'voiceModal')        this.#voice.close();
    if (id === 'detailModal')       this.#map.destroyDetailMap();
    if (id === 'btParkingModal') { this.#state.btPendingVehicleId = null; this.#state.btPendingLabel = null; }
    if (id === 'gpsEndModal')    this.#state.gpsEndSuggested = true;
    if (id === 'settingsView')      return; // views are not modals
    this.#ui.closeModal(id);
  }

  // ── VIEWS ─────────────────────────────────────────────────────
  #showView(viewId) {
    this.#state.currentView = viewId;
    this.#ui.showView(viewId, this.#map);
    if (viewId === 'historyView')  this.#ui.updateHistoryView(this.#state);
    if (viewId === 'settingsView') {
      this.#ui.renderSettingsView(this.#state, this.#settingsCbs());
      this.#updateBtBadge();
    }
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
    if ('serviceWorker' in navigator) {
      // Skip auto-reload if this page load was triggered by #reloadApp() to avoid double-reload
      if (!sessionStorage.getItem('fmc_manual_reload')) {
        let refreshing = false;
        navigator.serviceWorker.addEventListener('controllerchange', () => {
          if (!refreshing) { refreshing = true; window.location.reload(); }
        });
      }
      sessionStorage.removeItem('fmc_manual_reload');
    }

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

  async #reloadApp() {
    sessionStorage.setItem('fmc_manual_reload', '1');
    try {
      const keys = await caches.keys();
      await Promise.all(keys.map(k => caches.delete(k)));
      const regs = await navigator.serviceWorker?.getRegistrations?.() ?? [];
      await Promise.all(regs.map(r => r.unregister()));
    } catch (e) {
      console.warn('reloadApp cleanup:', e);
    }
    window.location.reload();
  }

  // ── BACKUP & RESTORE ─────────────────────────────────────────
  // Manual transfer between the PWA and the Android app: they run on
  // different WebView/browser origins, so localStorage can't be shared
  // directly — a backup file (exported from one, imported into the other)
  // is the only way to move data across without a server.
  async #exportData() {
    const payload = {
      app:        'findmycar',
      formatVersion: 1,
      appVersion: CFG.version,
      exportedAt: new Date().toISOString(),
      data:       Store.exportAll(),
    };
    const json     = JSON.stringify(payload, null, 2);
    const filename = `findmycar-backup-${new Date().toISOString().slice(0, 10)}.json`;

    const Filesystem = window.Capacitor?.Plugins?.Filesystem;
    const Share      = window.Capacitor?.Plugins?.Share;
    if (window.Capacitor?.isNativePlatform?.() && Filesystem && Share) {
      try {
        const { uri } = await Filesystem.writeFile({
          path: filename, data: json, directory: 'CACHE', encoding: 'utf8',
        });
        await Share.share({ title: 'גיבוי FindMyCar', dialogTitle: 'שתף/שמור את קובץ הגיבוי', url: uri });
      } catch (e) {
        console.warn('Export failed:', e);
        this.#ui.showToast('שגיאה בייצוא הנתונים', 'error');
        return;
      }
    } else {
      const blob = new Blob([json], { type: 'application/json' });
      const url  = URL.createObjectURL(blob);
      const a    = document.createElement('a');
      a.href = url; a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    }
    this.#ui.showToast('📤 קובץ הגיבוי מוכן', 'success');
  }

  async #importData(file) {
    let payload;
    try {
      payload = JSON.parse(await file.text());
    } catch {
      this.#ui.showToast('קובץ הגיבוי אינו תקין', 'error');
      return;
    }
    if (payload?.app !== 'findmycar' || !payload?.data || typeof payload.data !== 'object') {
      this.#ui.showToast('קובץ הגיבוי אינו תקין', 'error');
      return;
    }
    const ok = confirm('פעולה זו תחליף רכבים, היסטוריה והגדרות קיימים בנתונים מקובץ הגיבוי. להמשיך?');
    if (!ok) return;

    try {
      Store.importAll(payload.data);
    } catch (e) {
      console.warn('Import failed:', e);
      this.#ui.showToast('שגיאה בייבוא הקובץ', 'error');
      return;
    }

    // The imported backup can turn on settings this install never asked
    // permission for (e.g. a vehicle with Bluetooth auto-end enabled, or GPS
    // auto-end) — re-run the same priming sequence as first launch instead
    // of relying on the reload below to happen to trigger it via #init().
    await this.#primeNativePermissions();

    this.#ui.showToast('✅ הנתונים יובאו בהצלחה. טוען מחדש...', 'success');
    setTimeout(() => window.location.reload(), 1200);
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
