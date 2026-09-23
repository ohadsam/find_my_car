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
import { DiagLog } from './diag-log.js';
import { OemSetup } from './oem-setup.js';

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
    gpsSpeedAccumMs:      0,     // ms ACCUMULATED at or above CFG.gpsSpeedThreshold this parking session
    gpsLastSpeedSampleAt: null,  // Date.now() of the previous speed sample, for the interval above
    gpsPrevFix:           null,  // {lat, lng, at} of the previous position fix, for deriving speed
    gpsLastAboveAt:       null,  // Date.now() of the last above-threshold sample, for CFG.gpsEvidenceTtlMs expiry
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
  // Last widget/notification action performed, for performWidgetAction()'s
  // duplicate-delivery guard: {key, at, message}.
  #lastWidgetAction = null;
  #reconcilingBt = false;
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

    // Merges native-only lifecycle events (foreground service/GPS watch
    // start-stop, raw BT broadcast receipt) recorded while the app was
    // closed into the diagnostic log — awaited (unlike the other
    // #reconcilePending*() calls below) specifically so these historical
    // entries land BEFORE anything else this session logs, preserving
    // correct chronological order without needing to sort by timestamp at
    // render time. Resolves near-instantly (a local SharedPreferences read)
    // and is itself a no-op in the browser/PWA, so this adds no meaningful
    // delay to the rest of init.
    await this.#reconcileNativeLog().catch(() => {});

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

    // Stage 4 of the native background-detection migration (see CLAUDE.md):
    // listen for the native GPS shadow-mode decision so it's visible in the
    // diagnostic log alongside the real BT-SHADOW entries — no-op in the
    // browser/PWA.
    WidgetBridge.initShadowListener();

    // Shade buttons for the confirmation notifications — see
    // #initNotificationActions(). Not awaited: it only registers listeners.
    this.#initNotificationActions().catch(() => {});
    if (this.#getBtSettings().enabled) {
      DiagLog.log('BT', 'app init: starting Bluetooth watch (master switch is on)');
      this.#bluetooth.startWatch();
    } else {
      DiagLog.log('BT', 'app init: Bluetooth watch NOT started — master switch is off');
    }

    // Stage 6 of the native background-detection migration (see CLAUDE.md):
    // replays what BtDecisionEngine recorded for real while the WebView was
    // unreachable, then clears it — not awaited, so it doesn't block the
    // rest of init. No-op in the browser/PWA (getPendingActions() resolves
    // to [] there).
    this.#reconcilePendingBtActions().catch(() => {});

    // Stage 7 of the native background-detection migration (see CLAUDE.md):
    // replays a GPS end-suggestion recorded while the WebView was
    // unreachable as the same confirmation modal — never an automatic end.
    // Not awaited, so it doesn't block the rest of init. No-op in the
    // browser/PWA.
    this.#reconcilePendingGpsSuggestion().catch(() => {});

    // Stage 8 of the native background-detection migration (see CLAUDE.md):
    // replays widget quick-actions tapped while the WebView was
    // unreachable, then clears them. Not awaited, so it doesn't block the
    // rest of init. No-op in the browser/PWA.
    this.#reconcilePendingWidgetActions().catch(() => {});

    // The walk-away parking suggestion (CLAUDE.md "Walk-away parking
    // suggestion"). Unlike the other reconcilers this also runs on resume —
    // it is normally raised while the app is merely backgrounded, not killed,
    // so waiting for the next cold start would show it far too late.
    this.#reconcilePendingParkingSuggestion().catch(() => {});

    const gpsToggle = Utils.el('gpsAutoEndToggle');
    if (gpsToggle) gpsToggle.checked = this.#getGpsSettings().enabled;

    const dailyStatusToggle = Utils.el('dailyStatusToggle');
    if (dailyStatusToggle) dailyStatusToggle.checked = this.#getDailyStatusSettings().enabled;

    // The device-settings guide only means anything on native (the OemSetup
    // plugin is absent in the browser), so its Settings entry point stays
    // hidden on the PWA rather than opening a modal with nothing to show.
    if (OemSetup.isSupported()) {
      const oemSection = Utils.el('oemSetupSection');
      if (oemSection) oemSection.style.display = '';
      // Auto-open once the app has settled, and only while something is
      // genuinely outstanding — never awaited, so a slow plugin call can't
      // hold up init. Deliberately after the loading screen fades, since it
      // is a modal over the main UI, not part of startup.
      setTimeout(() => {
        OemSetup.shouldAutoShow()
          .then(show => { if (show) this.#openOemSetupModal(); })
          .catch(() => {});
      }, 2500);
    }

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
    this.#startDiagHeartbeat();

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
    DiagLog.log('PERM', 'priming permissions (geolocation, camera/mic, Bluetooth, notifications)');

    await new Promise(resolve => {
      if (!navigator.geolocation) { resolve(); return; }
      navigator.geolocation.getCurrentPosition(
        () => { DiagLog.log('PERM', 'geolocation: granted'); resolve(); },
        err => { DiagLog.log('PERM', `geolocation: ${FindMyCarApp.describeGeoError(err)}`); resolve(); },
        { timeout: 8000 }
      );
    });

    try {
      const stream = await navigator.mediaDevices?.getUserMedia?.({ video: true, audio: true });
      stream?.getTracks().forEach(t => t.stop());
      DiagLog.log('PERM', 'camera/mic: granted');
    } catch {
      DiagLog.log('PERM', 'camera/mic: denied or unavailable');
      // Denied or no camera/mic — the camera/voice modals already fall back
      // to their own permission-error UI when actually opened.
    }

    await this.#bluetooth.requestPermission?.().catch(() => {});
    const notifGranted = await Notify.ensurePermission().catch(() => false);
    DiagLog.log('PERM', `notifications: ${notifGranted ? 'granted' : 'denied'}`);

    // Standard Android battery optimization is a very common, deterministic
    // cause of BT/GPS background detection silently going dead — the OS (or
    // an aggressive OEM skin) reclaims the Activity/WebView despite the
    // foreground service unless the app is explicitly exempted. Only prompt
    // if not already exempted, so this doesn't nag on every launch.
    const battery = await this.#bluetooth.batteryOptimizationStatus?.().catch(() => null);
    if (battery && !battery.ignoring) {
      await this.#bluetooth.requestIgnoreBatteryOptimizations?.().catch(() => {});
    }
    DiagLog.log('PERM', `battery optimization: ${battery?.ignoring ? 'already exempted' : 'requested exemption'}`);
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

    Utils.el('openOemSetupBtn')?.addEventListener('click',     () => this.#openOemSetupModal());
    Utils.el('oemSetupRefreshBtn')?.addEventListener('click',  () => this.#refreshOemSetupView());
    Utils.el('oemSetupDismissBtn')?.addEventListener('click',  () => {
      OemSetup.setManual({ dismissed: true });
      this.#closeModal('oemSetupModal');
      this.#ui.showToast('המדריך לא יוצג שוב אוטומטית — הוא נשאר זמין בהגדרות', 'info');
    });

    Utils.el('openDiagLogBtn')?.addEventListener('click',   () => this.#openDiagLogModal());
    Utils.el('diagLogRefreshBtn')?.addEventListener('click', () => this.#refreshDiagLogView());
    Utils.el('diagLogVehicleFilter')?.addEventListener('change', () => this.#refreshDiagLogView());
    Utils.el('diagLogCategoryFilter')?.addEventListener('change', () => this.#refreshDiagLogView());
    Utils.el('diagLogCopyBtn')?.addEventListener('click',   () => this.#copyDiagLog());
    Utils.el('diagLogExportBtn')?.addEventListener('click', () => this.#exportDiagLog());
    Utils.el('diagLogClearBtn')?.addEventListener('click',  () => this.#clearDiagLog());

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
      // Native's GpsDecisionEngine reads this from WidgetDataPlugin's
      // KEY_GPS_AUTO_END_ENABLED mirror to decide whether to suggest ending a
      // parking while the app is closed — without an immediate re-sync the
      // mirror stays stale until some unrelated parking-state change happens,
      // so the setting the user just flipped isn't the one actually in effect.
      this.#syncUI();
    });
    Utils.el('dailyStatusToggle')?.addEventListener('change', e => {
      Store.set(CFG.keys.dailyStatus, { enabled: e.target.checked });
      // Re-sync immediately (not just on the next unrelated state change) so
      // the native side schedules/cancels its daily alarm right away —
      // WidgetBridge.sync() reads this setting fresh on every call.
      this.#syncUI();
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

    Utils.el('walkAwayConfirmBtn')?.addEventListener('click', async () => {
      // Deliberately the plain UI close, NOT #closeModal: that one clears the
      // pending suggestion (correct for a dismissal), which would delete the
      // recorded location before saveAt could read it. #acceptWalkAwaySuggestion
      // clears the store itself once it has the entry in hand.
      this.#ui.closeModal('walkAwayModal');
      // Straight through the same headless action a shade button uses, so the
      // in-app answer and the notification answer cannot drift apart.
      await this.performWidgetAction('saveAt', null);
    });
    Utils.el('walkAwayDismissBtn')?.addEventListener('click', () => {
      this.#closeModal('walkAwayModal');
      DiagLog.log('WALK', 'walk-away suggestion dismissed by the user');
      WidgetBridge.clearPendingParkingSuggestion().catch(() => {});
    });

    Utils.el('installAcceptBtn')?.addEventListener('click',  () => this.#promptInstall());
    Utils.el('installDismissBtn')?.addEventListener('click', () => {
      Utils.el('installBanner').style.display = 'none';
    });

    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState !== 'visible') return;
      if (this.#state.current) this.#acquireWakeLock();
      if (this.#getBtSettings().enabled) this.#bluetooth.checkNow();
      this.#reconcilePendingParkingSuggestion().catch(() => {});
      // Also on resume, not only from #init(): the Activity often stays alive
      // while the app is closed, so a resume is NOT a fresh init — a pending
      // action recorded while the JS engine was frozen would otherwise sit
      // unreplayed until the next genuine cold start.
      this.#reconcilePendingBtActions().catch(() => {});
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
    DiagLog.log('GPS', 'watchPosition started');
    this.#state.watchId = navigator.geolocation.watchPosition(
      pos => this.#onPosition(pos),
      err => {
        console.warn('GPS error', err.code);
        DiagLog.log('GPS', `watchPosition error, code=${err.code} (${err.message || ''})`);
      },
      { enableHighAccuracy: true, maximumAge: 10000, timeout: 15000 }
    );
  }

  #onPosition(pos) {
    const { latitude: lat, longitude: lng, accuracy, speed } = pos.coords;
    this.#state.userPos = { lat, lng, accuracy };
    this.#map.updateUserMarker(lat, lng);
    this.#ui.updateDistance(this.#state);
    this.#checkGpsSpeed(this.#effectiveSpeed(speed, lat, lng), lat, lng);
    this.#checkGpsDistance(lat, lng);
  }

  /**
   * Turns a GeolocationPositionError into something a diagnostic log can be
   * read from. "denied or unavailable" used to cover all three codes, which
   * made a revoked permission indistinguishable from simply being indoors —
   * and that ambiguity is what left one real report unanswerable: the user
   * said location was on, the log said "denied or unavailable", and nothing
   * could tell which of the two was true.
   */
  /**
   * A parking that silently fails to save is the worst outcome there is — the
   * user believes their spot is recorded and finds out much later that it
   * isn't. So say what actually went wrong, and separate the two cases that
   * need completely different actions from the user:
   *
   *  - permission revoked → nothing they do in this app will help until they
   *    re-grant it, so offer the settings screen directly;
   *  - no fix → waiting or stepping outside will fix it.
   *
   * The native check is authoritative: a browser `PERMISSION_DENIED` and a
   * plain timeout are easy to confuse, and guessing wrong sends the user to
   * the wrong place. A notification goes out too, because on a Bluetooth
   * auto-start there is no screen to show a toast on.
   */
  async #reportLocationFailure(err) {
    let granted = null;
    try {
      if (OemSetup.isSupported()) granted = (await OemSetup.status())?.locationGranted ?? null;
    } catch { /* fall back to the browser's own error code below */ }

    const denied = granted === false || (granted === null && err?.code === 1);
    if (denied) {
      DiagLog.log('GPS', 'location permission is NOT granted — the parking could not be saved');
      this.#ui.showToast('אין הרשאת מיקום — החניה לא נשמרה. פתח הגדרות ואשר מיקום.', 'error');
      Notify.show('FindMyCar', '⚠️ החניה לא נשמרה — חסרה הרשאת מיקום');
      this.#bluetooth.openAppSettings?.();
      return;
    }
    this.#ui.showToast('לא ניתן לאתר מיקום כרגע — החניה לא נשמרה. נסה שוב בחוץ.', 'error');
    Notify.show('FindMyCar', '⚠️ החניה לא נשמרה — לא התקבל מיקום GPS');
  }

  static describeGeoError(err) {
    switch (err?.code) {
      case 1:  return 'PERMISSION DENIED — the app does not currently hold location permission ' +
                      '(an Android "only this time" grant is revoked once the app stops being used)';
      case 2:  return 'position unavailable — permission is fine, but no fix could be obtained (indoors / location services off)';
      case 3:  return 'timed out — permission is fine, but no fix arrived in time';
      default: return `failed — ${err?.message || 'unknown error'}`;
    }
  }

  /**
   * One high-accuracy attempt, then — rather than giving up — a relaxed one
   * that accepts a coarse or slightly stale fix. A single strict attempt fails
   * routinely indoors or in a car park, and when it does on an auto-start the
   * user gets nothing at all: no parking, and a toast they never see because
   * the app is backgrounded. A slightly less precise saved spot beats none.
   *
   * A denied permission is NOT retried — there is nothing a second attempt can
   * do about it, and retrying would just delay telling the user the truth.
   */
  #getCurrentLocation() {
    const attempt = opts => new Promise((resolve, reject) => {
      if (!navigator.geolocation) { reject(new Error('Geolocation not supported')); return; }
      navigator.geolocation.getCurrentPosition(
        pos => resolve({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy }),
        err => reject(err),
        opts
      );
    });

    return attempt({ enableHighAccuracy: true, timeout: 12000, maximumAge: 5000 })
      .catch(err => {
        if (err?.code === 1) throw err;
        DiagLog.log('GPS', `precise fix ${FindMyCarApp.describeGeoError(err)} — retrying with a coarse/cached fix`);
        return attempt({ enableHighAccuracy: false, timeout: 10000, maximumAge: 120000 });
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
        this.#syncUI();
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

  /**
   * @param presetLoc {lat, lng} to save at instead of the live GPS fix. Used
   *   by the walk-away suggestion, which must save where the car actually is
   *   (the spot captured when Bluetooth disconnected) rather than where the
   *   user is standing by the time they answer — they are a walk away by then.
   *   Everything after the fix is deliberately shared with the normal path:
   *   geocoding, widget sync, the notification and the wake lock all behave
   *   identically, because this is the same method, not a second save.
   */
  async #saveNewParking(presetLoc = null) {
    let loc = presetLoc;
    if (!loc) {
      this.#ui.showToast('מאתר מיקום... ⏳', 'info');
      try {
        loc = await this.#getCurrentLocation();
      } catch (err) {
        if (this.#state.userPos) {
          DiagLog.log('GPS', `save: ${FindMyCarApp.describeGeoError(err)} — falling back to the last watched position`);
          loc = this.#state.userPos;
        } else {
          DiagLog.log('GPS', `save ABORTED — no location: ${FindMyCarApp.describeGeoError(err)}`);
          await this.#reportLocationFailure(err);
          return;
        }
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
    this.#resetGpsDetection();
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
      this.#syncUI();
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
    let locErr = null;
    try {
      loc = await this.#getCurrentLocation();
    } catch (err) {
      locErr = err;
      loc = this.#state.userPos ?? null;
    } finally {
      this.#swapping = false;
    }

    if (!loc) {
      DiagLog.log('GPS', `swap ABORTED — no location: ${FindMyCarApp.describeGeoError(locErr)}`);
      await this.#reportLocationFailure(locErr);
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
    this.#resetGpsDetection();
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
      this.#syncUI();
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
      this.#syncUI();

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
        this.#syncUI();
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
    this.#resetGpsDetection();
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
    this.#resetGpsDetection();

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

  // Public (not #-private) on purpose — the Active Parking / Quick Save /
  // Mini Map widgets' quick-actions popup runs this directly against the
  // app's already-running WebView via evaluateJavascript() from
  // WidgetActionReceiver.kt, without ever bringing the app to the
  // foreground. Same idea as the existing BT-triggered background saves
  // (#onBtDisconnected already calls #switchVehicle/#saveNewParking while
  // the app isn't foregrounded) — just reachable from a widget tap too now.
  async performWidgetAction(action, vehicleId) {
    try {
      // Duplicate-delivery guard. A real report showed two identical
      // performWidgetAction('save') calls landing in the same second, saving
      // two parkings and posting two notifications: the two ran concurrently,
      // so each read "no parking yet" before the other wrote, and the
      // per-action guards below could not catch it. Returning the first call's
      // own result keeps the caller's Toast/notification truthful — the action
      // really was performed, just once.
      const key = `${action}:${vehicleId || ''}`;
      const recent = this.#lastWidgetAction;
      if (recent && recent.key === key && Date.now() - recent.at < CFG.widgetActionDedupeMs) {
        DiagLog.log('WIDGET', `ignored duplicate performWidgetAction(${action}) within ${CFG.widgetActionDedupeMs}ms`);
        return recent.message;
      }
      this.#lastWidgetAction = { key, at: Date.now(), message: 'מבצע…' };

      if (vehicleId && vehicleId !== this.#state.activeVehicleId &&
          this.#state.vehicles.some(v => v.id === vehicleId)) {
        this.#switchVehicle(vehicleId, { silent: true });
      }
      const v = VehicleController.getById(this.#state.activeVehicleId);
      const vLabel = v ? `${v.icon} ${v.name}` : '';
      let message;
      if (action === 'save') {
        if (this.#state.current) {
          message = 'יש כבר חניה פעילה — להחלפה השתמש ב"החלף חניה"';
        } else {
          await this.#saveNewParking();
          message = this.#state.current ? `🅿️ חניה נשמרה — ${vLabel}` : 'שמירת חניה נכשלה (בדוק מיקום GPS)';
        }
      } else if (action === 'swap') {
        if (!this.#state.current) {
          message = 'אין חניה פעילה להחלפה';
        } else {
          const prevId = this.#state.current.id;
          await this.#swapParking();
          message = this.#state.current?.id !== prevId ? `🔄 החניה הוחלפה — ${vLabel}` : 'החלפת חניה נכשלה (בדוק מיקום GPS)';
        }
      } else if (action === 'end') {
        const had = !!this.#state.current;
        this.#resetParking();
        message = had ? `✅ החניה הסתיימה — ${vLabel}` : 'אין חניה פעילה לסיום';
      } else if (action === 'saveAt') {
        // The walk-away suggestion's "save" answer. The location comes from the
        // pending entry (captured when Bluetooth disconnected), never from the
        // live fix — by now the user is a walk away from the car. Reaching it
        // through performWidgetAction keeps this on the one headless path, so
        // it inherits the dedupe guard, the replay queue and the result
        // notification for free.
        message = await this.#acceptWalkAwaySuggestion(vLabel);
      } else if (action === 'refresh') {
        // The widgets' ↻ button. Re-reads storage rather than trusting
        // #state, because the point of tapping refresh is to pick up a change
        // this page may have missed, then pushes the result through the one
        // choke point every widget update goes through.
        this.#state.vehicles = VehicleController.getAll();
        this.#state.current  = VehicleController.getCurrent(this.#state.activeVehicleId);
        this.#state.history  = VehicleController.getHistory(this.#state.activeVehicleId);
        this.#syncUI();
        message = 'הנתונים סונכרנו';
      } else {
        message = 'פעולה לא מוכרת';
      }
      this.#lastWidgetAction = { key, at: Date.now(), message };
      DiagLog.log('WIDGET', `performWidgetAction(${action}) → ${message}`, { vehicleName: v?.name, vehicleIcon: v?.icon });
      // Unconditional (not gated by document.visibilityState like
      // #notifyIfBackground) — a widget action, by definition, never has an
      // in-app UI open to see the result in; the Toast the widget popup
      // shows is only "מבצע…" (in progress), not the actual outcome, so
      // this notification is the only place the user finds out what
      // happened and to which vehicle.
      //
      // 'refresh' is the one deliberate exception: it changes no parking
      // state, and its whole result is visible on the widget the user just
      // tapped — a heads-up notification per refresh would be pure noise, and
      // noise is what teaches people to swipe these away unread.
      if (action !== 'refresh') Notify.show('FindMyCar', message);
      return message;
    } catch (e) {
      const errMsg = 'שגיאה בביצוע הפעולה';
      // Clear the dedupe marker: a failed attempt must not suppress a retry.
      this.#lastWidgetAction = null;
      DiagLog.log('WIDGET', `performWidgetAction(${action}) threw — ${e?.message || e}`);
      Notify.show('FindMyCar', errMsg);
      return errMsg;
    }
  }

  /**
   * Saves the parking a walk-away suggestion is about, at the spot recorded
   * when Bluetooth disconnected. Returns the message performWidgetAction
   * reports back (Toast + notification).
   *
   * Idempotency is enforced here rather than by the caller: the suggestion may
   * have been answered from the shade already, or the vehicle may have gained
   * a parking some other way in the meantime.
   */
  async #acceptWalkAwaySuggestion(vLabel) {
    const pending = await WidgetBridge.getPendingParkingSuggestion();
    await WidgetBridge.clearPendingParkingSuggestion();
    if (!pending) {
      DiagLog.log('WALK', 'saveAt requested but no walk-away suggestion is outstanding — ignoring');
      return 'אין הצעת חניה ממתינה';
    }
    if (this.#state.current) {
      DiagLog.log('WALK', 'saveAt ignored — this vehicle already has an active parking', { vehicleName: pending.vehicleName });
      return 'כבר קיימת חניה פעילה';
    }
    const hasFix = typeof pending.lat === 'number' && typeof pending.lng === 'number';
    // No captured fix: fall back to a live read rather than refusing. Less
    // accurate, but the user explicitly asked for the spot to be saved.
    await this.#saveNewParking(hasFix ? { lat: pending.lat, lng: pending.lng, accuracy: 0 } : null);
    if (!this.#state.current) return 'שמירת חניה נכשלה (בדוק מיקום GPS)';
    if (pending.label) {
      this.#state.current.btStartDevice = pending.label;
      VehicleController.setCurrent(this.#state.activeVehicleId, this.#state.current);
      this.#syncUI();
    }
    DiagLog.log('WALK', `saved the parking from the walk-away suggestion (${hasFix ? 'at the disconnect spot' : 'at the current location — no fix was captured'})`,
      { vehicleName: pending.vehicleName });
    return `🅿️ חניה נשמרה — ${vLabel}`;
  }

  /**
   * Replays a walk-away suggestion raised natively while the app wasn't in
   * front of the user, as the same confirmation modal a live one would show.
   * Never saves a parking by itself — like the GPS end-suggestion, this only
   * ever asks. Called fire-and-forget from #init() and on every resume, since
   * the suggestion is typically raised while the app is backgrounded rather
   * than killed.
   */
  async #reconcilePendingParkingSuggestion() {
    const pending = await WidgetBridge.getPendingParkingSuggestion();
    if (!pending) return;
    // Discard rather than ask about a vehicle that is no longer the one this
    // would act on, mirroring #reconcilePendingGpsSuggestion's own checks.
    if (pending.vehicleId !== this.#state.activeVehicleId) {
      DiagLog.log('WALK', `discarding walk-away suggestion for ${pending.vehicleName} — a different vehicle is active now`);
      await WidgetBridge.clearPendingParkingSuggestion();
      return;
    }
    if (this.#state.current) {
      DiagLog.log('WALK', 'discarding walk-away suggestion — a parking is already active', { vehicleName: pending.vehicleName });
      await WidgetBridge.clearPendingParkingSuggestion();
      return;
    }
    const age = Date.now() - (pending.timestamp || 0);
    if (age > CFG.walkWindowMs) {
      DiagLog.log('WALK', `discarding walk-away suggestion — it is ${Math.round(age / 60000)} minutes old`);
      await WidgetBridge.clearPendingParkingSuggestion();
      return;
    }
    DiagLog.log('WALK', 'showing the walk-away parking suggestion', { vehicleName: pending.vehicleName });
    const sub = Utils.el('walkAwaySubtitle');
    if (sub) sub.textContent = `${pending.vehicleName || ''} — נראה שחנית והתרחקת מהרכב`;
    this.#ui.openModal('walkAwayModal');
  }

  // Stage 8 of the native background-detection migration (see CLAUDE.md
  // "Native background detection"): replays widget quick-actions
  // WidgetActionReceiver recorded while the WebView was unreachable —
  // through the SAME real performWidgetAction() a live tap would have
  // used, not a separate reimplementation. performWidgetAction() already
  // has its own idempotency checks per action (e.g. "save" is a no-op
  // message if a parking already exists), so replaying an already-
  // consistent state is safe. Processed sequentially to match how widget
  // taps only ever happen one at a time. No-op in the browser/PWA
  // (getPendingWidgetActions() resolves to []).
  async #reconcilePendingWidgetActions() {
    const actions = await WidgetBridge.getPendingWidgetActions();
    if (!actions.length) return;
    for (const a of actions) {
      DiagLog.log('WIDGET', `replaying pending widget action=${a.action} vehicleId=${a.vehicleId || '(active)'}`);
      try {
        await this.performWidgetAction(a.action, a.vehicleId ?? null);
      } catch (e) {
        DiagLog.log('WIDGET', `pending widget action replay threw — ${e?.message || e}`);
      }
    }
    await WidgetBridge.clearPendingWidgetActions();
  }

  // Merges NativeLogStore's native-only events — background-machinery
  // lifecycle transitions (foreground service/GPS watch start-stop, raw BT
  // ACL broadcast receipt; category SERVICE) and native<->JS Capacitor
  // plugin message-bus traffic (every @PluginMethod call received from JS,
  // every notifyListeners() call sent to JS; category BRIDGE) — see
  // CLAUDE.md "Native background service log" / "Native<->JS message bus
  // log" — into the in-app diagnostic log under each entry's OWN category
  // (not a single hardcoded one), each with its own real historical
  // timestamp (not "now") via DiagLog.log's optional 4th argument — this is
  // purely informational merging, not a replay of an action, so unlike the
  // other #reconcilePending*() methods there's no decision to re-derive.
  // Called first thing in #init(), before any other DiagLog entry this
  // session, so these historical entries land in correct chronological
  // order relative to both the previous session's own trailing entries and
  // this session's — DiagLog stores entries in insertion order and only
  // reverses for display, it doesn't sort by timestamp. No-op in the
  // browser/PWA (getNativeLog() resolves to [] there).
  async #reconcileNativeLog() {
    const entries = await WidgetBridge.getNativeLog();
    if (!entries.length) return;
    for (const e of entries) {
      // e.tag (e.g. "FMC-FgService") becomes DiagLog.log's `source` — the
      // formatted line already gets a "[FMC-FgService]" prefix from that,
      // so the message text itself no longer needs to repeat it.
      DiagLog.log(e.category || 'SERVICE', e.message || '', null, e.timestamp || null, e.tag || 'native');
    }
    await WidgetBridge.clearNativeLog();
  }

  // Logs a "heartbeat" entry to DiagLog's SERVICE category (source 'WEB')
  // every CFG.diagHeartbeatIntervalMs, for as long as this page's JS keeps
  // executing — the WEB-side counterpart to ParkingForegroundService.kt's
  // own native heartbeat. Neither heartbeat exists to be useful moment-to-
  // moment; they exist so a gap in the diagnostic log is provably a gap
  // (that side genuinely stopped running) rather than just "nothing
  // happened to log" — which was previously indistinguishable from "it's
  // broken" when reviewing a report. Logs once immediately (not just after
  // the first interval) so a heartbeat is visible even in a session that
  // closes again well within the first interval. Runs unconditionally
  // (also in the browser/PWA, where there's no separate native process,
  // but "is the tab's JS still executing" is exactly as meaningful there).
  #startDiagHeartbeat() {
    const beat = () => DiagLog.log('SERVICE', 'heartbeat — app JS alive');
    beat();
    setInterval(beat, CFG.diagHeartbeatIntervalMs);
  }

  #openVehicleModal(vehicle) {
    this.#state.vehicleEditId = vehicle ? vehicle.id : null;
    const title = Utils.el('vehicleModalTitle');
    if (title) title.textContent = vehicle ? 'ערוך רכב' : 'הוסף רכב';
    this.#ui.populateVehicleModal(vehicle);
    this.#ui.openModal('vehicleModal');
  }

  #saveVehicle() {
    const { name, icon, plate, color, bluetoothDevice, dailyStatusEnabled } = this.#ui.getVehicleModalValues();
    if (!name) { this.#ui.showToast('יש להזין שם לרכב', 'warning'); return; }

    if (this.#state.vehicleEditId) {
      VehicleController.update(this.#state.vehicleEditId, name, icon, plate, color, bluetoothDevice, dailyStatusEnabled);
      this.#ui.showToast('✅ הרכב עודכן', 'success');
    } else {
      const v = VehicleController.add(name, icon, plate, color, bluetoothDevice, dailyStatusEnabled);
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
      this.#resetGpsDetection();
      this.#map.removeParkingMarker();
      this.#stopTimer();
      this.#releaseWakeLock();
      this.#cancelParkingNotification();
    }
    // Always sync (not just when isActive) — this changes a vehicle's
    // hasParking/history even when it isn't the currently-selected one, and
    // the widgets mirror every vehicle's own parking state independently
    // (see ParkedVehicles.kt), not just the active vehicle's.
    this.#syncUI();

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
  // Stage 9 of the native background-detection migration (see CLAUDE.md):
  // on native, WidgetDataPlugin.update()/.clear() (Kotlin) now post/cancel
  // this same notification directly — reliably, even once the WebView is
  // reclaimed, unlike this Service-Worker path. Skipped here on native so
  // the two don't both fire; the PWA (no Capacitor) keeps this path
  // unchanged, since it has no native equivalent to delegate to.
  async #showParkingNotification(parking) {
    if (window.Capacitor?.isNativePlatform?.()) return;
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
    if (window.Capacitor?.isNativePlatform?.()) return;
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

  // Every GPS-detection field, reset as a unit. They are only meaningful
  // relative to one another — accumulated vehicle-speed evidence, the sample
  // clock that interval math is measured from, and the previous fix that speed
  // is derived from — so a call site that reset a subset would silently carry
  // the last session's evidence into the next parking, which is exactly what
  // the distance trigger's new gate relies on NOT happening.
  #resetGpsDetection() {
    this.#state.gpsSpeedAccumMs      = 0;
    this.#state.gpsLastSpeedSampleAt = null;
    this.#state.gpsPrevFix           = null;
    this.#state.gpsLastAboveAt       = null;
    this.#state.gpsEndSuggested      = false;
  }

  // Straight-line distance in metres from the active parking spot, or null if
  // there is no active parking. Shared by #checkGpsSpeed (which needs it to
  // decide whether vehicle speed counts as THIS car departing) and
  // #checkGpsDistance, so both read one definition of "how far from the car".
  #distanceFromParking(lat, lng) {
    const p = this.#state.current;
    if (!p) return null;
    return Utils.distance(lat, lng, p.location.lat, p.location.lng);
  }

  // ── DAILY STATUS NOTIFICATION ───────────────────────────────────
  // Global master switch — Android-only (no PWA equivalent, see
  // js/widget-bridge.js). Reading this here (rather than a plain Store.get
  // at each call site) matches #getGpsSettings()'s precedent. Default
  // false: a new notification category should be opt-in, not sprung on
  // existing users after an update.
  #getDailyStatusSettings() {
    return Store.get(CFG.keys.dailyStatus, { enabled: false });
  }

  // Best available speed in m/s, or null if genuinely unknown. Mirrors
  // GpsDecisionEngine.effectiveSpeed() (android/.../core/GpsDecisionEngine.kt).
  // coords.speed is absent or a hard 0 on plenty of real devices — which is
  // exactly why #checkGpsDistance used to have no speed condition at all.
  // Deriving from the distance and time between consecutive fixes removes that
  // dependency, so requiring speed evidence can't disable detection on them.
  #effectiveSpeed(reported, lat, lng) {
    const prev    = this.#state.gpsPrevFix;
    const now     = Date.now();
    const elapsed = prev ? now - prev.at : null;
    // Hold the baseline while the interval is still too short to derive over,
    // so it can actually grow past the minimum — advancing it on every fix
    // would keep every interval at the update period (~1s) and make derivation
    // permanently unavailable.
    if (elapsed === null || elapsed >= CFG.gpsDerivedSpeedMinIntervalMs) {
      this.#state.gpsPrevFix = { lat, lng, at: now };
    }
    if (reported !== null && reported !== undefined && !Number.isNaN(reported) && reported > 0) return reported;
    // Consecutive fixes seconds apart are dominated by GPS jitter — 20m of
    // error over 1s reads as 20 m/s, past the vehicle threshold — so too short
    // an interval is reported as unknown rather than as fabricated evidence.
    if (elapsed === null || elapsed < CFG.gpsDerivedSpeedMinIntervalMs) return null;
    return Utils.distance(lat, lng, prev.lat, prev.lng) / (elapsed / 1000);
  }

  // Accumulates time observed at vehicle speed, then suggests once enough has
  // built up. Mirrors GpsDecisionEngine.checkSpeed(). Accumulated rather than
  // "sustained continuously since": a continuous timer resets at every red
  // light, which would make a 2-minute requirement unreachable in city driving.
  // The threshold is deliberately only just above running, not at a "real
  // driving speed" — see CLAUDE.md "Vehicle-movement detection" (v1.42.0).
  #checkGpsSpeed(speed, lat, lng) {
    if (!this.#state.current || this.#state.gpsEndSuggested) return;
    if (!this.#getGpsSettings().enabled) return;

    const now = Date.now();

    // Expire stale evidence FIRST, before the unknown-speed return below:
    // going stale is a function of elapsed time, not of whether this
    // particular fix happened to carry a usable speed.
    const lastAbove = this.#state.gpsLastAboveAt;
    if (lastAbove !== null && now - lastAbove >= CFG.gpsEvidenceTtlMs) {
      DiagLog.log('GPS', 'vehicle-speed evidence expired (stale) — the distance trigger is disarmed again');
      this.#state.gpsSpeedAccumMs = 0;
      this.#state.gpsLastAboveAt  = null;
    }

    // An unknown speed is not evidence of anything — including not evidence of
    // having been stationary — so it leaves the sample clock alone too.
    // Advancing it here would shrink the interval credited to the next KNOWN
    // reading, which is derived over the time since the last known one.
    if (speed === null || speed === undefined || Number.isNaN(speed)) return;

    const last = this.#state.gpsLastSpeedSampleAt;
    // First sample of the session has no interval behind it; the cap stops a
    // long gap with no fixes (screen off while parked) from being dumped into
    // the accumulator by one fast sample.
    const delta = last === null ? 0 : Math.min(Math.max(now - last, 0), CFG.gpsSpeedSampleCapMs);
    this.#state.gpsLastSpeedSampleAt = now;

    // Deliberately NOT reset below the threshold: a stop at a traffic light
    // does not make the preceding driving un-happen.
    if (speed < CFG.gpsSpeedThreshold) return;

    const before = this.#state.gpsSpeedAccumMs;
    this.#state.gpsSpeedAccumMs = before + delta;
    this.#state.gpsLastAboveAt  = now;
    if (before < CFG.gpsVehicleEvidenceMs && this.#state.gpsSpeedAccumMs >= CFG.gpsVehicleEvidenceMs) {
      const fromCar = this.#distanceFromParking(lat, lng);
      DiagLog.log('GPS', `vehicle-speed evidence reached (${speed.toFixed(1)} m/s, ${Math.round(fromCar ?? -1)}m from the car) — the distance trigger is now armed`);
    }
    if (this.#state.gpsSpeedAccumMs >= CFG.gpsSpeedDuration) this.#suggestGpsEnd();
  }

  // Second, independent signal alongside speed — it fires far sooner than
  // #checkGpsSpeed's accumulated duration on a normal drive, so it stays the
  // trigger that catches most real departures. Mirrors
  // GpsDecisionEngine.checkDistance(), including its vehicle-evidence gate:
  // distance says how FAR, never HOW, so walking 300m from the car used to
  // produce a "your car seems to have moved" suggestion.
  #checkGpsDistance(lat, lng) {
    if (!this.#state.current || this.#state.gpsEndSuggested) return;
    if (!this.#getGpsSettings().enabled) return;
    const fromCar = this.#distanceFromParking(lat, lng);
    if (fromCar === null || fromCar < CFG.gpsDistanceThreshold) return;
    // The evidence this reads was already anchored and expiry-checked by
    // #checkGpsSpeed on this same position update — see its comments.
    if (this.#state.gpsSpeedAccumMs < CFG.gpsVehicleEvidenceMs) return;
    this.#suggestGpsEnd();
  }

  #suggestGpsEnd() {
    if (this.#state.gpsEndSuggested) return; // race guard: speed+distance can both fire on the same position update
    this.#resetGpsDetection();
    this.#state.gpsEndSuggested = true;
    DiagLog.log('GPS', 'showing end-parking suggestion (speed or distance threshold crossed)');
    this.#ui.openModal('gpsEndModal');
    this.#notifyIfBackground(
      '🚗 מזוהה נסיעה',
      'ייתכן שהרכב זז ממקום החניה.',
      { actionTypeId: Notify.CONFIRM_END, extra: { vehicleId: this.#state.activeVehicleId } },
    );
  }

  // Stage 7 of the native background-detection migration (see CLAUDE.md
  // "Native background detection"): replays a GPS end-suggestion
  // ParkingForegroundService recorded while the WebView was unreachable —
  // through the SAME real #suggestGpsEnd() a live threshold-crossing would
  // have used, not a separate reimplementation. GPS suggestions (unlike
  // BT's AutoEnd) never auto-perform an action — they only ever open a
  // confirmation modal, so replay does exactly that, nothing more.
  // Discarded (not replayed) if the vehicle that was active when the
  // suggestion fired is no longer the active vehicle — #suggestGpsEnd()
  // always operates on whichever vehicle is active *now*, so replaying it
  // for the wrong vehicle would show a misleading suggestion. No-op in the
  // browser/PWA (getPendingGpsSuggestion() resolves to null there).
  async #reconcilePendingGpsSuggestion() {
    const pending = await WidgetBridge.getPendingGpsSuggestion();
    if (!pending) return;
    await WidgetBridge.clearPendingGpsSuggestion();
    if (pending.vehicleId !== this.#state.activeVehicleId) {
      DiagLog.log('GPS-PENDING', `pending GPS suggestion discarded — active vehicle changed since (was ${pending.vehicleName})`);
      return;
    }
    // #suggestGpsEnd() has no precondition of its own — its real callers
    // (#checkGpsSpeed/#checkGpsDistance) only ever reach it once they've
    // already confirmed #state.current exists. Replay must enforce that
    // same precondition itself, or it could open gpsEndModal with no
    // active parking to show (e.g. the user already ended it manually).
    if (!this.#state.current) {
      DiagLog.log('GPS-PENDING', `pending GPS suggestion discarded — no active parking for ${pending.vehicleName}`);
      return;
    }
    DiagLog.log('GPS-PENDING', `replaying pending GPS suggestion for ${pending.vehicleName}`);
    this.#suggestGpsEnd();
  }

  // Background-only system notification alongside an in-app toast/modal —
  // if the app is visible the on-screen UI already alerts the user, so a
  // notification would just be redundant noise.
  #notifyIfBackground(title, body, opts) {
    if (document.visibilityState === 'visible') return;
    Notify.show(title, body, opts);
  }

  // Wires the shade buttons on the two confirmation notifications (GPS
  // "the car seems to have moved", Bluetooth "you connected — end the
  // parking?"). Both used to say "open the app to confirm", which is the
  // wrong thing to ask of someone who is driving: it's a one-tap decision
  // and it belongs in the shade.
  //
  // "end" deliberately routes through the SAME performWidgetAction() the
  // widgets use rather than calling #resetParking()/#btEndParking()
  // directly — that method already handles switching to a non-active
  // vehicle, guards against the parking having been ended since, and posts
  // its own result notification. One headless action path, not two.
  // Called once, fire-and-forget, from #init(); no-op in the browser.
  async #initNotificationActions() {
    await Notify.registerActionTypes();
    await Notify.addActionListener(async ({ actionId, extra }) => {
      const vehicleId = extra?.vehicleId ?? null;
      DiagLog.log('NOTIFY', `notification action "${actionId}" (vehicleId=${vehicleId || '(active)'})`);
      if (actionId !== 'end') return; // 'dismiss' and a plain 'tap' just open/close
      // The in-app modals become stale the moment the action is taken from
      // the shade — close whichever one is showing so the user doesn't come
      // back to a question they already answered.
      this.#closeModal('gpsEndModal');
      this.#closeModal('btParkingModal');
      try {
        await this.performWidgetAction('end', vehicleId);
      } catch (e) {
        DiagLog.log('NOTIFY', `notification action "end" threw — ${e?.message || e}`);
      }
    });
  }

  // ── BLUETOOTH ─────────────────────────────────────────────────
  #getBtSettings() {
    return Store.get(CFG.keys.bluetoothSettings, { enabled: true });
  }

  #onBtConnected(label) {
    DiagLog.log('BT', `connected event received, label=${label}`);
    const vehicles = this.#state.vehicles;
    let matched = false;
    for (const v of vehicles) {
      if (v.bluetoothDevice !== label) continue;
      matched = true;
      if (!VehicleController.getCurrent(v.id)) {
        DiagLog.log('BT', `no active parking for this vehicle — ignoring connect event`, { vehicleName: v.name, vehicleIcon: v.icon });
        continue;
      }
      if (v.bluetoothAutoEnd) {
        DiagLog.log('BT', 'auto-ending parking (bluetoothAutoEnd is on)', { vehicleName: v.name, vehicleIcon: v.icon });
        this.#markBtEnd(v.id, label);
        this.#btEndParking(v.id);
        this.#ui.showToast(`🔵 ${v.icon} ${v.name} — חניה הסתיימה אוטומטית`, 'success');
        this.#notifyIfBackground('🔵 חניה הסתיימה אוטומטית', `${v.icon} ${v.name} — זוהה חיבור Bluetooth`);
      } else {
        if (this.#state.btPendingVehicleId) continue; // confirm modal already open; keep processing autoEnd vehicles
        DiagLog.log('BT', 'showing end-parking confirmation modal', { vehicleName: v.name, vehicleIcon: v.icon });
        this.#state.btPendingVehicleId = v.id;
        this.#state.btPendingLabel     = label;
        const title = Utils.el('btParkingTitle');
        const desc  = Utils.el('btParkingDesc');
        if (title) title.textContent = `${v.icon} הגעת לרכב?`;
        if (desc)  desc.textContent  = `זוהה חיבור Bluetooth — יש חניה פעילה של ${v.name}`;
        this.#ui.openModal('btParkingModal');
        this.#notifyIfBackground(
          `${v.icon} הגעת לרכב?`,
          `זוהה חיבור Bluetooth — יש חניה פעילה של ${v.name}`,
          { actionTypeId: Notify.CONFIRM_END, extra: { vehicleId: v.id } },
        );
      }
    }
    if (!matched) DiagLog.log('BT', `no vehicle is linked to device label="${label}" — event ignored`);
  }

  /**
   * @param {object} [opts]
   * @param {number|null} [opts.at] the native event's own timestamp. A plugin
   *   event reaches a paused WebView fine, but is only delivered once its JS
   *   engine resumes — so this handler can run many minutes after the car
   *   actually disconnected.
   * @param {{lat:number,lng:number}|null} [opts.presetLoc] the location native
   *   captured AT the disconnect, supplied by the pending-action replay. When
   *   present it is used instead of a live fix, because by then the user has
   *   walked away and the live fix describes them, not the car.
   */
  async #onBtDisconnected(label, { at = null, presetLoc = null } = {}) {
    const ageMs = at ? Date.now() - at : 0;
    DiagLog.log('BT', `disconnected event received, label=${label}` +
      (ageMs > 5000 ? ` (event is ${Math.round(ageMs / 1000)}s old)` : ''));
    const vehicles = this.#state.vehicles;
    let matched = false;
    for (const v of vehicles) {
      if (v.bluetoothDevice !== label) continue;
      matched = true;
      if (!v.bluetoothAutoStart) {
        DiagLog.log('BT', 'bluetoothAutoStart is off — ignoring disconnect event', { vehicleName: v.name, vehicleIcon: v.icon });
        continue;
      }
      if (VehicleController.getCurrent(v.id)) {
        DiagLog.log('BT', 'vehicle already has an active parking — ignoring disconnect event', { vehicleName: v.name, vehicleIcon: v.icon });
        continue; // already has parking
      }
      // A stale event with no recorded location must NOT auto-start. Saving at
      // the current position would put the parking wherever the user happens to
      // be standing when the app finally resumes — the exact wrong-location bug
      // this check exists for. Skipping leaves it to the native pending record,
      // which carries the location from the moment of the disconnect.
      if (!presetLoc && ageMs > CFG.btEventMaxAgeMs) {
        DiagLog.log('BT',
          `refusing to auto-start from a ${Math.round(ageMs / 60000)}-minute-old disconnect with no recorded ` +
          'location — the car is not where you are now; leaving it to the native pending record',
          { vehicleName: v.name, vehicleIcon: v.icon });
        // Never silent: skipping is the right call, but the user still expected
        // a parking to exist. A missing one they can save by hand beats one
        // saved at the wrong place, and they need to know which happened.
        this.#ui.showToast(`${v.icon} ${v.name} — ניתוק ישן זוהה באיחור, לא נשמרה חניה אוטומטית`, 'warning');
        continue;
      }
      DiagLog.log('BT', 'auto-starting parking (bluetoothAutoStart is on)' +
        (presetLoc ? ' at the location recorded when Bluetooth disconnected' : ''),
        { vehicleName: v.name, vehicleIcon: v.icon });

      // Switch to this vehicle if needed silently, then save parking.
      // Roll back the switch if GPS fails so the user's active parking remains visible.
      const needsSwitch = v.id !== this.#state.activeVehicleId;
      const prevId      = this.#state.activeVehicleId;
      if (needsSwitch) this.#switchVehicle(v.id, { silent: true });
      await this.#saveNewParking(presetLoc);
      if (!this.#state.current) {
        DiagLog.log('BT', 'auto-start aborted — GPS location unavailable', { vehicleName: v.name, vehicleIcon: v.icon });
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
    if (!matched) DiagLog.log('BT', `no vehicle is linked to device label="${label}" — event ignored`);
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

  // Stage 6 of the native background-detection migration (see CLAUDE.md
  // "Native background detection"): replays what BluetoothClassicPlugin
  // recorded for real while the WebView was unreachable — through the SAME
  // real #onBtConnected/#onBtDisconnected handlers a live event would have
  // used, not a separate reimplementation, so this is exactly the behavior
  // that would have happened had the app been alive when the event fired.
  // Each entry's own idempotency guards (no active parking / already has
  // parking) make replaying an already-consistent state a safe no-op — the
  // recorded `action`/`lat`/`lng` fields are informational only (logged),
  // never used to decide what to do; #onBt(Dis)connected re-derives that
  // fresh from current settings/state. Processed sequentially (not
  // concurrently) to match how real BT events only ever arrive one at a
  // time. No-op in the browser/PWA (getPendingActions() resolves to []).
  async #reconcilePendingBtActions() {
    // Runs from #init() AND from visibilitychange, so an overlap is possible
    // where it never was before; without this guard the two could replay the
    // same entry twice, since the store is only cleared at the end.
    if (this.#reconcilingBt) return;
    this.#reconcilingBt = true;
    try {
      await this.#reconcilePendingBtActionsInner();
    } finally {
      this.#reconcilingBt = false;
    }
  }

  async #reconcilePendingBtActionsInner() {
    const actions = (await this.#bluetooth.getPendingActions?.()) ?? [];
    if (!actions.length) return;
    for (const a of actions) {
      DiagLog.log('BT-PENDING', `replaying ${a.action} (${a.direction}, label=${a.label || '?'})`, { vehicleName: a.vehicleName });
      try {
        if (a.direction === 'connected') {
          this.#onBtConnected(a.label);
        } else if (a.direction === 'disconnected') {
          // The recorded lat/lng WAS purely informational, on the reasoning that
          // the replay should re-derive everything from current state rather
          // than trust a stale native decision. That is right about settings
          // and wrong about location: a location is not a decision, it is an
          // observation with a timestamp, and this one was taken at the
          // disconnect — which is where the car is. The live fix at replay time
          // describes the user, who has since walked away.
          const presetLoc = (typeof a.lat === 'number' && typeof a.lng === 'number')
            ? { lat: a.lat, lng: a.lng, accuracy: null }
            : null;
          if (!presetLoc) {
            DiagLog.log('BT-PENDING', 'no location was captured at the disconnect — the replay will have to use a live fix',
              { vehicleName: a.vehicleName });
          }
          await this.#onBtDisconnected(a.label, { at: a.timestamp ?? null, presetLoc });
        }
      } catch (e) {
        DiagLog.log('BT-PENDING', `replay threw — ${e?.message || e}`, { vehicleName: a.vehicleName });
      }
    }
    await this.#bluetooth.clearPendingActions?.().catch(() => {});
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
        DiagLog.log('BT', `master switch turned ${enabled ? 'ON' : 'OFF'}`);
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
        // Mirror the new setting to native immediately. The native side reads
        // these (BtDecisionEngine via the vehicles_json mirror, and the
        // "bluetooth" foreground-service keep-alive reason via
        // KEY_BT_ENABLED) to decide what to do while the app is CLOSED — so
        // leaving the mirror stale until some unrelated parking-state sync
        // happens means a setting the user just changed isn't the one acting
        // on their next real BT event.
        this.#syncUI();
        this.#refreshBtModal();
      },
      onToggleVehicle: (vehicleId, updates) => {
        const v = VehicleController.getById(vehicleId);
        DiagLog.log('BT', `per-vehicle settings changed: ${JSON.stringify(updates)}`, { vehicleName: v?.name, vehicleIcon: v?.icon });
        VehicleController.updateBluetooth(vehicleId, updates);
        this.#state.vehicles = VehicleController.getAll();
        this.#syncUI();
        this.#refreshBtModal();
      },
      onSetAll: updates => {
        DiagLog.log('BT', `settings changed for all vehicles: ${JSON.stringify(updates)}`);
        VehicleController.updateAllBluetooth(updates);
        this.#state.vehicles = VehicleController.getAll();
        this.#syncUI();
        this.#refreshBtModal();
      },
    };
  }

  async #refreshBtModal() {
    const isNative = NativeBluetoothController.isSupported();
    const [btStatus, notifGranted, batteryStatus] = isNative
      ? await Promise.all([
          this.#bluetooth.permissionStatus?.(),
          Notify.checkPermission(),
          this.#bluetooth.batteryOptimizationStatus?.(),
        ])
      : [null, true, null];
    const batteryRestricted = batteryStatus != null && !batteryStatus.ignoring;
    const needsSettings = !!btStatus?.permanentlyDenied || notifGranted === false || batteryRestricted;
    this.#ui.renderBtSettingsModal(
      this.#getBtSettings(),
      this.#state.vehicles,
      this.#btSettingsCbs(),
      needsSettings ? {
        batteryRestricted,
        onOpenSettings: () => this.#bluetooth.openAppSettings?.(),
        onRequestBattery: () => this.#bluetooth.requestIgnoreBatteryOptimizations?.(),
      } : null
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
    if (id === 'walkAwayModal') WidgetBridge.clearPendingParkingSuggestion().catch(() => {});
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
  // Shared by #exportData() and #exportDiagLog() — native uses Filesystem
  // (private Cache dir, no permissions needed) + the Share sheet; browser
  // falls back to a plain download link.
  async #writeAndShareFile(filename, content, mimeType, shareTitle) {
    const Filesystem = window.Capacitor?.Plugins?.Filesystem;
    const Share      = window.Capacitor?.Plugins?.Share;
    if (window.Capacitor?.isNativePlatform?.() && Filesystem && Share) {
      const { uri } = await Filesystem.writeFile({
        path: filename, data: content, directory: 'CACHE', encoding: 'utf8',
      });
      await Share.share({ title: shareTitle, dialogTitle: shareTitle, url: uri });
    } else {
      const blob = new Blob([content], { type: mimeType });
      const url  = URL.createObjectURL(blob);
      const a    = document.createElement('a');
      a.href = url; a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    }
  }

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

    try {
      await this.#writeAndShareFile(filename, json, 'application/json', 'גיבוי FindMyCar');
    } catch (e) {
      console.warn('Export failed:', e);
      this.#ui.showToast('שגיאה בייצוא הנתונים', 'error');
      return;
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

  // ── DIAGNOSTIC LOG ───────────────────────────────────────────
  // ── BACKGROUND-DETECTION SETUP GUIDE ──────────────────────────
  // Android-only in practice (OemSetup.buildSteps() returns [] in the
  // browser), which is why the Settings entry point stays hidden on the PWA
  // rather than opening an empty modal.
  async #openOemSetupModal() {
    await this.#refreshOemSetupView();
    this.#ui.openModal('oemSetupModal');
  }

  async #refreshOemSetupView() {
    const list = Utils.el('oemSetupList');
    const intro = Utils.el('oemSetupIntro');
    if (!list) return;

    const steps = await OemSetup.buildSteps();
    if (!steps.length) {
      list.innerHTML = '<p class="oem-setup-empty">אין הגדרות מכשיר לבדוק בגרסה הזו (זמין באפליקציית האנדרואיד בלבד).</p>';
      if (intro) intro.textContent = '';
      return;
    }

    const todo = steps.filter(s => s.state !== 'ok').length;
    if (intro) {
      intro.textContent = todo
        ? `${todo} מתוך ${steps.length} הגדרות עדיין דורשות טיפול. לחץ על כל שלב כדי לפתוח את המסך המתאים.`
        : 'כל ההגדרות שניתן לבדוק תקינות. שים לב שאת שלבי היצרן אי אפשר לאמת — הסימון מבוסס על מה שסימנת בעצמך.';
    }

    list.innerHTML = steps.map(s => {
      const badge = {
        ok:      '<span class="oem-step-badge oem-step-ok">תקין</span>',
        todo:    '<span class="oem-step-badge oem-step-todo">דורש טיפול</span>',
        unknown: '<span class="oem-step-badge oem-step-unknown">לא ניתן לבדוק</span>',
      }[s.state];
      // Manual steps carry their own "I did this" checkbox precisely because
      // no API can confirm them — see js/oem-setup.js.
      const confirm = s.kind === 'manual'
        ? `<label class="oem-step-confirm">
             <input type="checkbox" data-oem-confirm="${Utils.escHtml(s.id)}" ${s.state === 'ok' ? 'checked' : ''}>
             <span>סימנתי שביצעתי את זה</span>
           </label>`
        : '';
      const action = s.action
        ? `<button class="settings-backup-btn oem-step-btn" data-oem-action="${Utils.escHtml(s.action)}">
             <span>${Utils.escHtml(s.actionLabel)}</span>
           </button>`
        : '';
      return `<div class="oem-step oem-step-${s.state}">
        <div class="oem-step-head">
          <span class="oem-step-icon">${s.icon}</span>
          <span class="oem-step-title">${Utils.escHtml(s.title)}</span>
          ${badge}
        </div>
        <p class="oem-step-desc">${Utils.escHtml(s.desc)}</p>
        ${action}
        ${confirm}
      </div>`;
    }).join('');

    list.querySelectorAll('[data-oem-action]').forEach(btn => {
      btn.addEventListener('click', async () => {
        const result = await OemSetup.runAction(btn.dataset.oemAction);
        // A vendor screen that doesn't exist on this ROM silently falls back
        // to the generic app-settings page — say so, or the user is left
        // wondering why the screen they were promised never appeared.
        if (result === 'fallback') {
          this.#ui.showToast('מסך היצרן לא זמין במכשיר הזה — נפתחו הגדרות האפליקציה במקום', 'warning');
        } else if (result === 'failed' || result === null) {
          this.#ui.showToast('לא ניתן היה לפתוח את המסך — פתח אותו ידנית בהגדרות המכשיר', 'error');
        }
      });
    });

    list.querySelectorAll('[data-oem-confirm]').forEach(cb => {
      cb.addEventListener('change', () => {
        OemSetup.setManual({ [cb.dataset.oemConfirm]: cb.checked });
        this.#refreshOemSetupView();
      });
    });
  }

  #openDiagLogModal() {
    const select = Utils.el('diagLogVehicleFilter');
    if (select) {
      const current = select.value;
      select.innerHTML = '<option value="">כל הרכבים</option>';
      for (const v of this.#state.vehicles) {
        const opt = document.createElement('option');
        opt.value = v.name;
        opt.textContent = `${v.icon} ${v.name}`;
        select.appendChild(opt);
      }
      select.value = current;
    }
    this.#refreshDiagLogView();
    this.#ui.openModal('diagLogModal');
  }

  #filteredDiagLogEntries() {
    const vehicleName = Utils.el('diagLogVehicleFilter')?.value || '';
    const category     = Utils.el('diagLogCategoryFilter')?.value || '';
    return DiagLog.getAll()
      .filter(e => !vehicleName || e.vehicleName === vehicleName)
      .filter(e => !category || e.category === category)
      .reverse(); // newest first
  }

  #refreshDiagLogView() {
    const content = Utils.el('diagLogContent');
    if (!content) return;
    const entries = this.#filteredDiagLogEntries();
    content.textContent = entries.length ? DiagLog.formatText(entries) : 'אין רשומות תואמות לסינון שנבחר.';
  }

  async #copyDiagLog() {
    const text = DiagLog.formatText(this.#filteredDiagLogEntries());
    if (!text) { this.#ui.showToast('אין מה להעתיק', 'info'); return; }
    try {
      await navigator.clipboard.writeText(text);
      this.#ui.showToast('📋 היומן הועתק', 'success');
    } catch {
      this.#ui.showToast('שגיאה בהעתקה', 'error');
    }
  }

  async #exportDiagLog() {
    const text = DiagLog.formatText(this.#filteredDiagLogEntries());
    if (!text) { this.#ui.showToast('אין מה לייצא', 'info'); return; }
    const filename = `findmycar-diag-log-${new Date().toISOString().slice(0, 10)}.txt`;
    try {
      await this.#writeAndShareFile(filename, text, 'text/plain', 'יומן אבחון FindMyCar');
      this.#ui.showToast('📤 היומן יוצא', 'success');
    } catch (e) {
      console.warn('Log export failed:', e);
      this.#ui.showToast('שגיאה בייצוא היומן', 'error');
    }
  }

  #clearDiagLog() {
    if (!confirm('למחוק את כל רשומות היומן?')) return;
    DiagLog.clear();
    this.#refreshDiagLogView();
    this.#ui.showToast('🗑️ היומן נוקה', 'success');
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
