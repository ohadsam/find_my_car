import { CFG } from './config.js';
import { Utils } from './utils.js';
import { normalizeAddress } from './geocoder.js';

export class UIController {
  #onHistoryItemClick;
  #onHistoryItemNav;
  #onPhotoClick;
  #onVoiceClick;

  constructor({ onHistoryItemClick, onHistoryItemNav, onPhotoClick, onVoiceClick }) {
    this.#onHistoryItemClick = onHistoryItemClick;
    this.#onHistoryItemNav   = onHistoryItemNav;
    this.#onPhotoClick       = onPhotoClick;
    this.#onVoiceClick       = onVoiceClick;
  }

  // ── FULL REFRESH ──────────────────────────────────────────────
  updateAll(state) {
    this.updateStatusChip(state);
    this.updateHomeView(state);
    this.updateHistoryView(state);
    this.updateHistoryBadge(state);
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
    } else {
      chip.classList.remove('has-parking');
      dot.classList.remove('active');
      lbl.textContent = 'אין חניה';
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
    this.updateDescription(p);
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

  // ── DESCRIPTION ───────────────────────────────────────────────
  updateDescription(p) {
    const row  = Utils.el('parkingDescRow');
    const span = Utils.el('parkingDescDisplay');
    if (!row || !span) return;
    if (p.description) {
      row.style.display = '';
      span.textContent  = p.description;
    } else {
      row.style.display = 'none';
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
    container.innerHTML = '';
    if (!p) return;

    if (p.photo) {
      const chip = document.createElement('div');
      chip.className = 'attachment-chip has-photo';
      chip.innerHTML = `<img class="photo-thumb" src="${p.photo}" alt="תמונה"><span>📷 תמונה</span>`;
      chip.addEventListener('click', () => this.#onPhotoClick?.(p.photo));
      container.appendChild(chip);
    }
    if (p.voice) {
      const chip = document.createElement('div');
      chip.className = 'attachment-chip has-voice';
      chip.innerHTML = `<span>🎙️</span><span>הקלטה קולית</span>`;
      chip.addEventListener('click', () => this.#onVoiceClick?.(p.voice));
      container.appendChild(chip);
    }
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
      item.photo       ? '<span class="media-badge photo">📷 תמונה</span>'   : '',
      item.voice       ? '<span class="media-badge voice">🎙️ הקלטה</span>'   : '',
      item.description ? '<span class="media-badge text">✏️ תיאור</span>'     : ''
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
    document.querySelectorAll('.nav-btn').forEach(b => b.classList.remove('active'));

    Utils.el(viewId)?.classList.add('active');
    document.querySelector(`.nav-btn[data-view="${viewId}"]`)?.classList.add('active');

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
