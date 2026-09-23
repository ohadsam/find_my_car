export const CFG = Object.freeze({
  version: '1.46.0',
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
    dailyStatus:       'fmc_daily_status_v1',
    // Which manual steps of the background-setup guide the user says they've
    // completed, plus whether they dismissed it. Manual-only by necessity:
    // MIUI Autostart / the OEM battery policy / the Recents lock have no API
    // to read, so this stores the user's own word — never a verified fact
    // (see js/oem-setup.js).
    oemSetup:          'fmc_oem_setup_v1',
    notifTag:          'fmc-parking-active',
  }),
  // Vehicle-movement detection. See CLAUDE.md "Vehicle-movement detection"
  // for the reasoning behind each of these — in particular why the distance
  // trigger is no longer allowed to fire on its own (walking 300m from the car
  // used to produce a "your car seems to have moved" suggestion).
  gpsSpeedThreshold:    7,      // m/s ≈ 25 km/h — above walking (~1.4) and running (~3-5), which is all it has to exclude. NOT a "real driving speed": city traffic averages well under that, and a higher bar disarmed detection for whole drives (see CLAUDE.md, v1.42.0)
  gpsSpeedDuration:     120000, // ms ACCUMULATED above the threshold before speed alone suggests end (not "continuously since" — red lights must not undo progress)
  gpsVehicleEvidenceMs: 10000,  // ms accumulated above the threshold before the distance trigger may fire at all — distance says how far, never how
  gpsSpeedSampleCapMs:  15000,  // ms ceiling on how much any single sample may add, so one fast fix after a long gap can't fill the accumulator at once
  gpsDerivedSpeedMinIntervalMs: 5000, // ms — shortest interval a speed may be DERIVED over when the device reports none; below this, GPS jitter (20m of error 1s apart reads as 20 m/s) would fabricate vehicle evidence
  gpsEvidenceTtlMs:     600000, // ms (10 min) — accumulated evidence expires after this long with no further above-threshold sample, so one ride early in a parking session can't leave the distance trigger armed for a plain walk hours later
  // Walk-away parking suggestion (Bluetooth disconnect -> offer to save the
  // spot). See CLAUDE.md "Walk-away parking suggestion". Since v1.46.0 the ask
  // itself is unconditional (fires on every eligible disconnect) — these
  // thresholds now only govern whether an already-shown notification gets
  // WITHDRAWN. walkMinSpeed/walkMaxSpeed/walkRequiredMs/walkMinDisplacement
  // are read on the native side only (ParkingForegroundService.kt's mirrored
  // constants), feeding WalkAwayEngine's now-vestigial SuggestStart branch —
  // kept rather than removed so the engine's tests and shape stay untouched.
  // walkAbortSpeed and walkWindowMs are the ones still doing real work.
  walkMinSpeed:         0.5,    // m/s — lower edge of the pedestrian band (vestigial, see above)
  walkMaxSpeed:         3.0,    // m/s — upper edge of the pedestrian band (~11 km/h) (vestigial, see above)
  walkAbortSpeed:       6.0,    // m/s (~22 km/h) — above this shortly after a disconnect the car clearly never stopped here, so the already-shown suggestion is withdrawn rather than left standing for a destination it knows nothing about
  walkRequiredMs:       8000,   // ms accumulated in the pedestrian band (vestigial, see above)
  walkMinDisplacement:  30,     // meters from the disconnect point (vestigial, see above)
  walkWindowMs:         600000, // ms (10 min) — how long after a disconnect a stale suggestion is still allowed to reach the in-app modal on resume; generous, since sitting in the car a few minutes before getting out is normal
  // How old a native Bluetooth event may be before its location is no longer
  // trustworthy for auto-start. Delivery of a plugin event waits for the
  // WebView's JS engine to resume, so a disconnect can surface long after it
  // happened — and saving "where you are now" is then simply the wrong place.
  // Two minutes: within that you are still at the car; past it, defer to the
  // location native recorded at the moment of the disconnect.
  btEventMaxAgeMs:      120000,
  widgetActionDedupeMs: 3000,   // ms — an identical widget/notification action repeated within this window is treated as one tap, not two (a duplicate broadcast saved two parkings and posted two notifications)
  gpsDistanceThreshold: 300,    // meters from the saved parking spot before suggesting end (catches movement the speed check would miss, e.g. stop-and-go traffic)
  // How often the WEB (js/app.js) and SERVICE (ParkingForegroundService.kt)
  // heartbeats log to DiagLog's SERVICE category, proving each is
  // continuously alive — not just at start/stop transitions. No single
  // shared source between JS and Kotlin, so ParkingForegroundService.kt's
  // matching constant must be kept in sync with this by hand if it ever
  // changes (see CLAUDE.md "Heartbeats").
  diagHeartbeatIntervalMs: 5 * 60 * 1000,
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
      version: '1.46.0',
      date: '2026-09-23',
      items: Object.freeze([
        'הצעת "התחלת חניה" אחרי ניתוק Bluetooth נשלחת עכשיו מיד עם הניתוק, ולא רק אחרי שזוהתה הליכה בפועל — הליכה הייתה לפעמים איטית/קצרה מדי כדי להיקלט, וההצעה פשוט לא הופיעה',
        'אם מתברר תוך כמה שניות שהרכב עדיין בתנועה (הניתוק היה ברמזור/במנהרה) ההודעה מבוטלת אוטומטית מהמסך',
        'התראה שכבר נשלחה מבוטלת גם בחיבור מחדש לרכב',
      ]),
    }),
    Object.freeze({
      version: '1.45.1',
      date: '2026-09-23',
      items: Object.freeze([
        'חניה שלא נשמרה בגלל מיקום כבר לא נכשלת בשקט: מוצגת הודעה שאומרת מה בדיוק קרה — אין הרשאת מיקום (ואז נפתחות ההגדרות) או שלא התקבל קליט GPS — ובנוסף נשלחת התראה, כי בשמירה אוטומטית אין מסך לראות בו הודעה',
        'לפני ויתור, נעשה ניסיון שני עם מיקום גס/מהמטמון — ניסיון מדויק יחיד נכשל דרך קבע בחניון או בתוך מבנה, ומיקום קצת פחות מדויק עדיף על כלום',
        'יומן האבחון מפריד עכשיו בין "אין הרשאה" לבין "לא התקבל קליט" ו"פג הזמן". עד כה כל השלושה נרשמו כ-"denied or unavailable", ולא היה אפשר לדעת מה מהם קרה',
      ]),
    }),
    Object.freeze({
      version: '1.45.0',
      date: '2026-09-22',
      items: Object.freeze([
        'תוקן (אנדרואיד) — באג שגרם לשמירת חניה במיקום שגוי: ניתוק Bluetooth בזמן שהאפליקציה סגורה טופל רק כשפתחת אותה, ואז החניה נשמרה איפה שאתה עומד במקום איפה שהרכב',
        'המיקום נלכד עכשיו בצד הנייטיב ברגע הניתוק עצמו, ומשמש בשמירה — גם אם האפליקציה נפתחה רק שעה אחר כך',
        'אירוע ניתוק שמגיע באיחור כבר לא ישמור חניה לפי המיקום הנוכחי; אם אין מיקום שנלכד, מוצגת הודעה במקום שמירה במקום הלא נכון',
        'פעולות Bluetooth שממתינות מיושמות עכשיו גם בחזרה לאפליקציה, לא רק בהפעלה מחדש שלה',
      ]),
    }),
    Object.freeze({
      version: '1.44.1',
      date: '2026-09-22',
      items: Object.freeze([
        'יומן האבחון (קטגוריית WALK) מסביר עכשיו למה ניתוק Bluetooth לא פתח חלון "הצע חניה אחרי שהתרחקת" — איזו הגדרה בדיוק מנעה זאת. עד כה זה היה שקט מוחלט, ולא היה אפשר להבדיל בין "הופעל והחליט שאין מה לעשות" לבין "לא רץ בכלל"',
      ]),
    }),
    Object.freeze({
      version: '1.44.0',
      date: '2026-09-22',
      items: Object.freeze([
        'תוקן (אנדרואיד): פעולה שבוצעה מהתריס או מהווידג\'ט בזמן שהאפליקציה סגורה משתקפת עכשיו בווידג\'טים מיד, ולא רק בפעם הבאה שפותחים את האפליקציה — עד כה הווידג\'ט המשיך להציג חניה פעילה גם אחרי שסיימת אותה',
        'אותו תיקון חל גם על סיום/התחלת חניה אוטומטית דרך Bluetooth כשהאפליקציה סגורה',
        'נוסף כפתור רענון (↻) לשלושת הווידג\'טים — בודק מיד את מצב החניות הנוכחי',
        'שינוי שעדיין לא אושר מול האפליקציה מסומן בווידג\'ט ב-⏳, כדי שברור מה כבר סונכרן ומה עוד לא',
        'תוקן (אנדרואיד): התראות "מזוהה נסיעה", "הגעת לרכב?" ו"לשמור את החניה?" קופצות עכשיו על המסך כמו הודעה נכנסת, במקום להגיע בשקט לתריס בלבד',
      ]),
    }),
    Object.freeze({
      version: '1.43.1',
      date: '2026-09-18',
      items: Object.freeze([
        'תוקן (אנדרואיד): זיהוי ההליכה החדש יכול היה לא לפעול כלל אחרי הפעלה מחדש של הטלפון או אחרי עדכון האפליקציה — שירות הרקע היה משדרג את הרשאת המיקום שלו רק כשיש חניה פעילה, ודווקא לזיהוי ההליכה אין חניה פעילה בהגדרה',
      ]),
    }),
    Object.freeze({
      version: '1.43.0',
      date: '2026-09-18',
      items: Object.freeze([
        'תכונה חדשה (אנדרואיד): אחרי ניתוק Bluetooth מהרכב, אם המכשיר מזהה שהתרחקת ברגל — האפליקציה מציעה לשמור את החניה. ההצעה מגיעה כהתראה בתריס עם כפתור "שמור חניה", בלי צורך לפתוח את האפליקציה',
        'המיקום שנשמר הוא זה שנלכד ברגע הניתוק — לא המקום שבו אתה עומד כשאתה עונה, כי עד אז כבר התרחקת',
        'התכונה היא opt-in לכל רכב בנפרד ("הצע חניה אחרי שהתרחקת" בהגדרות Bluetooth), ורלוונטית רק כשהתחלת חניה אוטומטית כבויה — עם התחלה אוטומטית החניה ממילא נשמרת מיד',
        'אם אחרי הניתוק אתה עדיין נע במהירות רכב (הבלוטות\' נפל ברמזור או במנהרה) — ההצעה נזנחת, כי הרכב לא עצר שם',
      ]),
    }),
    Object.freeze({
      version: '1.42.1',
      date: '2026-09-18',
      items: Object.freeze([
        'תיקון דחוף: גרסה 1.42.0 לא עלתה בכלל — גרש בודד בתוך מחרוזת בעברית ("ווידג\'ט") שבר את קובץ ההגדרות, וכל האפליקציה נפלה בטעינה',
        'נוספה בדיקת תחביר אוטומטית לכל קובצי ה-JS, שרצה גם ב-npm test וגם בבנייה — כך ששגיאה מהסוג הזה לא תוכל לעבור שוב',
      ]),
    }),
    Object.freeze({
      version: '1.42.0',
      date: '2026-09-18',
      items: Object.freeze([
        'תוקן שורש הבעיה שדיווחת עליה: סף המהירות של 50 קמ"ש (שנכנס ב-1.40.0) פשוט לא מושג בנסיעה עירונית. הלוג שלך מראה קילומטר שלם של נסיעה עם 0 שניות "עדות נסיעה" — ולכן ההצעה לסיום חניה לא הופיעה. הסף חזר ל-25 קמ"ש, שזה עדיין הרבה מעל הליכה (5 קמ"ש) וריצה (11-18 קמ"ש)',
        'בוטלה גם הדרישה (מ-1.41.0) שהתנועה המהירה תתחיל ליד הרכב — באותה נסיעה הדגימה המהירה הראשונה הייתה כבר מעבר לקילומטר מהרכב, כך שההצעה לא הייתה מופיעה אף פעם. מנגנון שיכול להשבית זיהוי לצמיתות הוא מחיר גבוה מדי',
        'תוקן באג שבו לחיצה על פעולת ווידג\'ט נעלמה בשקט: אם האפליקציה הייתה חיה אבל הדף עוד לא הספיק להיטען, הפעולה לא בוצעה, לא נשמרה לביצוע מאוחר, ולא הופיעה בשום מקום. עכשיו כל לחיצה או מתבצעת מיד, או נשמרת ומבוצעת בפתיחה הבאה — עם הודעה בהתאם',
        'תוקן: לחיצה אחת על פעולת ווידג\'ט יכלה להישלח פעמיים ולשמור שתי חניות עם שתי התראות',
      ]),
    }),
    Object.freeze({
      version: '1.41.0',
      date: '2026-09-17',
      items: Object.freeze([
        'המשך לתיקון של 1.40.0: הדרישה ל"עדות לנסיעה" מנעה הליכה, אבל העדות עצמה עדיין אמרה רק "הטלפון נע מהר מתישהו" — לא "הרכב הזה נסע". עכשיו העדות נספרת רק אם התנועה המהירה התחילה ליד הרכב החונה (עד 150 מטר)',
        'תוקן: הליכה לתחנה ואז נסיעה ברכבת/אוטובוס כבר לא מזוהה כ"הרכב זז" — זו נסיעה שלך, לא של הרכב',
        'תוקן: עדות שנצברה פעם אחת נשארה תקפה עד סוף החניה, כך שנסיעה מוקדמת השאירה את ההתראה דרוכה גם להליכה רגילה שעות אחר כך. העדות פגה עכשיו אחרי 10 דקות ללא תנועה מהירה נוספת',
      ]),
    }),
    Object.freeze({
      version: '1.40.0',
      date: '2026-09-17',
      items: Object.freeze([
        'תוקן זיהוי תנועה שגוי: הליכה ברגל של 300 מטר מהרכב הפעילה את ההצעה לסיום חניה. בדיקת המרחק פעלה ללא שום תנאי מהירות — היא ידעה כמה התרחקת, אף פעם לא איך — ולכן היא דורשת עכשיו גם עדות אמיתית לנסיעה',
        'סף הזיהוי הוא כעת 50 קמ"ש, מהירות שאי אפשר להגיע אליה בהליכה או באופניים, במקום 25 קמ"ש',
        'הזמן שנצבר במהירות נסיעה נספר במצטבר ולא "ברציפות" — עצירה ברמזור כבר לא מאפסת את הספירה ומתחילה מהתחלה',
        'כשהמכשיר לא מדווח מהירות (קורה בהרבה מכשירים), המהירות מחושבת מהמרחק והזמן בין שתי מדידות — כדי שדרישת המהירות החדשה לא תשבית את הזיהוי דווקא במכשירים האלה',
      ]),
    }),
    Object.freeze({
      version: '1.39.0',
      date: '2026-09-16',
      items: Object.freeze([
        'להתראות שמבקשות אישור — "מזוהה נסיעה" ו"הגעת לרכב?" — נוספו כפתורים ישירות בתריס: "סיים חניה" ו"התעלם". קודם הן רק ביקשו "פתח את האפליקציה לאישור", וזו בדיוק הבקשה הלא נכונה ממישהו שנוהג',
        'הכפתורים פועלים בלי לפתוח את האפליקציה, ואם היא סגורה לגמרי הפעולה מתבצעת בפתיחה הבאה — בדיוק כמו פעולות הווידג\'טים',
        'לחיצה על כפתור בתריס סוגרת גם את החלון המקביל בתוך האפליקציה, כדי שלא תחזור לשאלה שכבר ענית עליה',
      ]),
    }),
    Object.freeze({
      version: '1.38.1',
      date: '2026-09-16',
      items: Object.freeze([
        'תוקן שורש הבעיה של זיהוי נסיעה ברקע: כששירות הרקע עולה מחדש לבד (אחרי עדכון או הפעלת המכשיר), אנדרואיד לא מעניקה לו הרשאת מיקום-ברקע — והוספת ההרשאה מאוחר יותר לא עוזרת רטרואקטיבית. השירות פשוט לא קיבל אף עדכון מיקום, בזמן שכל הסימנים הראו "פעיל". עכשיו הוא מופעל מחדש מתוך האפליקציה ברגע שהיא נפתחת, וכך מקבל את ההרשאה באמת',
        'רישום "פעימת הלב" כולל עכשיו כמה עדכוני GPS התקבלו בפועל, לפני כמה זמן היה העדכון האחרון, והמרחק הנוכחי מהחניה — כדי שלא תהיה יותר אי-ודאות בין "המעקב לא רץ", "רץ אבל לא מגיע אף עדכון" ו"מגיעים עדכונים אך לא נחצה הסף"',
      ]),
    }),
    Object.freeze({
      version: '1.38.0',
      date: '2026-09-16',
      items: Object.freeze([
        'נוספו לכל שלושת הווידג\'טים שני סמלי חיווי קטנים — מיקום ו-Bluetooth — שצבעם מראה במבט אחד אם זיהוי הרקע באמת פועל כרגע, בלי להיכנס לאפליקציה או לקרוא את יומן האבחון',
        'ירוק = פעיל, כתום = רץ אך אנדרואיד מונע עדכונים ברקע (פתח את האפליקציה פעם אחת), אדום = אמור לרוץ ולא רץ, אפור = כבוי בהגדרות או שאין חניה פעילה — כדי שאדום יסמן תמיד תקלה אמיתית ולא הגדרה שכיבית בכוונה',
        'החיווי מתרענן כל 2 דקות, וגם מיד עם כל שינוי הגדרה או פעולת חניה',
      ]),
    }),
    Object.freeze({
      version: '1.37.1',
      date: '2026-09-16',
      items: Object.freeze([
        'תוקן באג חמור שנכנס בגרסה 1.36.3: בהפעלה מחדש של המכשיר ובעדכון האפליקציה, שירות הרקע ניסה לעלות עם הרשאת מיקום שאנדרואיד 14 מתירה רק כשהאפליקציה גלויה על המסך — הבקשה נדחתה, וכל שירות הרקע נפל יחד איתה (ללא Bluetooth, ללא GPS, ללא כלום) עד לפתיחה ידנית של האפליקציה',
        'שירות הרקע עולה עכשיו תמיד, גם אם אנדרואיד דוחה סוג הרשאה מסוים — דחייה עולה לנו באותה יכולת בלבד, לא בכיבוי מוחלט של הזיהוי',
        'מעקב המיקום משתדרג אוטומטית ברגע שהאפליקציה נפתחת, כך שאחרי הפעלה מחדש של המכשיר הזיהוי חוזר לפעול מלא',
        'יומן האבחון מציין עכשיו במפורש כשמעקב GPS התחיל אך אנדרואיד עדיין מונע ממנו לקבל עדכונים ברקע — במקום לרשום "הופעל" ולהשאיר את זה עמום',
      ]),
    }),
    Object.freeze({
      version: '1.37.0',
      date: '2026-09-15',
      items: Object.freeze([
        'נוסף מדריך "הגדרת זיהוי ברקע" (אנדרואיד) — מציג רשימה של כל הגדרות המכשיר שקובעות אם האפליקציה בכלל רשאית לפעול ברקע, ופותח כל מסך בלחיצה אחת במקום לחפש אותו ידנית בהגדרות',
        'המדריך בודק בפועל מה שניתן לבדוק (פטור מחיסכון בסוללה, הרשאות מיקום/התראות/Bluetooth) ומסמן בבירור אילו הגדרות של היצרן — כמו "הפעלה אוטומטית" בשיאומי — אי אפשר לאמת מתוך האפליקציה, כדי שלא יוצג מידע שאינו נכון',
        'המדריך נפתח אוטומטית בפתיחת האפליקציה רק כל עוד משהו עדיין דורש טיפול, וזמין תמיד מתוך ההגדרות',
      ]),
    }),
    Object.freeze({
      version: '1.36.3',
      date: '2026-09-15',
      items: Object.freeze([
        'תוקן באג שורש בזיהוי GPS ברקע: לשירות הרקע חסרה ההרשאה המיוחדת שאנדרואיד דורשת כדי להמשיך לקבל עדכוני מיקום כשהאפליקציה סגורה — לכן זיהוי הנסיעה פשוט הפסיק לפעול רגע אחרי סגירת האפליקציה, וההצעה לסיום חניה הופיעה רק בפתיחה הבאה',
        'תוקן: לאחר הפעלה מחדש של המכשיר או עדכון האפליקציה, תהליכי הרקע נשארו כבויים עד שהאפליקציה נפתחה ידנית — עכשיו הם מתאוששים לבד',
        'תוקן: אם מערכת ההפעלה סגרה את תהליך האפליקציה, שירות הרקע היה חוזר "ריק" בלי לדעת שיש חניה פעילה או ש-Bluetooth דלוק — עכשיו הוא משחזר את מצבו מהנתונים השמורים',
        'תוקן: שינוי הגדרות Bluetooth או זיהוי נסיעה לא עודכן מיד בצד הרקע אלא רק אחרי פעולת חניה כלשהי — עכשיו כל שינוי הגדרה מסונכרן מיידית',
      ]),
    }),
    Object.freeze({
      version: '1.36.2',
      date: '2026-09-15',
      items: Object.freeze([
        'תוקן באג משמעותי: כשלא הייתה חניה פעילה, סגירת האפליקציה גרמה לשירות הרקע כולו להיעצר לגמרי (לא רק המסך) — כך שזיהוי Bluetooth ברקע פסק לעבוד עד לפתיחה הבאה של האפליקציה, גם כשההגדרה הייתה דלוקה',
        'תוקן: גם לאחר שהשירות נשאר פעיל, רישום פעולות Bluetooth שקרו כשהאפליקציה סגורה (לצורך ביצוען בפתיחה הבאה) לא תמיד עבד — עכשיו זה קורה ישירות משירות הרקע ולא תלוי בכך שהאפליקציה הייתה פתוחה לאחרונה',
      ]),
    }),
    Object.freeze({
      version: '1.36.1',
      date: '2026-09-10',
      items: Object.freeze([
        'נוסף רישום אבחון נוסף לחיבורי Bluetooth כדי לחקור דיווח על כך שחיבור/ניתוק לרכב לא הפעיל התראה — עדיין בבדיקה, אין שינוי בהתנהגות בפועל',
      ]),
    }),
    Object.freeze({
      version: '1.36.0',
      date: '2026-09-10',
      items: Object.freeze([
        'תוקן: רישומי "פעימת לב" של שירות הרקע ברקע לא נרשמו בקצב קבוע (לפעמים עד 20 דקות במקום 5) כשהמכשיר נכנס למצב חיסכון בסוללה עמוק — עכשיו נרשמים בצורה אמינה גם אז',
        'נוספה התראה יומית (אנדרואיד) שמדווחת אילו רכבים חונים כרגע ואיפה — כדי לוודא שהאפליקציה וזיהוי הרקע פעילים. ניתן להפעיל/לכבות אותה גלובלית בהגדרות, ובנפרד לכל רכב',
      ]),
    }),
    Object.freeze({
      version: '1.35.0',
      date: '2026-09-09',
      items: Object.freeze([
        'יומן האבחון מציג עכשיו לכל שורה מאיזה תהליך היא הגיעה (האפליקציה או אחד מהשירותים הנייטיביים) ואת השעה המדויקת שבה נרשמה בפועל, בנוסף לזמן שבו האירוע עצמו קרה',
        'נוספו רישומי "פעימת לב" ליומן האבחון מהאפליקציה ומשירות הרקע הנייטיבי כל כמה דקות, כדי שאפשר יהיה לדעת בוודאות שהם רצים ברקע כרגיל ולא נעצרו',
      ]),
    }),
    Object.freeze({
      version: '1.34.0',
      date: '2026-09-09',
      items: Object.freeze([
        'יומן האבחון כולל עכשיו גם קטגוריה "תקשורת נייטיב↔אפליקציה" — מציגה כל הודעה שנשלחה בין הצד הנייטיבי לאפליקציה (ולהפך), כדי לוודא בוודאות שהתקשורת ביניהם עובדת כמצופה ושאין הודעות שאבדו בדרך',
      ]),
    }),
    Object.freeze({
      version: '1.33.0',
      date: '2026-09-09',
      items: Object.freeze([
        'נוסף יומן חדש ליומן האבחון — "שירות רקע (נייטיב)" — שמראה בזמן אמת אם ומתי שירות הרקע, מעקב ה-GPS וקליטת אירועי Bluetooth היו פעילים בפועל, גם כשהאפליקציה הייתה סגורה, כדי לדעת בוודאות מה קרה ומתי',
      ]),
    }),
    Object.freeze({
      version: '1.32.0',
      date: '2026-09-09',
      items: Object.freeze([
        'תוקן: הווידג\'טים ("חניה פעילה" ו"מפה מוקטנת") לפעמים נשארו תקועים על "מיקום נשמר" במקום להציג את הכתובת האמיתית, גם אחרי שהכתובת כבר הופיעה בתוך האפליקציה עצמה',
        'תוקן: כשחניה של רכב שאינו הרכב הפעיל מסתיימת אוטומטית דרך Bluetooth, הווידג\'טים לא תמיד ידעו לעדכן שהחניה של אותו רכב הסתיימה',
      ]),
    }),
    Object.freeze({
      version: '1.31.0',
      date: '2026-09-09',
      items: Object.freeze([
        'תוקן: הצעת סיום חניה לפי GPS (וההתראה שאמורה להופיע יחד איתה) לא הופיעה כלל כשהמסך היה כבוי או האפליקציה ברקע — רק בפתיחה הבאה של האפליקציה, לפעמים הרבה זמן אחרי שהרכב כבר זז',
      ]),
    }),
    Object.freeze({
      version: '1.30.0',
      date: '2026-09-08',
      items: Object.freeze([
        'הווידג\'טים "חניה פעילה" ו"מפה מוקטנת" תומכים עכשיו במספר רכבים חונים בו-זמנית: בגודל רגיל אפשר לעבור בין הרכבים בכפתור 🔁, ובגודל מוגדל (גרירה להגדלה) שני הרכבים מוצגים יחד',
      ]),
    }),
    Object.freeze({
      version: '1.29.0',
      date: '2026-09-08',
      items: Object.freeze([
        'תוקן: לחיצה על פעולה בתפריט הווידג\'טים ("⋮") הייתה עלולה לפתוח את האפליקציה בטעות אחרי ביצוע הפעולה, גם כשהפעולה עצמה בוצעה ברקע כראוי',
        'פעולות ווידג\'ט (שמור/החלף/סיים) מציגות עכשיו נוטיפיקציה שמאשרת מה בוצע ולאיזה רכב — גם כשהפעולה מתבצעת ברקע לגמרי',
      ]),
    }),
    Object.freeze({
      version: '1.28.0',
      date: '2026-09-08',
      items: Object.freeze([
        'שלב תשיעי בהעברת זיהוי Bluetooth/GPS לקוד נייטיבי — נוטיפיקציית "חניה פעילה" (עם הכתובת) עוברת עכשיו ישירות דרך הצד הנייטיבי, כדי שתישאר מדויקת גם אם האפליקציה נסגרת לגמרי ברקע ולא רק תלויה בדפדפן',
      ]),
    }),
    Object.freeze({
      version: '1.27.0',
      date: '2026-09-08',
      items: Object.freeze([
        'שלב שמיני (וסיום) בהעברת זיהוי Bluetooth/GPS לקוד נייטיבי — פעולות ווידג\'ט (שמור/החלף/סיים) שנלחצות כשהאפליקציה סגורה לגמרי כבר לא פותחות את האפליקציה: הן מבוצעות אוטומטית בפתיחה הבאה, עם הודעה מיידית שהפעולה תתבצע',
      ]),
    }),
    Object.freeze({
      version: '1.26.0',
      date: '2026-09-08',
      items: Object.freeze([
        'שלב שביעי (וסיום) בהפיכת GPS לחי לגמרי — הצעת סיום חניה לפי GPS שקרתה כשהאפליקציה הייתה סגורה לגמרי ברקע מוצגת עכשיו כאותו חלון אישור בפתיחה הבאה של האפליקציה (ולא רק כהתראה) — עדיין דורשת אישור ידני, אף פעם לא מסיימת חניה אוטומטית',
      ]),
    }),
    Object.freeze({
      version: '1.25.0',
      date: '2026-09-08',
      items: Object.freeze([
        'שלב שישי (וסיום) בהפיכת Bluetooth לחי לגמרי — אירוע Bluetooth שקרה כשהאפליקציה הייתה סגורה לחלוטין ברקע כעת גם מבצע בפועל את הפעולה (סיום/התחלת חניה) בפתיחה הבאה של האפליקציה, ולא רק מציג התראה',
      ]),
    }),
    Object.freeze({
      version: '1.24.0',
      date: '2026-09-07',
      items: Object.freeze([
        'שלב חמישי בהעברת Bluetooth לקוד נייטיבי — כשהאפליקציה ברקע לגמרי (ה-WebView לא זמין) והמערכת מזהה חיבור/ניתוק Bluetooth אמיתי, הקוד הנייטיבי שומר את הפרטים ומציג התראה — עדיין לא מבצע את הפעולה בפועל (זה יגיע בשלב הבא), רק דואג שהמידע לא יאבד',
      ]),
    }),
    Object.freeze({
      version: '1.23.0',
      date: '2026-09-07',
      items: Object.freeze([
        'שלב רביעי בהעברת זיהוי GPS לקוד נייטיבי — מנוע ה-GPS מחובר עכשיו לצפייה אמיתית במיקום ברקע (מצב "צל" בלבד: רק רושם ליומן האבחון להשוואה, בלי לבצע פעולה בפועל)',
      ]),
    }),
    Object.freeze({
      version: '1.22.0',
      date: '2026-09-07',
      items: Object.freeze([
        'שלב שלישי בהעברת זיהוי GPS לקוד נייטיבי — מנוע החלטות GPS טהור (מהירות/מרחק) נוסף לצד הנייטיבי, מכוסה בבדיקות; עדיין לא מחובר לצפייה במיקום אמיתית — ללא שינוי בהתנהגות בפועל',
      ]),
    }),
    Object.freeze({
      version: '1.21.0',
      date: '2026-09-07',
      items: Object.freeze([
        'שלב שני בהעברת זיהוי Bluetooth לקוד נייטיבי — הקוד הנייטיבי מחשב עכשיו החלטה מקבילה בכל חיבור/ניתוק Bluetooth (מצב "צל") ורק רושם אותה ליומן האבחון להשוואה, בלי לבצע פעולה בפועל',
      ]),
    }),
    Object.freeze({
      version: '1.20.0',
      date: '2026-09-07',
      items: Object.freeze([
        'שלב ראשון בהעברת זיהוי Bluetooth/GPS לקוד נייטיבי (כמו Waze) כדי שלא יהיה תלוי במסך פתוח ברקע — עדיין לא פעיל בפועל, שלב תשתית בלבד',
      ]),
    }),
    Object.freeze({
      version: '1.19.0',
      date: '2026-09-07',
      items: Object.freeze([
        'האפליקציה מבקשת כעת פטור מחיסכון בסוללה בפתיחה ראשונה — חיסכון בסוללה יכול לעצור זיהוי Bluetooth/GPS ברקע גם כששירות הרקע פעיל',
        'הגדרות Bluetooth מציגות אזהרה ייעודית וכפתור לביטול הגבלת חיסכון בסוללה אם היא עדיין פעילה',
      ]),
    }),
    Object.freeze({
      version: '1.18.0',
      date: '2026-09-07',
      items: Object.freeze([
        'תפריט "⋮" בכל הווידג\'טים כולל עכשיו בורר רכב, ומבצע שמור/החלף/סיים חניה ישירות מהווידג\'ט — בלי לפתוח את האפליקציה',
        'ווידג\'ט "שמירה מהירה" שומר חניה ישירות בלי לפתוח את האפליקציה, עם הודעה קצרה שמאשרת מה קרה',
      ]),
    }),
    Object.freeze({
      version: '1.17.0',
      date: '2026-09-06',
      items: Object.freeze([
        'נוסף יומן אבחון (בהגדרות) — מציג בדיוק אילו אירועי Bluetooth/GPS/התראות האפליקציה קיבלה בפועל, נשמר 3 ימים, עם סינון לפי רכב, העתקה וייצוא',
        'נוסף בדיקת "שירות רקע פעיל" — מאתרת ישירות אם השירות ששומר על זיהוי Bluetooth/GPS ברקע אכן עלה בהצלחה',
        'נוספו רישומי Logcat מפורטים לצד הנייטיבי (Bluetooth, שירות רקע) לאבחון מעמיק יותר דרך adb',
      ]),
    }),
    Object.freeze({
      version: '1.16.0',
      date: '2026-09-06',
      items: Object.freeze([
        'תוקן: מחוון "מכשיר מקושר" בהגדרות רכב לא השתנה חזותית לאחר בחירת מכשיר Bluetooth (נשאר אפור)',
        'הגדרות Bluetooth מציגות אזהרה אם ההרשאה נחסמה על ידי המערכת, עם כפתור לפתיחת הגדרות האפליקציה ישירות',
        'נוטיפיקציית הרקע הועלתה בעדיפות (Low במקום Min) כדי שתופיע בפועל בשורת ההתראות, עם אייקון ממותג במקום אייקון מערכת גנרי',
        'לינוק מכשיר Bluetooth לרכב מציג כעת הודעה המפנה להגדרות Bluetooth כדי להפעיל התחלה/סיום חניה אוטומטיים',
        'ווידג\'טים "חניה פעילה" ו"שמירה מהירה" הוקטנו משמעותית (גודל וגופן)',
        'ווידג\'ט "חניה פעילה" מקבל כפתור פעולות מהירות (⋮) — שמור חניה, החלף חניה, זזתי, בחר רכב',
      ]),
    }),
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
