import { Utils } from './utils.js';

export class CameraController {
  #stream        = null;
  #facingMode    = 'environment';
  #capturedPhoto = null;

  async open() {
    this.#capturedPhoto = null;
    this.#showLive();
    await this.#startCamera();
  }

  close() {
    this.#stopCamera();
  }

  getPhoto() { return this.#capturedPhoto; }

  async switchCamera() {
    this.#facingMode = this.#facingMode === 'environment' ? 'user' : 'environment';
    await this.#startCamera();
  }

  async capture() {
    const vid    = Utils.el('cameraVideo');
    const canvas = Utils.el('cameraCanvas');
    if (!vid || !canvas) return;

    canvas.width  = vid.videoWidth  || 640;
    canvas.height = vid.videoHeight || 480;
    canvas.getContext('2d').drawImage(vid, 0, 0);

    const raw = canvas.toDataURL('image/jpeg', 1.0);
    this.#capturedPhoto = await Utils.compressImage(raw);

    Utils.el('cameraContainer').style.display      = 'none';
    Utils.el('photoPreviewContainer').style.display = '';
    Utils.el('photoPreviewImg').src                 = this.#capturedPhoto;
    Utils.el('cameraControls').style.display        = 'none';
    Utils.el('photoPreviewControls').style.display  = '';
    this.#stopCamera();
  }

  retake() {
    this.#capturedPhoto = null;
    this.#showLive();
    this.#startCamera();
  }

  async handleFile(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    await new Promise(resolve => {
      const reader = new FileReader();
      reader.onload = async ev => {
        this.#capturedPhoto = await Utils.compressImage(ev.target.result);
        Utils.el('cameraContainer').style.display      = 'none';
        Utils.el('photoPreviewContainer').style.display = '';
        Utils.el('photoPreviewImg').src                 = this.#capturedPhoto;
        Utils.el('cameraControls').style.display        = 'none';
        Utils.el('photoPreviewControls').style.display  = '';
        Utils.el('cameraPermissionError').style.display = 'none';
        resolve();
      };
      reader.readAsDataURL(file);
    });
  }

  viewPhoto(src) {
    const overlay = document.createElement('div');
    overlay.style.cssText =
      'position:fixed;inset:0;z-index:900;background:rgba(0,0,0,0.92);' +
      'display:flex;align-items:center;justify-content:center;padding:20px;cursor:pointer';
    const img = document.createElement('img');
    img.src = src;
    img.style.cssText = 'max-width:100%;max-height:100%;border-radius:12px;object-fit:contain';
    overlay.appendChild(img);
    overlay.addEventListener('click', () => document.body.removeChild(overlay));
    document.body.appendChild(overlay);
  }

  #showLive() {
    Utils.el('cameraContainer').style.display      = '';
    Utils.el('photoPreviewContainer').style.display = 'none';
    Utils.el('cameraControls').style.display        = '';
    Utils.el('photoPreviewControls').style.display  = 'none';
    Utils.el('cameraPermissionError').style.display = 'none';
  }

  async #startCamera() {
    this.#stopCamera();
    try {
      this.#stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: this.#facingMode }, width: { ideal: 1280 }, height: { ideal: 720 } },
        audio: false
      });
      const vid = Utils.el('cameraVideo');
      vid.srcObject = this.#stream;
      vid.play().catch(() => {});
    } catch {
      Utils.el('cameraContainer').style.display      = 'none';
      Utils.el('cameraControls').style.display        = 'none';
      Utils.el('cameraPermissionError').style.display = '';
    }
  }

  #stopCamera() {
    this.#stream?.getTracks().forEach(t => t.stop());
    this.#stream = null;
    const vid = Utils.el('cameraVideo');
    if (vid) vid.srcObject = null;
  }
}
