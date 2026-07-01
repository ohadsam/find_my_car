import { CFG } from './config.js';
import { Utils } from './utils.js';
import { normalizeAddress } from './geocoder.js';

export class UIController {
  #onHistoryItemClick;
  #onHistoryItemNav;
  #onPhotoClick;
  #onVehicleSelect;
  #onDeletePhoto;
  #onDeleteVoice;
  #onDeleteText;
  #onEditText;

  constructor({ onHistoryItemClick, onHistoryItemNav, onPhotoClick, onVehicleSelect,
                onDeletePhoto, onDeleteVoice, onDeleteText, onEditText }) {
    this.#onHistoryItemClick = onHistoryItemClick;
    this.#onHistoryItemNav   = onHistoryItemNav;
    this.#onPhotoClick       = onPhotoClick;
    this.#onVehicleSelect    = onVehicleSelect;
    this.#onDeletePhoto      = onDeletePhoto;
    this.#onDeleteVoice      = onDeleteVoice;
    this.#onDeleteText       = onDeleteText;
    this.#onEditText         = onEditText;
  }

  // ── BLUETOOTH HELPERS ─────────────────────────────────────────
  setBtDeviceValue(label) {
    const btValue   = Utils.el('vehicleBtDeviceValue');
    const btDisplay = Utils.el('vehicleBtDeviceDisplay');
    const unlinkBtn = Utils.el('vehicleBtUnlinkBtn');
    if (btValue)   btValue.value     = label || '';
    if (btDisplay) btDisplay.textContent = label || 'לא מקושר';
    if (unlinkBtn) unlinkBtn.style.display = label ? '' : 'none';
    // Hide device list after selection
    const list = Utils.el('vehicleBtDeviceList');
    if (list) list.style.display = 'none';
  }

