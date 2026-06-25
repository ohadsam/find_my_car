import { CFG } from './config.js';

export const Utils = {
  uuid() {
    return Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  },

  formatTime(date) {
    return new Date(date).toLocaleTimeString('he-IL', { hour: '2-digit', minute: '2-digit' });
  },

  formatDate(date) {
    const d = new Date(date);
    const today = new Date();
    const yesterday = new Date(today);
    yesterday.setDate(yesterday.getDate() - 1);
    if (d.toDateString() === today.toDateString()) return 'היום';
    if (d.toDateString() === yesterday.toDateString()) return 'אתמול';
    return d.toLocaleDateString('he-IL', { day: 'numeric', month: 'long', year: 'numeric' });
  },

  formatElapsed(date) {
    const secs = Math.floor((Date.now() - new Date(date)) / 1000);
    if (secs < 60)  return `לפני ${secs} שניות`;
    const mins = Math.floor(secs / 60);
    if (mins < 60)  return `לפני ${mins} דקות`;
    const hrs = Math.floor(mins / 60);
    if (hrs < 24)   return `לפני ${hrs} שעות`;
    return `לפני ${Math.floor(hrs / 24)} ימים`;
  },

  formatDuration(date) {
    const secs = Math.floor((Date.now() - new Date(date)) / 1000);
    const h = Math.floor(secs / 3600);
    const m = Math.floor((secs % 3600) / 60);
    const s = secs % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  },

  // Haversine distance in meters
  distance(lat1, lng1, lat2, lng2) {
    const R = 6371000;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat / 2) ** 2 +
      Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
      Math.sin(dLng / 2) ** 2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
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

  escHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  },

  el(id) { return document.getElementById(id); }
};
