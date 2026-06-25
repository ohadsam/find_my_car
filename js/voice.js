import { Utils } from './utils.js';

export class VoiceController {
  #mediaRecorder = null;
  #audioChunks   = [];
  #isRecording   = false;
  #recTimerId    = null;
  #recSeconds    = 0;
  #capturedVoice = null;
  #blobUrl       = null;

  open() {
    this.#reset();
  }

  close() {
    if (this.#isRecording) this.stopRecording();
    if (this.#blobUrl) {
      URL.revokeObjectURL(this.#blobUrl);
      this.#blobUrl = null;
    }
  }

  getCaptured() {
    return { voice: this.#capturedVoice, seconds: this.#recSeconds };
  }

  async toggle() {
    if (this.#isRecording) {
      this.stopRecording();
    } else {
      await this.#startRecording();
    }
  }

  stopRecording() {
    if (!this.#isRecording) return;
    clearInterval(this.#recTimerId);
    this.#recTimerId = null;
    this.#isRecording = false;

    if (this.#mediaRecorder && this.#mediaRecorder.state !== 'inactive') {
      this.#mediaRecorder.stop();
    }

    Utils.el('voiceVisualizer')?.classList.remove('recording');
    Utils.el('voiceVisualizer')?.classList.add('done');
    Utils.el('voiceStatusText').textContent  = '✅ הקלטה הסתיימה';
    Utils.el('voiceRecordControls').style.display = 'none';
    Utils.el('voiceSaveControls').style.display   = '';
  }

  rerecord() {
    if (this.#isRecording) this.stopRecording();
    this.#reset();
  }

  async handleFile(e) {
    const file = e.target.files?.[0];
    if (!file) return;
    this.#capturedVoice = await Utils.blobToBase64(file);
    if (this.#blobUrl) URL.revokeObjectURL(this.#blobUrl);
    this.#blobUrl = URL.createObjectURL(file);
    Utils.el('voiceAudioPlayer').src              = this.#blobUrl;
    Utils.el('voicePlaybackContainer').style.display = '';
    Utils.el('voiceRecordControls').style.display    = 'none';
    Utils.el('voiceSaveControls').style.display      = '';
    Utils.el('voiceVisualizer')?.classList.add('done');
    Utils.el('voiceStatusText').textContent = '✅ קובץ נטען';
  }

  playVoice(src) {
    if (!src) return Promise.resolve();
    return new Audio(src).play();
  }

  async #startRecording() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/ogg', 'audio/mp4']
        .find(t => MediaRecorder.isTypeSupported(t)) || '';

      this.#mediaRecorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
      this.#audioChunks = [];

      this.#mediaRecorder.ondataavailable = e => {
        if (e.data.size > 0) this.#audioChunks.push(e.data);
      };

      this.#mediaRecorder.onstop = () => {
        stream.getTracks().forEach(t => t.stop());
        const blob = new Blob(this.#audioChunks, { type: this.#mediaRecorder.mimeType || 'audio/webm' });
        Utils.blobToBase64(blob).then(b64 => {
          this.#capturedVoice = b64;
          if (this.#blobUrl) URL.revokeObjectURL(this.#blobUrl);
          this.#blobUrl = URL.createObjectURL(blob);
          Utils.el('voiceAudioPlayer').src              = this.#blobUrl;
          Utils.el('voicePlaybackContainer').style.display = '';
        });
      };

      this.#mediaRecorder.start(100);
      this.#isRecording = true;
      this.#recSeconds  = 0;

      Utils.el('voiceVisualizer')?.classList.add('recording');
      Utils.el('voiceStatusText').textContent  = '● מקליט...';
      Utils.el('voiceTimerDisplay').style.display = '';

      const btn = Utils.el('recordToggleBtn');
      btn?.classList.add('recording');
      Utils.el('recordToggleIcon').textContent = '⏹️';
      Utils.el('recordToggleText').textContent = 'עצור הקלטה';

      this.#recTimerId = setInterval(() => {
        this.#recSeconds++;
        const m = Math.floor(this.#recSeconds / 60);
        const s = this.#recSeconds % 60;
        const el = Utils.el('voiceTimerDisplay');
        if (el) el.textContent = `${m}:${String(s).padStart(2, '0')}`;
        if (this.#recSeconds >= 300) this.stopRecording();
      }, 1000);

    } catch {
      Utils.el('voicePermissionError').style.display = '';
      Utils.el('voiceRecordControls').style.display  = 'none';
    }
  }

  #reset() {
    this.#capturedVoice = null;
    this.#audioChunks   = [];
    this.#recSeconds    = 0;

    Utils.el('voiceVisualizer')?.classList.remove('recording', 'done');
    Utils.el('voiceStatusText').textContent       = 'לחץ על המיקרופון להתחלת הקלטה';
    Utils.el('voiceTimerDisplay').style.display    = 'none';
    Utils.el('voiceTimerDisplay').textContent      = '0:00';
    Utils.el('voicePlaybackContainer').style.display = 'none';
    Utils.el('voicePermissionError').style.display  = 'none';
    Utils.el('voiceRecordControls').style.display   = '';
    Utils.el('voiceSaveControls').style.display     = 'none';

    const btn = Utils.el('recordToggleBtn');
    if (btn) {
      btn.classList.remove('recording');
      Utils.el('recordToggleIcon').textContent = '⏺️';
      Utils.el('recordToggleText').textContent = 'התחל הקלטה';
    }
  }
}