  showBtDeviceList(devices, onSelect) {
    const list = Utils.el('vehicleBtDeviceList');
    if (!list) return;
    list.innerHTML = '';
    const labels = [...new Set(devices.map(d => d.label).filter(Boolean))];
    if (labels.length === 0) {
      const empty = document.createElement('p');
      empty.className = 'bt-device-empty';
      empty.textContent = 'לא נמצאו מכשירי שמע פעילים. חבר מכשיר Bluetooth ונסה שוב.';
      list.appendChild(empty);
    } else {
      labels.forEach(label => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'bt-device-item';
        btn.textContent = label;
        btn.addEventListener('click', () => onSelect(label));
        list.appendChild(btn);
      });
    }
    list.style.display = '';
  }

  showBtPermissionRequest(onRequest) {
    const list = Utils.el('vehicleBtDeviceList');
    if (!list) return;
    list.innerHTML = '';
    const msg = document.createElement('p');
    msg.className = 'bt-perm-msg';
    msg.textContent = 'נדרש אישור גישה למיקרופון כדי לזהות מכשירי Bluetooth';
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'bt-perm-btn modal-btn primary';
    btn.textContent = '🎤 אשר גישה';
    btn.addEventListener('click', () => onRequest());
    list.appendChild(msg);
    list.appendChild(btn);
    list.style.display = '';
  }

  renderBtSettingsModal(btSettings, vehicles, { onToggleEnabled, onToggleVehicle, onSetAll }) {
    const content = Utils.el('btSettingsContent');
    if (!content) return;
    content.innerHTML = '';

    // Master switch
    const masterRow = this.#makeBtRow(
      'הפעל זיהוי Bluetooth',
      'זיהוי אוטומטי של התקן הרכב — חיבור וניתוק',
      btSettings.enabled,
      checked => onToggleEnabled(checked)
    );
    content.appendChild(masterRow);

    if (!btSettings.enabled) return;

    const linked = vehicles.filter(v => v.bluetoothDevice);
    if (linked.length === 0) {
      const msg = document.createElement('p');
      msg.className = 'bt-no-vehicles-msg';
      msg.textContent = 'לא קושרו מכשירי Bluetooth לרכבים. ערוך רכב כדי לקשר.';
      content.appendChild(msg);
      return;
    }

    // Global "set all" section
    const globalSec = document.createElement('div');
    globalSec.className = 'bt-section';
    const globalTitle = document.createElement('div');
    globalTitle.className = 'bt-section-title';
    globalTitle.textContent = 'הגדרות לכלל הרכבים';
    globalSec.appendChild(globalTitle);

    const allAutoEnd = linked.every(v => v.bluetoothAutoEnd);
    globalSec.appendChild(this.#makeBtRow(
      'סיום חניה בחיבור',
      'כאשר מזוהה חיבור, מציע סיום חניה',
      allAutoEnd,
      checked => onSetAll({ bluetoothAutoEnd: checked })
    ));

    const allAutoStart = linked.every(v => v.bluetoothAutoStart);
    globalSec.appendChild(this.#makeBtRow(
      'התחלת חניה בניתוק',
      'כאשר מזוהה ניתוק, שומר חניה חדשה לפי GPS',
      allAutoStart,
      checked => onSetAll({ bluetoothAutoStart: checked })
    ));

    const allPopup = linked.every(v => v.bluetoothStartPopup);
    globalSec.appendChild(this.#makeBtRow(
      'חלון הוספת מדיה',
      'לאחר שמירה אוטומטית — שאל האם להוסיף תמונה / הקלטה / תיאור',
      allPopup,
      checked => onSetAll({ bluetoothStartPopup: checked })
    ));

    content.appendChild(globalSec);

    // Per-vehicle section
    const vehicleSec = document.createElement('div');
    vehicleSec.className = 'bt-section';
    const vehicleTitle = document.createElement('div');
    vehicleTitle.className = 'bt-section-title';
    vehicleTitle.textContent = 'הגדרות לפי רכב';
    vehicleSec.appendChild(vehicleTitle);

    linked.forEach(v => {
      const card = document.createElement('div');
      card.className = 'bt-vehicle-card';

      const header = document.createElement('div');
      header.className = 'bt-vehicle-header';
      const icon = document.createElement('span');
      icon.className = 'bt-vehicle-icon';
      icon.textContent = v.icon;
      const info = document.createElement('div');
      info.className = 'bt-vehicle-info';
      const name = document.createElement('div');
      name.className = 'bt-vehicle-name';
      name.textContent = v.name;
      const device = document.createElement('div');
      device.className = 'bt-vehicle-device';
      device.textContent = `🔵 ${v.bluetoothDevice}`;
      info.appendChild(name);
      info.appendChild(device);
      header.appendChild(icon);
      header.appendChild(info);
      card.appendChild(header);

      card.appendChild(this.#makeBtRow(
        'סיום חניה בחיבור', '',
        v.bluetoothAutoEnd,
        checked => onToggleVehicle(v.id, { bluetoothAutoEnd: checked })
      ));
      card.appendChild(this.#makeBtRow(
        'התחלת חניה בניתוק', '',
        v.bluetoothAutoStart,
        checked => onToggleVehicle(v.id, { bluetoothAutoStart: checked })
      ));
      card.appendChild(this.#makeBtRow(
        'חלון הוספת מדיה', '',
        v.bluetoothStartPopup,
        checked => onToggleVehicle(v.id, { bluetoothStartPopup: checked })
      ));

      vehicleSec.appendChild(card);
    });

    content.appendChild(vehicleSec);
  }

  #makeBtRow(label, desc, checked, onChange) {
    const row = document.createElement('div');
    row.className = 'bt-toggle-row';
    const text = document.createElement('div');
    text.className = 'bt-toggle-text';
    const labelEl = document.createElement('span');
    labelEl.className = 'bt-toggle-label';
    labelEl.textContent = label;
    text.appendChild(labelEl);
    if (desc) {
      const descEl = document.createElement('span');
      descEl.className = 'bt-toggle-desc';
      descEl.textContent = desc;
      text.appendChild(descEl);
    }
    const toggleLabel = document.createElement('label');
    toggleLabel.className = 'bt-toggle';
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.checked = checked;
    input.addEventListener('change', () => onChange(input.checked));
    const slider = document.createElement('span');
    slider.className = 'bt-toggle-slider';
    toggleLabel.appendChild(input);
    toggleLabel.appendChild(slider);
    row.appendChild(text);
    row.appendChild(toggleLabel);
    return row;
  }

  updateBtSettingsBtn(linkedCount) {
    const badge = Utils.el('btLinkedBadge');
    if (!badge) return;
    badge.textContent    = linkedCount > 0 ? String(linkedCount) : '';
    badge.style.display  = linkedCount > 0 ? '' : 'none';
  }

  // ── FULL REFRESH ──────────────────────────────────────────────
  updateAll(state) {
    this.updateStatusChip(state);
    this.renderVehicleSelector(state);
    this.updateHomeView(state);
    this.updateHistoryView(state);
    this.updateHistoryBadge(state);
  }

  // ── VEHICLE SELECTOR ──────────────────────────────────────────
  renderVehicleSelector(state) {
    const row = Utils.el('vehicleSelectorRow');
    if (!row) return;
    const vehicles = state.vehicles || [];
    if (vehicles.length < 2) { row.style.display = 'none'; return; }

    row.style.display = '';
    row.innerHTML = '';
    vehicles.forEach(v => {
      const chip = document.createElement('button');
      chip.className = 'vehicle-chip' + (v.id === state.activeVehicleId ? ' active' : '');
      chip.setAttribute('aria-pressed', v.id === state.activeVehicleId ? 'true' : 'false');
      const iconSpan = document.createElement('span');
      iconSpan.textContent = v.icon;
      const nameSpan = document.createElement('span');
      nameSpan.textContent = v.name;
      chip.appendChild(iconSpan);
      chip.appendChild(nameSpan);
      chip.addEventListener('click', () => this.#onVehicleSelect?.(v.id));
      row.appendChild(chip);
    });
  }

  // ── SETTINGS VIEW ─────────────────────────────────────────────
  renderSettingsView(state, { onEdit, onDelete, onAdd, onClearParking, hasParking }) {
    const list = Utils.el('vehiclesList');
    if (!list) return;
    list.innerHTML = '';
    const vehicles = state.vehicles || [];
    vehicles.forEach(v => {
      const item = document.createElement('div');
      item.className = 'vehicle-list-item';

      const info = document.createElement('div');
      info.className = 'vehicle-list-info';

      const iconEl = document.createElement('span');
      iconEl.className = 'vehicle-list-icon';
      iconEl.textContent = v.icon;

      const textBlock = document.createElement('div');
      textBlock.className = 'vehicle-list-text';

      const hasPk = hasParking?.(v.id);

      const nameRow = document.createElement('div');
      nameRow.className = 'vehicle-list-name-row';
      const nameEl = document.createElement('span');
      nameEl.className = 'vehicle-list-name';
      nameEl.textContent = v.name;
      nameRow.appendChild(nameEl);
      if (v.id === state.activeVehicleId) {
        const badge = document.createElement('span');
        badge.className = 'vehicle-active-badge';
        badge.textContent = 'פעיל';
        nameRow.appendChild(badge);
      }
      if (hasPk) {
        const pkBadge = document.createElement('span');
        pkBadge.className = 'vehicle-parking-indicator';
        pkBadge.textContent = '🅿️';
        pkBadge.title = 'יש חניה פעילה';
        nameRow.appendChild(pkBadge);
      }
      textBlock.appendChild(nameRow);

      const metaParts = [v.plate, v.color].filter(Boolean);
      if (metaParts.length) {
        const metaEl = document.createElement('span');
        metaEl.className = 'vehicle-list-meta';
        metaEl.textContent = metaParts.join(' · ');
        textBlock.appendChild(metaEl);
      }

      info.appendChild(iconEl);
      info.appendChild(textBlock);

      const actions = document.createElement('div');
      actions.className = 'vehicle-list-actions';

      if (hasPk) {
        const clearBtn = document.createElement('button');
        clearBtn.className = 'vehicle-action-btn vehicle-clear-btn';
        clearBtn.textContent = '🧹';
        clearBtn.title = 'העבר חניה להיסטוריה';
        clearBtn.setAttribute('aria-label', `נקה חניה נוכחית של ${v.name}`);
        clearBtn.addEventListener('click', () => onClearParking?.(v.id));
        actions.appendChild(clearBtn);
      }

      const editBtn = document.createElement('button');
      editBtn.className = 'vehicle-action-btn edit-btn';
      editBtn.textContent = '✏️';
      editBtn.setAttribute('aria-label', `ערוך ${v.name}`);
      editBtn.addEventListener('click', () => onEdit?.(v));

      const delBtn = document.createElement('button');
      delBtn.className = 'vehicle-action-btn delete-btn';
      delBtn.textContent = '🗑️';
      delBtn.setAttribute('aria-label', `מחק ${v.name}`);
      delBtn.disabled = vehicles.length <= 1;
      delBtn.addEventListener('click', () => onDelete?.(v.id, v.name));

      actions.appendChild(editBtn);
      actions.appendChild(delBtn);
      item.appendChild(info);
      item.appendChild(actions);
      list.appendChild(item);
    });

    const addBtn = Utils.el('addVehicleBtn');
    if (addBtn) addBtn.disabled = vehicles.length >= CFG.maxVehicles;
  }

  // ── WHAT'S NEW ────────────────────────────────────────────────
  showWhatsNew(entry) {
    if (!entry) return;
    const { version, date, items } = entry;
    const title = Utils.el('whatsNewTitle');
    if (title) title.textContent = `🎉 מה חדש בגרסה ${version}`;

    const body = Utils.el('whatsNewContent');
    if (!body) return;
    body.innerHTML = '';

    const dateEl = document.createElement('p');
    dateEl.className = 'whats-new-date';
    dateEl.textContent = date;
    body.appendChild(dateEl);

    const ul = document.createElement('ul');
    ul.className = 'whats-new-list';
    items.forEach(item => {
      const li = document.createElement('li');
      li.textContent = item;
      ul.appendChild(li);
    });
    body.appendChild(ul);

    this.openModal('whatsNewModal');
  }

  // ── VEHICLE MODAL ─────────────────────────────────────────────
  populateVehicleModal(vehicle) {
    const nameInput = Utils.el('vehicleNameInput');
    if (nameInput) nameInput.value = vehicle ? vehicle.name : '';

    const plateInput = Utils.el('vehiclePlateInput');
    if (plateInput) plateInput.value = vehicle?.plate || '';

    const colorInput = Utils.el('vehicleColorInput');
    if (colorInput) colorInput.value = vehicle?.color || '';

    const grid = Utils.el('vehicleIconGrid');
    if (!grid) return;
    grid.innerHTML = '';
    const selectedIcon = vehicle ? vehicle.icon : CFG.vehicleIcons[0];
    CFG.vehicleIcons.forEach(icon => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'icon-pick-btn' + (icon === selectedIcon ? ' selected' : '');
      btn.textContent = icon;
      btn.setAttribute('aria-pressed', icon === selectedIcon ? 'true' : 'false');
      btn.addEventListener('click', () => {
        grid.querySelectorAll('.icon-pick-btn').forEach(b => {
          b.classList.remove('selected');
          b.setAttribute('aria-pressed', 'false');
        });
        btn.classList.add('selected');
        btn.setAttribute('aria-pressed', 'true');
      });
      grid.appendChild(btn);
    });

    // BT section
    this.setBtDeviceValue(vehicle?.bluetoothDevice || null);
    const btList = Utils.el('vehicleBtDeviceList');
    if (btList) { btList.innerHTML = ''; btList.style.display = 'none'; }
  }

  getVehicleModalValues() {
    const name            = (Utils.el('vehicleNameInput')?.value  || '').trim();
    const plate           = (Utils.el('vehiclePlateInput')?.value || '').trim() || null;
    const color           = (Utils.el('vehicleColorInput')?.value || '').trim() || null;
    const selected        = Utils.el('vehicleIconGrid')?.querySelector('.icon-pick-btn.selected');
    const icon            = selected ? selected.textContent : CFG.vehicleIcons[0];
    const bluetoothDevice = Utils.el('vehicleBtDeviceValue')?.value || null;
    return { name, icon, plate, color, bluetoothDevice };
  }

  // ── WHATSAPP MODAL ────────────────────────────────────────────
  populateWhatsAppModal(parking, vehicleName) {
    const list = Utils.el('waOptionsList');
    if (!list) return;
    list.innerHTML = '';

    const addrText = normalizeAddress(parking.address) ||
      `${parking.location.lat.toFixed(5)}, ${parking.location.lng.toFixed(5)}`;
    const mapUrl = `https://maps.google.com/maps?q=${parking.location.lat},${parking.location.lng}`;

    const options = [
      { id: 'wa_vehicle',  label: `${vehicleName || 'הרכב'} 🚗`,      checked: true, always: true },
      { id: 'wa_address',  label: `כתובת: ${addrText}`,               checked: true, always: true },
      { id: 'wa_maplink',  label: 'קישור למפה 🗺️',                   checked: true, always: true },
      { id: 'wa_time',     label: 'שעת חניה ⏰',                      checked: true, always: true },
      { id: 'wa_desc',     label: `תיאור: ${parking.description}`,     checked: true, always: false, show: !!parking.description },
      { id: 'wa_photo',    label: 'תמונה 📷',                         checked: true, always: false, show: !!parking.photo },
    ];

    options.forEach(opt => {
      if (!opt.always && !opt.show) return;
      const row = document.createElement('label');
      row.className = 'wa-option-row';
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.id = opt.id;
      cb.checked = opt.checked;
      const span = document.createElement('span');
      span.textContent = opt.label;
      row.appendChild(cb);
      row.appendChild(span);
      list.appendChild(row);
    });

    const note = Utils.el('waNote');
    if (note) {
      if (parking.photo) {
        note.textContent = 'שיתוף תמונה ייפתח את תפריט השיתוף של המכשיר';
      } else {
        note.textContent = '';
      }
    }
  }

  getWhatsAppOptions() {
    return {
      includeVehicle:  Utils.el('wa_vehicle')?.checked  ?? true,
      includeAddress:  Utils.el('wa_address')?.checked  ?? true,
      includeMapLink:  Utils.el('wa_maplink')?.checked  ?? true,
      includeTime:     Utils.el('wa_time')?.checked     ?? true,
      includeDesc:     Utils.el('wa_desc')?.checked     ?? false,
      includePhoto:    Utils.el('wa_photo')?.checked    ?? false,
    };
  }

  // ── STATUS CHIP ───────────────────────────────────────────────
  updateStatusChip(state) {
    const chip = Utils.el('statusChip');
    const dot  = Utils.el('statusDot');
    const lbl  = Utils.el('statusLabel');
    if (!chip || !dot || !lbl) return;
    if (state.current) {
      chip.classList.add('has-parking');
      dot.classList.add('active');
      lbl.textContent = 'חניה פעילה';
      chip.setAttribute('aria-label', 'סיים חניה');
      chip.setAttribute('aria-disabled', 'false');
      chip.tabIndex = 0;
    } else {
      chip.classList.remove('has-parking');
      dot.classList.remove('active');
      lbl.textContent = 'אין חניה';
      chip.setAttribute('aria-label', 'אין חניה פעילה');
      chip.setAttribute('aria-disabled', 'true');
      chip.tabIndex = -1;
    }
  }

  // ── HOME VIEW ─────────────────────────────────────────────────
  updateHomeView(state) {
    const noState  = Utils.el('noParkingState');
    const hasState = Utils.el('hasParkingState');
    const fab      = Utils.el('fabSaveParking');
    if (!noState || !hasState) return;

    if (state.current) {
      noState.style.display  = 'none';
      hasState.style.display = 'block';
      if (fab) fab.style.display = 'none';
      this._populateParkingCard(state);
    } else {
      noState.style.display  = '';
      hasState.style.display = 'none';
      if (fab) fab.style.display = 'none';
    }
  }

  _populateParkingCard(state) {
    const p = state.current;
    if (!p) return;

    const dateEl  = Utils.el('parkingDateDisplay');
    const timeEl  = Utils.el('parkingTimeDisplay');
    const agoEl   = Utils.el('parkingAgoDisplay');
    const timerEl = Utils.el('parkingTimerDisplay');
    if (dateEl)  dateEl.textContent  = Utils.formatDate(p.timestamp);
    if (timeEl)  timeEl.textContent  = Utils.formatTime(p.timestamp);
    if (agoEl)   agoEl.textContent   = Utils.formatElapsed(p.timestamp);
    if (timerEl) timerEl.textContent = Utils.formatDuration(p.timestamp);

    this.updateAddress(p);
    this.renderAttachments(p);
    this.updateMediaTiles(p);
    if (state.userPos) this.updateDistance(state);
  }

  // ── ADDRESS ───────────────────────────────────────────────────
  updateAddress(p) {
    const addrEl = Utils.el('parkingAddressDisplay');
    const cityEl = Utils.el('parkingCityDisplay');
    if (!addrEl) return;

    if (!p.address) {
      addrEl.textContent = 'מחשב כתובת...';
      if (cityEl) cityEl.style.display = 'none';
      return;
    }

    if (typeof p.address === 'object') {
      const mainParts = [p.address.street, p.address.houseNumber].filter(Boolean);
      addrEl.textContent = mainParts.length > 0 ? mainParts.join(' ') : (p.address.display || '');

      if (cityEl) {
        const subParts = [p.address.city, p.address.neighborhood].filter(Boolean);
        if (subParts.length > 0) {
          cityEl.textContent  = subParts.join(' • ');
          cityEl.style.display = '';
        } else {
          cityEl.style.display = 'none';
        }
      }
    } else {
      addrEl.textContent = p.address;
      if (cityEl) cityEl.style.display = 'none';
    }
  }

  // ── DISTANCE ──────────────────────────────────────────────────
  updateDistance(state) {
    if (!state.current || !state.userPos) return;
    const { lat: plat, lng: plng } = state.current.location;
    const { lat: ulat, lng: ulng } = state.userPos;
    const dist = Utils.distance(ulat, ulng, plat, plng);
    const el = Utils.el('distanceDisplay');
    if (el) el.textContent = `${Utils.formatDistance(dist)} מהמיקום הנוכחי`;
    const row = Utils.el('distanceRow');
    if (row) row.style.display = '';
  }

  // ── ATTACHMENTS ───────────────────────────────────────────────
  renderAttachments(p) {
    const container = Utils.el('parkingAttachments');
    if (!container) return;
    const existingAudio = container.querySelector('audio');
    if (existingAudio) { existingAudio.pause(); existingAudio.src = ''; }
    container.innerHTML = '';
    if (!p) return;

    if (p.photo) {
      const panel = document.createElement('div');
      panel.className = 'media-panel media-panel-photo';
      const header = document.createElement('div');
      header.className = 'media-panel-header';
      const label = document.createElement('span');
      label.className = 'media-panel-label';
      label.textContent = '📷 תמונה';
      const deleteBtn = document.createElement('button');
      deleteBtn.className = 'media-panel-delete-btn';
      deleteBtn.textContent = '🗑️';
      deleteBtn.setAttribute('aria-label', 'מחק תמונה');
      deleteBtn.addEventListener('click', () => this.#onDeletePhoto?.());
      header.appendChild(label);
      header.appendChild(deleteBtn);
      const img = document.createElement('img');
      img.src = p.photo;
      img.className = 'media-panel-photo-img';
      img.alt = 'תמונת חניה';
      img.addEventListener('click', () => this.#onPhotoClick?.(p.photo));
      panel.appendChild(header);
      panel.appendChild(img);
      container.appendChild(panel);
    }

    if (p.voice) {
      const panel = document.createElement('div');
      panel.className = 'media-panel media-panel-voice';
      const header = document.createElement('div');
      header.className = 'media-panel-header';
      const label = document.createElement('span');
      label.className = 'media-panel-label';
      label.textContent = '🎙️ הקלטה קולית';
      header.appendChild(label);
      if (p.voiceDuration > 0) {
        const dur = document.createElement('span');
        dur.className = 'media-panel-duration';
        const m = Math.floor(p.voiceDuration / 60);
        const s = p.voiceDuration % 60;
        dur.textContent = `${m}:${String(s).padStart(2, '0')}`;
        header.appendChild(dur);
      }
      const deleteBtn = document.createElement('button');
      deleteBtn.className = 'media-panel-delete-btn';
      deleteBtn.textContent = '🗑️';
      deleteBtn.setAttribute('aria-label', 'מחק הקלטה');
      deleteBtn.addEventListener('click', () => this.#onDeleteVoice?.());
      header.appendChild(deleteBtn);
      const audio = document.createElement('audio');
      audio.src = p.voice;
      audio.setAttribute('controls', '');
      audio.className = 'media-panel-audio';
      panel.appendChild(header);
      panel.appendChild(audio);
      container.appendChild(panel);
    }

    if (p.description) {
      const panel = document.createElement('div');
      panel.className = 'media-panel media-panel-text';
      const header = document.createElement('div');
      header.className = 'media-panel-header';
      const label = document.createElement('span');
      label.className = 'media-panel-label';
      label.textContent = '✏️ תיאור';
      const actions = document.createElement('div');
      actions.className = 'media-panel-actions';
      const editBtn = document.createElement('button');
      editBtn.className = 'media-panel-edit-btn';
      editBtn.setAttribute('aria-label', 'ערוך תיאור');
      editBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><path d="M11 4H4a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>';
      editBtn.addEventListener('click', () => this.#onEditText?.());
      const deleteBtn = document.createElement('button');
      deleteBtn.className = 'media-panel-delete-btn';
      deleteBtn.setAttribute('aria-label', 'מחק תיאור');
      deleteBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14H6L5 6"/><path d="M10 11v6"/><path d="M14 11v6"/><path d="M9 6V4h6v2"/></svg>';
      deleteBtn.addEventListener('click', () => this.#onDeleteText?.());
      actions.appendChild(editBtn);
      actions.appendChild(deleteBtn);
      header.appendChild(label);
      header.appendChild(actions);
      const textEl = document.createElement('p');
      textEl.className = 'media-panel-text-content';
      textEl.textContent = p.description;
      panel.appendChild(header);
      panel.appendChild(textEl);
      container.appendChild(panel);
    }

    container.style.display = (p.photo || p.voice || p.description) ? '' : 'none';
  }

  updateMediaTiles(p) {
    if (!p) return;
    Utils.el('addPhotoBtn')?.classList.toggle('has-data', !!p.photo);
    Utils.el('addVoiceBtn')?.classList.toggle('has-data', !!p.voice);
    Utils.el('addTextBtn')?.classList.toggle('has-data',  !!p.description);
  }

  // ── HISTORY ───────────────────────────────────────────────────
  updateHistoryBadge(state) {
    const badge = Utils.el('historyCountBadge');
    if (!badge) return;
    const count = state.history.length;
    badge.textContent  = count;
    badge.style.display = count > 0 ? '' : 'none';
  }

  updateHistoryView(state) {
    const list    = Utils.el('historyList');
    const noState = Utils.el('noHistoryState');
    if (!list) return;
    list.innerHTML = '';

    if (state.history.length === 0) {
      if (noState) noState.style.display = '';
      return;
    }
    if (noState) noState.style.display = 'none';

    state.history.forEach((item, idx) => list.appendChild(this._renderHistoryItem(item, idx)));
  }

  _renderHistoryItem(item, idx) {
    const div = document.createElement('div');
    div.className = 'history-item';
    div.style.animationDelay = `${idx * 40}ms`;
    div.setAttribute('role', 'listitem');

    const thumb = item.photo
      ? `<img src="${item.photo}" alt="תמונה" loading="lazy">`
      : `<span>🅿️</span>`;

    const badges = [
      item.photo                              ? '<span class="media-badge photo">📷 תמונה</span>'   : '',
      item.voice                              ? '<span class="media-badge voice">🎙️ הקלטה</span>'  : '',
      item.description                        ? '<span class="media-badge text">✏️ תיאור</span>'    : '',
      (item.btStartDevice || item.btEndDevice) ? '<span class="media-badge bt">🔵 BT</span>'        : '',
    ].filter(Boolean);

    const addrDisplay = normalizeAddress(item.address) || 'מיקום ידוע';

    div.innerHTML = `
      <div class="history-item-thumbnail">${thumb}</div>
      <div class="history-item-info">
        <div class="history-item-time">${Utils.formatDate(item.timestamp)} • ${Utils.formatTime(item.timestamp)}</div>
        <div class="history-item-address"></div>
        ${item.description ? '<div class="history-item-desc"></div>' : ''}
        <div class="history-item-badges">${badges.join('')}</div>
      </div>
      <button class="history-item-nav" aria-label="נווט לחניה זו">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polygon points="3 11 22 2 13 21 11 13 3 11"/>
        </svg>
      </button>`;

    div.querySelector('.history-item-address').textContent = addrDisplay;
    if (item.description) {
      div.querySelector('.history-item-desc').textContent = item.description;
    }

    div.addEventListener('click', e => {
      if (!e.target.closest('.history-item-nav')) this.#onHistoryItemClick?.(item);
    });
    div.querySelector('.history-item-nav').addEventListener('click', e => {
      e.stopPropagation();
      this.#onHistoryItemNav?.(item);
    });

    return div;
  }

  // ── DETAIL MODAL ──────────────────────────────────────────────
  buildDetailModal(item, { onPhotoClick }) {
    Utils.el('detailModalTitle').textContent =
      `${Utils.formatDate(item.timestamp)} • ${Utils.formatTime(item.timestamp)}`;

    const body = Utils.el('detailModalContent');
    body.innerHTML = '';

    if (item.photo) {
      const img = document.createElement('img');
      img.src = item.photo;
      img.className = 'detail-photo';
      img.alt = 'תמונת חניה';
      img.addEventListener('click', () => onPhotoClick?.(item.photo));
      body.appendChild(img);
    }

    const addrDisplay = normalizeAddress(item.address) ||
      `${item.location.lat.toFixed(5)}, ${item.location.lng.toFixed(5)}`;

    body.appendChild(this._makeSection('📍 מיקום', addrDisplay));
    body.appendChild(this._makeSection('⏰ זמן חניה',
      `${Utils.formatDate(item.timestamp)}, ${Utils.formatTime(item.timestamp)}`));

    if (item.btStartDevice) {
      body.appendChild(this._makeSection('🔵 הופעל אוטומטית', item.btStartDevice));
    }
    if (item.btEndDevice) {
      const btEndVal = item.btEndTime
        ? `${item.btEndDevice} • ${Utils.formatDate(item.btEndTime)}, ${Utils.formatTime(item.btEndTime)}`
        : item.btEndDevice;
      body.appendChild(this._makeSection('🔵 הסתיים ב-Bluetooth', btEndVal));
    }

    if (item.description) {
      body.appendChild(this._makeSection('✏️ תיאור', item.description));
    }

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

    const mapSec = document.createElement('div');
    mapSec.className = 'detail-section';
    mapSec.innerHTML = `<div class="detail-section-label">🗺️ מיקום במפה</div>
      <div class="detail-map-mini" id="detailMapContainer">טוען מפה...</div>`;
    body.appendChild(mapSec);
  }

  _makeSection(label, value) {
    const sec = document.createElement('div');
    sec.className = 'detail-section';
    const labelEl = document.createElement('div');
    labelEl.className = 'detail-section-label';
    labelEl.textContent = label;
    const valueEl = document.createElement('div');
    valueEl.className = 'detail-section-value';
    valueEl.textContent = value;
    sec.appendChild(labelEl);
    sec.appendChild(valueEl);
    return sec;
  }

  // ── MODALS ────────────────────────────────────────────────────
  openModal(id) {
    const modal = Utils.el(id);
    if (!modal) return;
    modal.style.display = 'flex';
    document.body.style.overflow = 'hidden';
    setTimeout(() => {
      modal.querySelector('button, input, textarea, [tabindex]')?.focus();
    }, 50);
  }

  closeModal(id) {
    const modal = Utils.el(id);
    if (!modal) return;
    modal.style.display = 'none';
    document.body.style.overflow = '';
  }

  // ── VIEWS ─────────────────────────────────────────────────────
  showView(viewId, mapCtrl) {
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    document.querySelectorAll('.bottom-nav .nav-btn').forEach(b => b.classList.remove('active'));

    Utils.el(viewId)?.classList.add('active');
    document.querySelector(`.bottom-nav .nav-btn[data-view="${viewId}"]`)?.classList.add('active');

    if (viewId === 'homeView' && mapCtrl) {
      setTimeout(() => mapCtrl.invalidateSize(), 50);
    }
  }

  // ── THEME ─────────────────────────────────────────────────────
  applyTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme);
    const meta = Utils.el('themeColorMeta');
    if (meta) meta.content = theme === 'dark' ? '#060B18' : '#F0F4FF';
  }

  // ── TOAST ─────────────────────────────────────────────────────
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
