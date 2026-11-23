// App updates and app sharing (v1.57.0).
//
// Updates: the APK is published as a GitHub release by CI; this asks the public
// releases API what is newest and compares it with CFG.version. On Android
// (the APK) the native AppUpdate plugin downloads it and opens the system
// installer; in the browser the "update" is clearing the app-shell caches and
// reloading, since the PWA is served from GitHub Pages and updates by itself.
//
// Pure helpers (version compare, release parsing, share links) are exported
// separately so they can be unit-tested without a DOM or a network.
import { CFG } from './config.js';

const U = CFG.update;

/** "1.57.0" -> [1, 57, 0]; anything unparseable -> null. A leading "v" is allowed. */
export function parseVersion(v) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)/.exec(String(v ?? '').trim());
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

/** <0 if a is older than b, 0 if equal, >0 if newer. null when either is unparseable. */
export function compareVersions(a, b) {
  const pa = parseVersion(a);
  const pb = parseVersion(b);
  if (!pa || !pb) return null;
  for (let i = 0; i < 3; i++) if (pa[i] !== pb[i]) return pa[i] - pb[i];
  return 0;
}

/** Release notes arrive as markdown; the update dialog shows plain text. */
export function plainNotes(body, maxChars = 700) {
  const text = String(body ?? '')
    .replace(/\r/g, '')
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/\*\*|__|`/g, '')
    .replace(/^\s*[-*]\s+/gm, '• ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
  return text.length > maxChars ? text.slice(0, maxChars).trimEnd() + '…' : text;
}

/**
 * Reduces a GitHub "release" JSON to what the app needs, or null when it is
 * not a usable release (draft, prerelease, no version, no APK attached).
 * The APK asset is looked up by its stable name first, then by extension.
 */
export function parseRelease(json) {
  if (!json || typeof json !== 'object' || json.draft || json.prerelease) return null;
  const version = parseVersion(json.tag_name) ? String(json.tag_name).replace(/^v/, '') : null;
  if (!version) return null;
  const assets = Array.isArray(json.assets) ? json.assets : [];
  const apk = assets.find(a => a?.name === U.apkAsset) ?? assets.find(a => /\.apk$/i.test(a?.name ?? ''));
  const digest = typeof apk?.digest === 'string' && apk.digest.startsWith('sha256:') ? apk.digest.slice(7) : null;
  return {
    version,
    notes: plainNotes(json.body),
    publishedAt: json.published_at ?? null,
    pageUrl: json.html_url ?? `https://github.com/${U.repo}/releases`,
    apkUrl: apk?.browser_download_url ?? null,
    apkSize: Number.isFinite(apk?.size) ? apk.size : null,
    sha256: digest && /^[0-9a-f]{64}$/i.test(digest) ? digest.toLowerCase() : null,
  };
}

export function apkDownloadLink() {
  return `https://github.com/${U.repo}/releases/latest/download/${U.apkAsset}`;
}

export function shareText() {
  return [
    '🚗 FindMyCar — שומרת את מקום החניה שלך ועוזרת למצוא את הרכב בחזרה.',
    '',
    `📱 אנדרואיד (התקנה): ${apkDownloadLink()}`,
    `🌐 בלי התקנה, מהדפדפן: ${U.pwaUrl}`,
  ].join('\n');
}

export const shareSubject = 'FindMyCar — אפליקציה לשמירת מקום החניה';

export function whatsappShareUrl() {
  return `https://wa.me/?text=${encodeURIComponent(shareText())}`;
}

export function mailShareUrl() {
  return `mailto:?subject=${encodeURIComponent(shareSubject)}&body=${encodeURIComponent(shareText())}`;
}

export class Updater {
  static get #plugin() {
    return window.Capacitor?.Plugins?.AppUpdate ?? null;
  }

  /** True inside the Android APK, where in-app download + install exists. */
  static isNative() {
    return !!this.#plugin;
  }

  /**
   * Asks GitHub for the newest release.
   * @returns {Promise<{release: object|null, newer: boolean}>} release is null
   *   when there is none yet; throws on a network/API failure so the caller can
   *   say "could not check" rather than "you are up to date".
   */
  static async fetchLatest() {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), U.timeoutMs);
    try {
      const res = await fetch(`https://api.github.com/repos/${U.repo}/releases/latest`, {
        headers: { Accept: 'application/vnd.github+json' },
        cache: 'no-store',
        signal: ctrl.signal,
      });
      if (res.status === 404) return { release: null, newer: false }; // no release published yet
      if (!res.ok) throw new Error(`GitHub ${res.status}`);
      const release = parseRelease(await res.json());
      const cmp = release ? compareVersions(release.version, CFG.version) : null;
      return { release, newer: cmp !== null && cmp > 0 };
    } finally {
      clearTimeout(timer);
    }
  }

  static lastCheck() {
    try { return JSON.parse(localStorage.getItem(U.lastCheckKey) || 'null') || {}; } catch { return {}; }
  }

  static recordCheck(patch) {
    try { localStorage.setItem(U.lastCheckKey, JSON.stringify({ ...this.lastCheck(), at: Date.now(), ...patch })); } catch { /* storage unavailable */ }
  }

  static checkDue() {
    return Date.now() - (this.lastCheck().at || 0) >= U.checkIntervalMs;
  }

  // ── native (APK) ──────────────────────────────────────────────
  static async nativeStatus() {
    try { return await this.#plugin?.status(); } catch { return null; }
  }

  static async openInstallSettings() {
    try { await this.#plugin?.openInstallSettings(); } catch { /* nothing to open */ }
  }

  static async cancelDownload() {
    try { await this.#plugin?.cancelDownload(); } catch { /* best effort */ }
  }

  /**
   * Downloads the release APK and opens the system installer.
   * @param onProgress ({downloaded, total}) called while downloading
   * @returns {Promise<{status: string, message?: string}>} status is one of
   *   installing | needsPermission | cancelled | busy | failed
   */
  static async downloadAndInstall(release, onProgress) {
    const plugin = this.#plugin;
    if (!plugin) return { status: 'failed', message: 'עדכון מהאפליקציה זמין רק באפליקציית האנדרואיד' };
    if (!release?.apkUrl) return { status: 'failed', message: 'לגרסה הזו אין קובץ התקנה' };
    let handle = null;
    try {
      if (onProgress) handle = await plugin.addListener('updateProgress', onProgress);
      return await plugin.downloadAndInstall({ url: release.apkUrl, sha256: release.sha256 ?? undefined });
    } catch (e) {
      return { status: 'failed', message: e?.message || 'ההורדה נכשלה' };
    } finally {
      try { await handle?.remove(); } catch { /* listener already gone */ }
    }
  }

  // ── browser (PWA) ─────────────────────────────────────────────
  /** Drops the app-shell caches (keeps map tiles) and reloads, so the new version is fetched. */
  static async reloadPwa() {
    try { const reg = await navigator.serviceWorker?.getRegistration(); await reg?.update(); } catch { /* offline */ }
    try {
      const keys = await caches.keys();
      await Promise.all(keys.filter(k => k.startsWith('findmycar-') && !k.includes('tiles')).map(k => caches.delete(k)));
    } catch { /* no Cache API */ }
    location.reload();
  }
}
