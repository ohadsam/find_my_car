import { Utils } from './utils.js';
import { normalizeAddress } from './geocoder.js';

export class ReturnModal {
  #onMove;
  #onDismiss;

  constructor({ onMove, onDismiss }) {
    this.#onMove    = onMove;
    this.#onDismiss = onDismiss;
  }

  bindEvents() {
    Utils.el('returnMoveBtn')?.addEventListener('click', () => {
      this.hide();
      this.#onMove?.();
    });
    Utils.el('returnContinueBtn')?.addEventListener('click', () => {
      this.hide();
      this.#onDismiss?.();
    });
  }

  show(parking, vehicleName) {
    const modal = Utils.el('returnModal');
    if (!modal) return;

    const subtitleEl = Utils.el('returnModalSubtitle');
    if (subtitleEl) subtitleEl.textContent = vehicleName ? `${vehicleName} ממתין לך` : 'הרכב שלך ממתין לך';

    const elapsedEl = Utils.el('returnElapsed');
    if (elapsedEl) elapsedEl.textContent = Utils.formatElapsed(parking.timestamp);

    const addrEl = Utils.el('returnAddress');
    if (addrEl) {
      const addr = normalizeAddress(parking.address);
      addrEl.textContent = addr || `${parking.location.lat.toFixed(5)}, ${parking.location.lng.toFixed(5)}`;
    }

    const photoEl = Utils.el('returnPhotoThumb');
    if (photoEl) {
      if (parking.photo) {
        photoEl.src = parking.photo;
        photoEl.style.display = '';
      } else {
        photoEl.style.display = 'none';
      }
    }

    const descRow = Utils.el('returnDescRow');
    const descEl  = Utils.el('returnDescription');
    if (descRow && descEl) {
      if (parking.description) {
        descEl.textContent    = parking.description;
        descRow.style.display = '';
      } else {
        descRow.style.display = 'none';
      }
    }

    const voiceEl = Utils.el('returnVoiceIndicator');
    if (voiceEl) voiceEl.style.display = parking.voice ? '' : 'none';

    modal.style.display = 'flex';
    document.body.style.overflow = 'hidden';
  }

  hide() {
    const modal = Utils.el('returnModal');
    if (!modal) return;
    modal.style.display = 'none';
    document.body.style.overflow = '';
  }
}
