// Guided setup for the device-level settings that decide whether background
// Bluetooth/GPS detection is allowed to run at all. No-op in the browser/PWA —
// every method is safe to call unconditionally from js/app.js.
//
// The honest split (mirrored from OemSetupPlugin.kt, which is the authority on
// what Android actually lets an app know):
//
//   - `auto` steps    — real API, really checked, re-checked every refresh.
//   - `manual` steps  — no API exists to read them (MIUI Autostart, the OEM's
//                       own battery policy, locking the app in Recents). The
//                       app can open the screen (for the first two) but can
//                       NEVER verify the outcome, so the user's own "done"
//                       confirmation is stored locally and shown as exactly
//                       that: their word, not a verified fact.
//
// Never blur those two together. A guide that claims a setting is fine when it
// cannot actually tell is worse than one that admits the limit — the whole
// reason this screen exists is that the user has been chasing an invisible
// problem for days.
import { CFG } from './config.js';
import { Store } from './store.js';
import { DiagLog } from './diag-log.js';

export class OemSetup {
  static #plugin = window.Capacitor?.Plugins?.OemSetup ?? null;

  static isSupported() {
    return !!this.#plugin;
  }

  /** Locally-stored record of the manual steps the user says they've done. */
  static getManual() {
    const v = Store.get(CFG.keys.oemSetup, null);
    return {
      autostart:   !!v?.autostart,
      oemBattery:  !!v?.oemBattery,
      recentsLock: !!v?.recentsLock,
      dismissed:   !!v?.dismissed,
    };
  }

  static setManual(patch) {
    const next = { ...this.getManual(), ...patch };
    Store.set(CFG.keys.oemSetup, next);
    return next;
  }

