export const CFG = Object.freeze({
  version: '1.15.0',
  keys: Object.freeze({
    theme:             'fmc_theme_v1',
    vehicles:          'fmc_vehicles_v1',
    activeVehicle:     'fmc_active_v1',
    curPrefix:         'fmc_cur_',
    histPrefix:        'fmc_hist_',
    legacyCurrent:     'fmc_current_v1',
    legacyHistory:     'fmc_history_v1',
    seenVersion:       'fmc_seen_version_v1',
    mapCollapsed:      'fmc_map_collapsed_v1',
    bluetoothSettings: 'fmc_bluetooth_v1',
    gpsAutoEnd:        'fmc_gps_auto_end_v1',
    notifTag:          'fmc-parking-active',
  }),
  gpsSpeedThreshold:    7,    // m/s ≈ 25 km/h — below this = pedestrian/cyclist
  gpsSpeedDuration:     8000, // ms speed must be sustained before suggesting end
  gpsDistanceThreshold: 300,  // meters from the saved parking spot before suggesting end (catches slow/no-speed-signal movement e.g. being driven away)
  maxHistory:        30,
  maxImgWidth:       900,
  imgQuality:        0.72,
  maxTextLen:        300,
  maxVehicles:       5,
  maxVehicleNameLen: 30,
  maxPlateLen:       15,
  maxColorLen:       20,
  toastDuration:     3000,
  timerInterval:     1000,
  geocodeTimeout:    6000,
  defaultCenter:     [31.7767, 35.2345],
  nominatim:         'https://nominatim.openstreetmap.org/reverse?format=json&addressdetails=1',
  vehicleIcons:      ['🚗', '🚙', '🚕', '🚌', '🏎️', '🛻', '🚐', '🚑'],
  changelog: Object.freeze([
    Object.freeze({
      version: '1.15.0',
      date: '2026-09-05',
      items: Object.freeze([
        'ייבוא גיבוי בודק ומבקש מחדש את כל ההרשאות (מכיוון שהגדרות מיובאות עשויות להפעיל תכונות שההתקנה הזו עדיין לא ביקשה הרשאה עבורן)',
        'עודכן עיצוב אייקון האפליקציה — הותאם למסכת האייקונים המסתגלים של אנדרואיד כך שלא ייחתך יותר',
        'עוצבו מחדש הווידג׳טים למראה מהוקצע יותר: תג אייקון עגול ופריסה אופקית בווידג׳ט החניה הפעילה, אייקון וקטורי וצל בווידג׳ט השמירה המהירה, פינות מעוגלות וכיתוב כתובת בווידג׳ט המפה המוקטנת',
      ]),
    }),
    Object.freeze({
      version: '1.14.0',
      date: '2026-09-05',
      items: Object.freeze([
        'תוקן: הרשאות מצלמה והקלטה קוליות היו חסרות באפליקציית האנדרואיד — התכונות לא עבדו כלל',
        'בקשת כל ההרשאות (מיקום, Bluetooth, מצלמה, הקלטה, נוטיפיקציות) מוצגת מיד בפתיחה ראשונה של האפליקציה',
        'הפעלת Bluetooth בהגדרות בודקת ומבקשת הרשאה מחדש אם צריך',
      ]),
    }),
    Object.freeze({
      version: '1.13.2',
      date: '2026-09-05',
      items: Object.freeze([
        'תיקון גלילה במסך הרכבים/הגדרות — כל המסך גולל יחד כדי שרשימת הרכבים לא תיחתך',
        'עדכון אפליקציית האנדרואיד ישירות מ-APK חדש בלי להסיר את הקיימת קודם',
      ]),
    }),
    Object.freeze({
      version: '1.13.1',
      date: '2026-09-05',
      items: Object.freeze([
        'תיקון קריסה באפליקציית האנדרואיד בפתיחה ראשונה (Android 14) — שירות הרקע לא הצליח לעלות לפני שהורשתה גישת Bluetooth',
      ]),
    }),
    Object.freeze({
      version: '1.13.0',
      date: '2026-09-05',
      items: Object.freeze([
        'הצעת סיום חניה גם לפי מרחק מהחניה (בנוסף למהירות) — מזהה תנועה גם כשאין נתוני מהירות אמינים',
        'נוטיפיקציות מערכת לאירועי Bluetooth/GPS ברקע: סיום/התחלת חניה אוטומטיים והצעת סיום חניה — כדי שתדעו גם כשהמסך כבוי',
      ]),
    }),
    Object.freeze({
      version: '1.12.0',
      date: '2026-09-05',
      items: Object.freeze([
        'גיבוי ושחזור נתונים — קובץ גיבוי להעברת רכבים, היסטוריה והגדרות בין ה-PWA לאפליקציית האנדרואיד (ולהפך)',
      ]),
    }),
    Object.freeze({
      version: '1.11.0',
      date: '2026-09-04',
      items: Object.freeze([
        'אפליקציית Android (APK) — גרסה נוספת, נפרדת מה-PWA, עם זיהוי Bluetooth אמיתי ברקע (לא רק בעת שהדפדפן פעיל) לסיום/התחלת חניה אוטומטיים',
        'ווידג\'טים למסך הבית באנדרואיד: חניה פעילה, שמירה מהירה, מפה מוקטנת',
      ]),
    }),
    Object.freeze({
      version: '1.10.0',
      date: '2026-07-09',
      items: Object.freeze([
        'כפתור "החלף חניה" במסך הכניסה — כשחוזרים לאפליקציה עם חניה פעילה ניתן להחליפה ישירות',
      ]),
    }),
    Object.freeze({
      version: '1.9.0',
      date: '2026-07-09',
      items: Object.freeze([
        'כפתור "החלף חניה" — שומר את החניה הנוכחית להיסטוריה ומיד מתחיל חניה חדשה במיקום GPS הנוכחי',
      ]),
    }),
    Object.freeze({
      version: '1.8.0',
      date: '2026-07-01',
      items: Object.freeze([
        'נוטיפיקציה כשהאפליקציה ברקע — פרטי החניה הפעילה מוצגים בחלון התראה',
        'Wake Lock — מסך נשאר פעיל בזמן חניה כדי שה-GPS וה-Bluetooth ימשיכו לעבוד',
        'זיהוי Bluetooth מחדש בחזרה לאפליקציה — לא מפספסים חיבור/ניתוק שקרה ברקע',
      ]),
    }),
    Object.freeze({
      version: '1.7.0',
      date: '2026-07-01',
      items: Object.freeze([
        'כפתור "סיום חניה" ישיר בכרטיס החניה הפעילה',
        'הצעת סיום חניה אוטומטית לפי מהירות GPS (נסיעה ברכב)',
        'מידע Bluetooth בהיסטוריה — מי התחיל/סיים כל חניה',
      ]),
    }),
    Object.freeze({
      version: '1.6.0',
      date: '2026-06-30',
      items: Object.freeze([
        'קישור מכשיר Bluetooth לכל רכב לזיהוי אוטומטי',
        'חיבור Bluetooth → הצעה או סיום אוטומטי של חניה פעילה',
        'ניתוק Bluetooth → שמירת חניה אוטומטית לפי GPS',
        'חלון הוספת מדיה (תמונה / הקלטה / תיאור) לאחר שמירה אוטומטית',
        'מסך הגדרות Bluetooth לכלל הרכבים ולכל רכב בנפרד',
      ]),
    }),
    Object.freeze({
      version: '1.5.0',
      date: '2026-06-27',
      items: Object.freeze([
        'תמונה, הקלטה ותיאור מוצגים ישירות בתוך כרטיס החניה',
        'מחיקת תמונה, הקלטה או תיאור בנפרד בלחיצה על כפתור מחיקה',
        'האזנה להקלטה ישירות מהאפליקציה ללא פתיחת חלון נוסף',
        'עריכת תיאור ישירות מכרטיס החניה',
      ]),
    }),
    Object.freeze({
      version: '1.4.0',
      date: '2026-06-26',
      items: Object.freeze([
        'לחיצה על "חניה פעילה" בכותרת פותחת את מסך סיום החניה',
        'פרטי רכב נוספים: לוחית רישוי וצבע (שדות אופציונליים)',
      ]),
    }),
    Object.freeze({
      version: '1.3.0',
      date: '2026-06-26',
      items: Object.freeze([
        'מספר גרסה גלוי בכותרת האפליקציה',
        'חלון "מה חדש" מוצג בכל עדכון',
        'תיקון תצוגת מפה ואיתחול מחדש',
        'כפתור הסתרת / הצגת המפה',
        'ניקוי חניה נוכחית לפי רכב מתצוגת הרכבים',
      ]),
    }),
    Object.freeze({
      version: '1.2.0',
      date: '2026-06-25',
      items: Object.freeze([
        'תמיכה במספר רכבים (עד 5)',
        'שיתוף ב-WhatsApp עם בחירת תוכן',
        'חבילת בדיקות אוטומטיות (Vitest + Playwright)',
      ]),
    }),
  ]),
});
