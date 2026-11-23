import { describe, it, expect } from 'vitest';
import {
  parseVersion, compareVersions, plainNotes, parseRelease,
  apkDownloadLink, shareText, whatsappShareUrl, mailShareUrl,
} from '../../js/updater.js';

describe('versions', () => {
  it('parses plain and v-prefixed versions, rejects junk', () => {
    expect(parseVersion('1.57.0')).toEqual([1, 57, 0]);
    expect(parseVersion('v2.0.10')).toEqual([2, 0, 10]);
    expect(parseVersion('latest')).toBeNull();
    expect(parseVersion(undefined)).toBeNull();
  });

  it('compares numerically, not as strings', () => {
    expect(compareVersions('1.10.0', '1.9.0')).toBeGreaterThan(0);
    expect(compareVersions('1.56.0', '1.57.0')).toBeLessThan(0);
    expect(compareVersions('1.57.0', 'v1.57.0')).toBe(0);
    expect(compareVersions('x', '1.0.0')).toBeNull();
  });
});

describe('parseRelease', () => {
  const base = {
    tag_name: 'v1.57.0', body: '## מה חדש\n- **דבר** ראשון\n- שני', html_url: 'https://github.com/o/r/releases/tag/v1.57.0',
    published_at: '2026-11-23T10:00:00Z',
    assets: [
      { name: 'other.txt', browser_download_url: 'https://x/other.txt', size: 1 },
      { name: 'FindMyCar.apk', browser_download_url: 'https://github.com/o/r/releases/download/v1.57.0/FindMyCar.apk', size: 3600000, digest: 'sha256:' + 'ab'.repeat(32) },
    ],
  };

  it('extracts version, apk url, size and checksum', () => {
    const r = parseRelease(base);
    expect(r.version).toBe('1.57.0');
    expect(r.apkUrl).toContain('FindMyCar.apk');
    expect(r.apkSize).toBe(3600000);
    expect(r.sha256).toBe('ab'.repeat(32));
  });

  it('turns markdown notes into plain text', () => {
    expect(parseRelease(base).notes).toBe('מה חדש\n• דבר ראשון\n• שני');
  });

  it('ignores drafts, prereleases, bad tags; tolerates a missing apk or digest', () => {
    expect(parseRelease({ ...base, draft: true })).toBeNull();
    expect(parseRelease({ ...base, prerelease: true })).toBeNull();
    expect(parseRelease({ ...base, tag_name: 'nightly' })).toBeNull();
    expect(parseRelease(null)).toBeNull();
    const noApk = parseRelease({ ...base, assets: [] });
    expect(noApk.apkUrl).toBeNull();
    const noDigest = parseRelease({ ...base, assets: [{ name: 'FindMyCar.apk', browser_download_url: 'u' }] });
    expect(noDigest.sha256).toBeNull();
  });

  it('falls back to any .apk when the stable name is absent, and rejects a malformed digest', () => {
    const r = parseRelease({ ...base, assets: [{ name: 'app-debug.apk', browser_download_url: 'u2', digest: 'sha256:zz' }] });
    expect(r.apkUrl).toBe('u2');
    expect(r.sha256).toBeNull();
  });

  it('caps very long notes', () => {
    expect(plainNotes('x'.repeat(2000), 100).length).toBe(101);
  });
});

describe('sharing', () => {
  it('shows both the APK link and the browser link', () => {
    const t = shareText();
    expect(t).toContain(apkDownloadLink());
    expect(t).toContain('https://ohadsam.github.io/find_my_car/');
  });

  it('builds a WhatsApp link and a mailto link that round-trip the text', () => {
    const wa = whatsappShareUrl();
    expect(wa.startsWith('https://wa.me/?text=')).toBe(true);
    expect(decodeURIComponent(wa.split('text=')[1])).toBe(shareText());
    const mail = mailShareUrl();
    expect(mail.startsWith('mailto:?subject=')).toBe(true);
    expect(decodeURIComponent(mail.split('&body=')[1])).toBe(shareText());
  });
});