  /**
   * Live device state. Returns null in the browser so callers can skip the
   * whole feature rather than rendering a guide full of unknowns.
   */
  static async status() {
    if (!this.#plugin) return null;
    try {
      return await this.#plugin.status();
    } catch (e) {
      DiagLog.log('PERM', `OEM setup status failed — ${e?.message || e}`);
      return null;
    }
  }

  /**
   * Builds the full step list the modal renders: live status for the
   * verifiable ones, the user's stored confirmation for the rest.
   * `state` is 'ok' | 'todo' | 'unknown' — 'unknown' meaning "we opened the
   * screen for you, but only you can see whether it took".
   */
  static async buildSteps() {
    const s = await this.status();
    if (!s) return [];
    const manual = this.getManual();
    const steps = [];

    // ── Verifiable ──────────────────────────────────────────────
    steps.push({
      id: 'battery',
      kind: 'auto',
      state: s.batteryUnrestricted ? 'ok' : 'todo',
      icon: '🔋',
      title: 'חיסכון בסוללה (אנדרואיד)',
      desc: 'ללא הפטור הזה אנדרואיד עוצר את זיהוי הרקע כשהמסך כבוי.',
      action: s.batteryUnrestricted ? null : 'requestBattery',
      actionLabel: 'בטל את ההגבלה',
    });
    steps.push({
      id: 'location',
      kind: 'auto',
      state: s.locationGranted && s.locationPrecise ? 'ok' : 'todo',
      icon: '📍',
      title: 'הרשאת מיקום מדויק',
      desc: s.locationGranted && !s.locationPrecise
        ? 'המיקום מאושר אך לא במצב "מדויק" — זיהוי הנסיעה לא יעבוד אמין.'
        : 'נדרשת כדי לזהות שהרכב התרחק ממקום החניה.',
      action: s.locationGranted && s.locationPrecise ? null : 'openAppSettings',
      actionLabel: 'פתח הרשאות אפליקציה',
    });
    steps.push({
      id: 'notifications',
      kind: 'auto',
      state: s.notificationsGranted ? 'ok' : 'todo',
      icon: '🔔',
      title: 'הרשאת התראות',
      desc: 'בלעדיה כל ההתראות ברקע נחסמות — גם אם הזיהוי עצמו עובד מצוין.',
      action: s.notificationsGranted ? null : 'openAppSettings',
      actionLabel: 'פתח הרשאות אפליקציה',
    });
    steps.push({
      id: 'bluetooth',
      kind: 'auto',
      state: s.bluetoothGranted ? 'ok' : 'todo',
      icon: '🔵',
      title: 'הרשאת Bluetooth',
      desc: 'נדרשת לזיהוי חיבור/ניתוק אמיתי מהרכב.',
      action: s.bluetoothGranted ? null : 'openAppSettings',
      actionLabel: 'פתח הרשאות אפליקציה',
    });

    // ── Not verifiable — openable only ──────────────────────────
    if (s.autostartAvailable) {
      steps.push({
        id: 'autostart',
        kind: 'manual',
        state: manual.autostart ? 'ok' : 'unknown',
        icon: '🚀',
        title: 'הפעלה אוטומטית (Autostart)',
        desc: 'ההגדרה הקריטית ביותר. בלעדיה היצרן חוסם את האפליקציה מלהתעורר אחרי הפעלה מחדש של המכשיר או עדכון — והזיהוי נשאר כבוי עד פתיחה ידנית. לאנדרואיד אין דרך לבדוק את זה, רק לפתוח את המסך.',
        action: 'openAutostart',
        actionLabel: 'פתח את מסך ההפעלה האוטומטית',
      });
    }
    if (s.oemBatteryAvailable) {
      steps.push({
        id: 'oemBattery',
        kind: 'manual',
        state: manual.oemBattery ? 'ok' : 'unknown',
        icon: '⚡',
        title: `חיסכון בסוללה של ${s.manufacturer || 'היצרן'}`,
        desc: 'נפרד לגמרי מההגדרה הראשונה ברשימה — ביצרנים רבים שניהם קיימים במקביל, וצריך לשחרר את שניהם. בחר "ללא הגבלות", לא "חכם"/"מותאם".',
        action: 'openOemBattery',
        actionLabel: 'פתח את הגדרות הסוללה של היצרן',
      });
    }
    steps.push({
      id: 'recentsLock',
      kind: 'manual',
      state: manual.recentsLock ? 'ok' : 'unknown',
      icon: '🔒',
      title: 'נעילת האפליקציה במסך האחרונות',
      desc: 'פתח את מסך האפליקציות האחרונות, גרור למטה על כרטיס FindMyCar (או לחיצה ארוכה) ולחץ על סמל המנעול. זו מחוות משתמש בלבד — אין לה שום ממשק שאפליקציה יכולה להפעיל.',
      action: null,
      actionLabel: null,
    });

    return steps;
  }

  /** Runs one step's action. Returns the native result string, or null. */
  static async runAction(action) {
    if (!this.#plugin || !action) return null;
    try {
      const fn = {
        requestBattery:  () => this.#plugin.requestIgnoreBatteryOptimizations(),
        openAutostart:   () => this.#plugin.openAutostart(),
        openOemBattery:  () => this.#plugin.openOemBattery(),
        openAppSettings: () => this.#plugin.openAppSettings(),
      }[action];
      if (!fn) return null;
      const { result } = (await fn()) ?? {};
      DiagLog.log('PERM', `setup guide: ${action} → ${result || '?'}`);
      return result ?? null;
    } catch (e) {
      DiagLog.log('PERM', `setup guide: ${action} threw — ${e?.message || e}`);
      return null;
    }
  }

  /**
   * Whether the guide is worth showing unprompted. True only when something
   * is genuinely outstanding AND the user hasn't dismissed it — a guide that
   * reappears after everything is handled trains people to ignore it.
   */
  static async shouldAutoShow() {
    if (!this.#plugin) return false;
    if (this.getManual().dismissed) return false;
    const steps = await this.buildSteps();
    return steps.some(st => st.state !== 'ok');
  }
}
