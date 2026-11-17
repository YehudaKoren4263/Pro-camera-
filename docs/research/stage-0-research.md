# שלב 0: מחקר מהמקור, לפני שכותבים שורת קוד

תאריך: 2026-11-17 · פלטפורמה: אנדרואיד בלבד

המסמך הזה עונה על ארבע שאלות:
1. מה באמת כואב לצלמים.
2. למה השוק לא פתר את זה.
3. איפה נמצאים "הפיצ'רים הנסתרים" ואיך פותחים אותם.
4. איך נוכיח שהצלחנו.

כל טענה מגובה במקור או במדידה. מה שלא נבדק מסומן **[לא אומת]**.

---

## 0. מגבלות המחקר (חשוב לקרוא קודם)

- **רדיט נחסם מהסביבה הזו**, גם ישירות וגם דרך כלי הקריאה. גם ניסיון לגשת לארכיוני רדיט חלופיים נחסם, ולא ניסיתי לעקוף את החסימה. קולות משתמשים נאספו ממנועי חיפוש (שמצטטים שרשורים), ממדרגות ומביקורות בחנויות, מ-DPReview ומפורומים של יצרנים. **פער פתוח:** ניתוח ישיר של r/AndroidCamera, r/photography ו-r/postprocessing, למשל דרך Reddit API עם מפתח, או ייצוא ידני שלך.
- **pixls.us ו-androidpolice.com לא נגישים מכאן** מבחינת רשת. התוכן שלהם נאסף דרך תקצירי חיפוש בלבד.
- **גיטהאב נגיש במלואו לקריאה.** שכפלתי וקראתי קוד מקור של 9 פרויקטים. זה החומר הגולמי החזק ביותר במסמך.
- **אין לי מכשיר פיזי.** טבלת היכולות בסעיף 4 בנויה מנתונים שפרויקטים אחרים מדדו על מכשירים אמיתיים, ולא ממדידה שלנו. המשימה הראשונה בקוד היא אפליקציית Probe שתמדוד בעצמה (סעיף 8).

---

## 1. חמש התובנות המרכזיות

1. **הבעיה האמיתית היא לא הפילטרים, אלא הרשאות.** מעבד התמונה של היצרן (ה-HAL) **מחליט מה לחשוף לפי זהות האפליקציה שפונה אליו.** בקושחה של vivo X300 Ultra נמצאה לוגיקה שמסווגת כל אפליקציה לפי שם החבילה (`AppType`), ומצבי 50 ו-200 מגה-פיקסל זמינים רק כש-`AppType == vivo` [rawr/docs/vivo-fullres.md]. לכן אפליקציות צד שלישי מקבלות 12MP בעוד שהמצלמה המובנית מקבלת 50MP. "פיצוח המערכת" הוא בדיוק השכבה הזו.
2. **אפשר לפרוץ את השכבה הזו בלי root, אבל רק לכל יצרן בנפרד, ורק מבוסס נתונים.** יש שלוש עדויות מתועדות. **vivo:** מחלקה נסתרת `VivoCameraManager` שמגיעים אליה ב-reflection, ומשנים בה את מפתח 40 (`useVivoCamera`). **OPPO:** מפתחות session `com.oplus.is.sdk.camera.package` ו-`com.oplus.engineercamera.agingtest.mode.select`. **Nothing Phone (3):** המצלמה המרובה האמיתית היא camera id 4, מוסתרת מעל החיישנים 0, 2 ו-3 [Koshi README]. **אין דרך כללית אחת.** מה שעובד הוא מאגר פרופילי מכשירים (JSON) שהקהילה מתרימה אליו, בתוספת כלי Probe.
3. **האיכות מגיעה מצינור עיבוד משלנו, לא מה-ISP.** צילום רצף RAW, יישור ומיזוג (HDR+, Hasinoff 2016), ושחזור פרטים מריבוי פריימים (Wronski 2019). כך עובדים MotionCam, rawr ו-LumenRAW. זה מוכח וקיים בקוד פתוח, ולכן זה לא החידוש שלנו. החידוש שלנו הוא **השילוב** של שלושה דברים: פתיחת חיישן מלא (תובנה 2), מיזוג, ופילם ספקטרלי.
4. **"פילטרים ברמה גבוהה" כבר הוגדרו מחדש בקהילה כסימולציה ספקטרלית.** spektrafilm של Andrea Volpato משחזר ספקטרום מ-RAW ליניארי, מקרין אותו דרך נגטיב וירטואלי אל נייר הדפסה, ומוסיף גרעין סטוכסטי. מודול שלו נכנס ל-darktable. FILCamera כבר הריץ אותו על Vulkan באנדרואיד. מסקנה: LUT מעל JPEG הוא כבר רמה של מתחילים.
5. **החלון פתוח עכשיו.** Adobe Project Indigo, של צוות המצלמה לשעבר של Pixel, זמין **רק ב-iOS**, ו-Adobe הודיעה שגרסת אנדרואיד תגיע ללא תאריך. Android 17 הוסיף "vendor-defined camera extensions", אבל ההערכה היא 6 עד 18 חודשים עד שיגיעו למכשירים, והן לא מכסות וידאו. **אין כרגע באנדרואיד מוצר שמשלב פתיחת חיישן, מיזוג ופילם ספקטרלי בחוויה של מוצר גמור ולא של פרויקט מחקר.**

---

## 2. עשר הבעיות המובילות, מדורגות

הדירוג משקלל תדירות ההופעה במקורות וחומרה עבור צלם מקצועי. לכל בעיה מצוינים העדות, הסיבה שהמתחרים לא פתרו אותה, והפתרון שלנו.

| # | הבעיה | עדות | למה לא נפתרה | הפתרון שלנו |
|---|---|---|---|---|
| 1 | **אפליקציות צד שלישי מקבלות פחות מהמצלמה המובנית**: חיישן מוגבל ל-12MP, עדשות נעולות, בלי מצב לילה | בעלי Samsung A36 מדווחים שה-UW, המאקרו והקדמית נעולות ומדווחות כ-LIMITED, ש-Camera2 מראה רק את האחורית והקדמית, וש-RAW_SENSOR לא עובד במלואו (פורום Samsung). ב-Redmi 13C חיישן 50MP חשוף רק ב-12MP [LumenRAW PLAN §3]. לפי XDA, מצבי Portrait, Night Sight ו-HDR+ חסומים | חסימה טכנית ועסקית של היצרן. רוב המפתחים מוותרים, כי לתחזק כל מכשיר זה יקר | שכבת **Device Unlock** מבוססת פרופילים (סעיף 3), בתוספת צינור עיבוד משלנו שלא תלוי ב-ISP |
| 2 | **מראה "מעובד מדי"**: חידוד יתר, HDR מצויר, מרקם סינתטי, אפקט "צבעי מים" | משתמשים ברדיט על S23 Ultra, Pixel ו-iPhone (Smart HDR). Koshi מצא ש-Device mode מוסיף הילות חידוד ומרקם סינתטי | היצרנים מכוונים למסך קטן ולמשתמש הממוצע | ברירת מחדל של עיבוד מינימלי. מיזוג רעש אמיתי מריבוי פריימים במקום NR שמוחק פרטים. בלי unsharp mask גלובלי |
| 3 | **התצוגה המקדימה לא תואמת לתמונה** | ב-Moment מודים שה-viewfinder משתמש ב-tone mapping של המערכת. FILCamera מודה שה-preview הוא "קירוב" ושגרעין והילה מופיעים רק בתמונה הסופית | הרכיבים המרחביים כבדים מדי לזמן אמת | Preview שמחושב **מה-RAW** על ה-GPU, כמו ב-MotionCam. גרסה מוקטנת בזמן אמת של שלבי הפילם, כולל הילה וגרעין בגודל המסך |
| 4 | **עיכוב תריס**, ותמונה שנלכדת ברגע אחר | S25 Ultra: עיכוב במצב Pro וב-Expert RAW, ו-Reduced delay "לא עובד באמינות". OnePlus 9 Pro: התמונה שונה מהתצוגה | Pro mode מבטל את ה-ZSL של היצרן | **ZSL משלנו**: מאגר מעגלי של פריימי RAW, ובחירת הפריים לפי חותמת הזמן של החיישן (כמו "Zero shutter lag burst" ב-MotionCam). המדד הוא מילישניות, נמדד בווידאו מהיר |
| 5 | **מורכבות ועומס הגדרות** | על MotionCam נכתב שמערך הפיצ'רים "גדול ומורכב בצורה קיצונית" ושיש בלבול נרחב בהגדרות ברדיט ובדיסקורד | מפתח-מהנדס בונה לעצמו | שתי שכבות: ממשק "מצלמה" נקי, ו"מעבדה" למי שרוצה. ברירות מחדל שנמדדו ולא נוחשו |
| 6 | **קבצים שבורים או לא תואמים** בתוכנות עריכה | DNG של MotionCam לא נפתחים ב-Premiere, ו-Resolve לא מזהה אותם בלינוקס. FreeDcam: גוון מג'נטה ב-DNG של Redmi Note 4 (issue #12) | מטא-דאטה של צבע וכיול חסרה או שגויה. בדיקה רק על מכשיר אחד | DNG מאומת עם `dng_validate` של Adobe ב-CI. DCP או ColorMatrix לכל חיישן. מבחן פתיחה אוטומטי ב-LibRaw ו-darktable, ובלייטרום ידנית |
| 7 | **יציבות**: קריסות ונפילות ביצועים אחרי עדכונים | MotionCam: נפילת ביצועים של 40-70% אחרי עדכון ביוני 2026 (Nothing 3a Pro), ופסים אדומים-כחולים ב-Sony Pro-I. rawr: ה-HAL של vivo קורס על session פתוח, ROI ריק מפיל את ספק המצלמה | יותר מדי מכשירים וקושחות, בלי QA לכל אחד | פרופיל לכל מכשיר עם `workarounds` מפורשים, טלמטריית קריסות ב-opt-in, ובדיקות regression לכל פרופיל |
| 8 | **מודל עסקי עוין**: מעבר למנוי, מחיר בלי ניסיון | Halide ספג ביקורת על מעבר למנוי אחרי רכישה. Filmic Pro איבד את מעמדו אחרי מכירה ל-Bending Spoons ומעבר למנוי. MotionCam: "40$ בלי ניסיון זה מטורף". Picsart ירדה מ-4.17 ל-2.52 כוכבים אחרי paywall | לחץ הכנסות | **רכישה חד-פעמית** עם ניסיון מלא. מנוי רק לשירותים שבאמת עולים כסף, כמו ענן. לא מוציאים לעולם פיצ'ר שנקנה אל מאחורי חומת תשלום |
| 9 | **אין עדשות אמיתיות**: "זום" הוא חיתוך דיגיטלי | Koshi מציג את המעבר בין .6×, 1× ו-3× כ"מחליף עדשות ולא חותך" כפיצ'ר מרכזי. בקשות issue בגרפין ("Zoom buttons like Google Camera", 23 תגובות) | מזהי המצלמות הפיזיות מוסתרים מאחורי מצלמה לוגית | גילוי אוטומטי של מזהי מצלמות לוגיות ופיזיות, כולל מזהים נסתרים (סעיף 3) |
| 10 | **כלי צילום חסרים או מזויפים**: היסטוגרמה מ-JPEG, אין זברה ואין false color על RAW | ב-VidUltra נפתח issue "No Manual Controls". Koshi מציג כפיצ'ר היסטוגרמה ו-focus aid | הכלים מחושבים על התמונה המעובדת ולא על הנתונים | היסטוגרמה, זברה ו-false color מחושבים **על ערכי ה-RAW הליניאריים**, כך שהם מראים קליפינג אמיתי של החיישן. Focus peaking ב-shader |

---

## 3. "לפצח את המערכת": מודל הגישה בשכבות

אנחנו ממפים כל מכשיר לרמת הגישה הגבוהה ביותר שהוא מאפשר בבטחה:

| רמה | מה זה | דוגמה מתועדת | סיכון | ב-v1? |
|---|---|---|---|---|
| L0 | Camera2 ציבורי: RAW, MANUAL_SENSOR, BURST | Redmi 13C: FULL, RAW ו-BURST, אבל רק 12MP | אפסי | כן |
| L1 | **מזהים נסתרים**: מצלמה לוגית או פיזית שלא מופיעה ב-`getCameraIdList()` | Nothing Phone (3): id 4 מעל חיישנים 0, 2 ו-3 | נמוך | כן |
| L2 | **מפתחות יצרן** (vendor tags) ב-session או ב-request | OPPO X9 Ultra: `com.oplus.is.sdk.camera.package=1`, `com.oplus.engineercamera.agingtest.mode.select` רק על still, מה שנותן 50MP. FreeDcam תיעד dumps של מפתחות נסתרים של Samsung ו-Xiaomi | בינוני: קריסות HAL | כן, לכל פרופיל, ובאישור המשתמש |
| L3 | **שער יצרן (gate)** דרך מחלקה נסתרת ב-reflection | vivo X300 Ultra ו-Pro: `VivoCameraManager`, מפתח 40, מה שנותן 50 ו-200MP | גבוה: **ערך שמשפיע על כל המערכת**. rawr מתעד את הערך הקודם ומשחזר אותו גם אחרי קריסה | כן, אבל אופציונלי, עם journaling ושחזור |
| L4 | root או ספרייה מתוקנת | OPPO 200MP | גבוה מאוד | **לא.** לא נוגעים בזה |

**איך זה נראה בפועל:**
- **Probe:** כלי שמריץ dump מלא של `CameraCharacteristics`, כולל מזהים לא רשומים (ניסיון פתיחה של 0 עד 15) ומפתחות יצרן שמופיעים ב-`getKeys()`. הכלי מייצר דוח בפורמט JSON.
- **פרופילי מכשירים:** קובצי JSON מגורסים, עם התאמה לפי דגם, ועם שדות `camera`, `streams`, `session`, `vendorKeys` ו-`workarounds`. הסכמה של rawr היא רפרנס טוב כ**רעיון**, אבל אנחנו כותבים מימוש משלנו בגלל הרישיון (סעיף 6).
- **תרומות קהילה:** משתמש מריץ Probe ושולח את הדוח. פרופיל עולה ל-"verified" רק אחרי בדיקת קבלה אוטומטית: צילום, DNG תקין וללא קריסה ב-100 צילומים.
- **מדיניות Google Play [לא אומת]:** צריך לבדוק אם reflection למחלקות יצרן (לא מחלקות framework של AOSP) עובר את ההגבלות של non-SDK interfaces ואת מדיניות החנות. תוכנית גיבוי: ברירת המחדל היא L0 עד L2, ו-L3 זמין רק בגרסה שמופצת מחוץ ל-Play או בהפעלה ידנית.

---

## 4. טבלת יכולות (ממקורות, לא ממדידה שלנו)

| מכשיר | מה ידוע | מקור |
|---|---|---|
| Redmi 13C 5G (Dimensity 6100+) | אחורית FULL עם RAW, BURST, MANUAL_SENSOR ו-MANUAL_POST_PROCESSING. RAW מוגבל ל-12MP למרות חיישן 50MP. קדמית LEVEL_3 בפוקוס קבוע. EV ±4 בצעדים של 1/6. כל 9 מצבי ה-AWB זמינים. מרחק פוקוס UNCALIBRATED | LumenRAW PLAN §3 ו-DEVLOG M2 (נמדד) |
| Nothing Phone (3) | מצלמה מרובה נסתרת ב-id 4 מעל 0, 2 ו-3. JPEG לוקח 30-100ms ו-DNG של 25MB לוקח כ-110ms | Koshi README (נמדד) |
| vivo X300 Ultra | 50MP ב-H14, H35 ו-H85 (מזהים 4, 3 ו-5) עם buffer של 8192×6144, דרך שער vivoFullSize. ה-provider קורס על בקשת full-size בלי מפתחות מסוימים. ROI ריק מפיל את ה-AF | rawr docs (נבדק על V2562) |
| vivo X300 Pro / X200 Ultra | פרופיל קיים. X200 Ultra דורש `keepTemplateRegions` | rawr profiles |
| OPPO Find X9 Ultra | 50MP Quad-Bayer ב-4 עדשות דרך מפתחות session ו-sensor mode. 200MP רק עם root | rawr oppo-fullres.md |
| Samsung Galaxy (A ו-S) | עדשות שאינן ראשיות לרוב LIMITED או נעולות. RAW_SENSOR לא אמין. Expert RAW הוא אפליקציה סגורה של Samsung. אין API ישיר לחיישן | פורומי Samsung ו-Android Authority |
| Xiaomi Mi 5 | זרם RAW שבור | FreeDcam README |
| Redmi Note 4 | גוון מג'נטה ב-DNG, כלומר מטריצה שגויה | FreeDcam #12 |

**מסקנה לגבי מכשירי יעד ל-v1:** vivo, OPPO ו-Xiaomi (הפתוחים ביותר), Pixel (רפרנס נקי של Camera2) ו-Nothing (קהילה צלמית). Samsung נכנס ב-L0 בלבד, עד שנמצא gate.

---

## 5. נוף המתחרים

| אפליקציה | חוזק | חולשה | רישיון |
|---|---|---|---|
| **MotionCam Pro** | מחליף את כל הצינור, מיזוג RAW, וידאו RAW, preview מ-RAW | מורכב, $40 בלי ניסיון, רגרסיות ביצועים, DNG בעייתיים | GPL-3 (גרסה ישנה), היום סגור |
| **Open Camera** | חינם, יציב, ידני ו-RAW | בלי מיזוג חישובי, ממשק ישן | GPL |
| **FreeDcam** | חושף הגדרות יצרן נסתרות, dumps של מפתחות | ממשק גולמי, כמעט לא מתוחזק | GPL-2 |
| **GCam ports** | איכות HDR+ ולילה | לא רשמי, תלוי מכשיר, OnePlus חסמה ב-2021 | קנייני (Google) |
| **Koshi** | עיבוד אפס, עדשות אמיתיות, מהיר מאוד | בלי מיזוג ובלי פילם אמיתי (רק `.cube`) | בלי רישיון |
| **rawr** (2026) | פתיחת 50 ו-200MP ב-vivo ו-OPPO, Vulkan, MFSR, פילם | "General device compatibility is not yet established", פרויקט מפתח | GPL-3 |
| **FILCamera** (2026) | spektrafilm על Vulkan, darkroom לא הרסני, אופליין מלא | נבדק על Xiaomi 15 Pro בלבד, preview מקורב | GPL-3 |
| **LumenRAW** (2026) | צינור HDR+ על Redmi | בשלבים מוקדמים | בלי רישיון |
| **Samsung Expert RAW** | RAW חישובי ועדשות טלה | Samsung בלבד, סגור | קנייני |
| **Adobe Project Indigo** | המתחרה המסוכן ביותר: צוות Levoy, צינור RAW חישובי ו-"Natural" | **iOS בלבד** נכון לנובמבר 2026 | קנייני |

**הפער:** כל רכיב קיים אצל מישהו, אבל אף אחד לא משלב **פתיחת חיישן + מיזוג + פילם ספקטרלי + preview תואם + UX של מוצר + מודל הוגן**. שלושת הפרויקטים החזקים מ-2026 (rawr, FILCamera, Koshi) הם פרויקטים של מפתח יחיד, כל אחד על מכשיר אחד או שניים.

---

## 6. החלטות טכנולוגיות והשלכות רישוי

### מחסנית מוצעת
| שכבה | בחירה | נימוק ומקור |
|---|---|---|
| צילום | Camera2 דרך NDK (`ACameraManager`), עם fallback ל-Java Camera2 | rawr משתמש ב-`cameraApi: auto/native/java` כי חלק מה-vendor keys עובדים רק ב-Java |
| עיבוד | C++17 עם Vulkan compute (shaders ב-GLSL ל-SPIR-V) | rawr ו-FILCamera, שניהם Vulkan 1.1+ |
| גנרטורים | Halide כאופציה ליישור ומיזוג | MotionCam ו-Handheld-SR משתמשים ב-Halide |
| יישור ומיזוג | Hasinoff 2016 (HDR+): פירמידה, tiles ומיזוג Wiener | timothybrooks/hdr-plus (MIT) |
| שחזור פרטים | Wronski 2019 (Handheld MFSR) | Jamy-L (MIT), כולל IPOL paper |
| טונים | Exposure fusion (Mertens) | MotionCam |
| צבע | ליניארי ב-ProPhoto או ACES AP1, ו-OpenColorIO לייצוא | FILCamera: ProPhoto ליניארי |
| פילם | סימולציה ספקטרלית: נגטיב, הדפסה, הילה, דיפוזיה וגרעין | spektrafilm ו-spectral_film_lut (MIT) |
| פלט | DNG (DngCreator ו-DNG SDK), JPEG XL ו-HEIF כאופציה | בקשת JPEG XL בגרפין (20 תגובות) |
| ממשק | Kotlin עם Jetpack Compose | FILCamera |

### השלכת רישוי: החלטה שלך (חוסמת)
- **ל-MIT מותר להשתמש בקוד:** hdr-plus, Handheld-SR ו-spectral_film_lut.
- **GPL-3 (rawr, FILCamera, MotionCam) ו-GPL-2 (FreeDcam):** אם מעתיקים קוד, **כל האפליקציה חייבת להיות GPL וקוד פתוח.** מפתחות יצרן, מזהי מצלמות ושמות מצבים הם **עובדות** שאפשר ללמוד מהן. המימוש נכתב מאפס.
- **spektrafilm:** את הרישיון של הפרויקט ושל נתוני הפילם **[לא אומת]**. FILCamera, שנגזר ממנו, מופץ תחת GPL-3.

---

## 7. איך נוכיח שהתוצאה טובה (פרוטוקול)

1. **מדדי מעבדה** על כל מכשיר יעד, מול המצלמה המובנית ומול MotionCam או GCam:
   - צבע: ColorChecker 24 ו-ΔE2000 ממוצע ומקסימלי.
   - חדות: slanted-edge ו-MTF50, ובדיקת הילות חידוד (overshoot).
   - רעש: SNR לכל ISO, ו-dark frames לפרופיל הרעש.
   - טווח דינמי: step wedge, בסטופים.
   - עיכוב תריס: וידאו 240fps של שעון LED, במילישניות.
   - זמן עיבוד, צריכת סוללה והתחממות: Perfetto, ובדיקה של 30 דקות רצופות.
2. **תקינות קבצים:** `dng_validate` ב-CI, פתיחה ב-LibRaw ו-darktable באופן אוטומטי, ובלייטרום ידנית.
3. **מבחן עיוור:** לפחות 20 צלמים, זוגות תמונות באקראי בלי לדעת את המקור, והעדפה ב-2AFC.
4. **בטא:** שימור ב-D7 וב-D30, ושיעור הקריסות לכל פרופיל.
5. **ביקורת חיצונית:** צלם מקצועי, ופוסט ב-r/AndroidCamera וב-pixls.us עם קובצי RAW להורדה.

---

## 8. הצעד הבא בקוד (מוצע, לא התחלתי)

**M0, CameraProbe:** אפליקציה מינימלית שמייצרת דוח JSON מלא:
- כל המזהים, כולל ניסיון לפתוח מזהים נסתרים.
- יכולות, גדלי RAW, CFA, רמות שחור ולבן ומטריצות צבע.
- מפתחות יצרן.
- צילום RAW בודד, עם מדידת זמן ובדיקת DNG.

זה החומר הגולמי האמיתי לטבלה שבסעיף 4. הדוחות של 5-10 המכשירים הראשונים יקבעו את פרופילי v1.

## 9. החלטות פתוחות

1. **רישיון:** קוד פתוח ב-GPL, שמאפשר ללמוד מקוד של rawr ו-FILCamera ולתרום חזרה, או קוד סגור, שבו כותבים מאפס ומשתמשים רק ב-MIT?
2. **מכשירי בדיקה:** אילו מכשירים יש לך פיזית? בלי מכשיר אי אפשר לעבור את M0.
3. **הפצה:** רק Play, Play ובנוסף APK עם L3, או F-Droid?
4. **Reddit:** אפשר לקבל מפתח Reddit API, או שתייצא שרשורים ידנית?

---

## מקורות

**קוד (נקרא ישירות):**
[adityawarmanfw/rawr](https://github.com/adityawarmanfw/rawr) (docs/vivo-fullres.md, oppo-fullres.md, camera-recipes.md) ·
[c52e/filcamera](https://github.com/c52e/filcamera) ·
[ghostyapps/Koshi-Camera](https://github.com/ghostyapps/Koshi-Camera) ·
[Kp4103/lumenraw-camera](https://github.com/Kp4103/lumenraw-camera) ·
[f0enix/motioncam](https://github.com/f0enix/motioncam) ·
[KillerInk/FreeDcam](https://github.com/KillerInk/FreeDcam) ·
[timothybrooks/hdr-plus](https://github.com/timothybrooks/hdr-plus) ·
[Jamy-L/Handheld-Multi-Frame-Super-Resolution](https://github.com/Jamy-L/Handheld-Multi-Frame-Super-Resolution) ·
[JanLohse/spectral_film_lut](https://github.com/JanLohse/spectral_film_lut) ·
[GrapheneOS/Camera issues](https://github.com/GrapheneOS/Camera/issues) (#14 RAW DNG, #87 zoom, #374 JPEG XL)

**מאמרים:**
Hasinoff et al., "Burst photography for high dynamic range and low-light imaging", SIGGRAPH Asia 2016 ·
Wronski et al., "Handheld Multi-Frame Super-Resolution", SIGGRAPH 2019 ·
Mertens et al., "Exposure Fusion", 2007

**רשת (דרך חיפוש):**
[Samsung community: Camera2API support](https://r1.community.samsung.com/t5/support/camera2api-support-for-all-devices/td-p/39042600) ·
[XDA: CameraX extensions](https://www.xda-developers.com/google-camerax-android-api-third-party-apps-best-features-stock-camera/amp/) ·
[Android Authority: Android 17 vendor-defined extensions](https://www.androidauthority.com/android-17-beta-3-vendor-defined-camera-extensions-3652506/) ·
[Gadget Hacks: Android 17 extensions](https://android.gadgethacks.com/news/android-17-camera-features-in-every-app-how-vendor-extensions-work/) ·
[Android Authority: S25 Ultra shutter lag](https://www.androidauthority.com/galaxy-s25-ultra-shutter-lag-annoying-3549120/) ·
[Moment: preview vs. photo](https://helpcenter.momentprocamera.com/articles/2919567-why-dont-my-photos-match-the-viewfinder) ·
[MotionCam Pro on Google Play](https://play.google.com/store/apps/details?id=com.motioncam.pro) ·
[shivjm.blog: MotionCam](https://shivjm.blog/motioncam-i-am-unworthy-of-thee/) ·
[DPReview: Motion Cam Pro](https://dpreview.com/forums/thread/4731969) ·
[9to5Mac: Project Indigo](https://9to5mac.com/2026/04/09/adobes-low-processing-camera-app-expands-support-to-select-ipads-and-the-iphone-17e/) ·
[9to5Google: Project Indigo](https://9to5google.com/2025/06/18/adobe-project-indigo-pixel-camera-app/) ·
[darktable: spektrafilm module](https://docs.darktable.org/usermanual/development/en/module-reference/processing-modules/spektrafilm/) ·
[Kimola: Halide feedback](https://kimola.com/reports/unlock-insights-halide-mark-ii-customer-feedback-report-app-store-us-147521) ·
[The App Whisperer: Filmic Pro](https://theappwhisperer.com/2026/04/filmic-pro-how-the-industrys-go-to-camera-app-lost-its-edge-overnight/) ·
[MWM: Picsart paywall](https://mwm.ai/articles/picsart-v29-9-1-moves-paid-features-behind-new-paywall-sparking-user-backlash-in-may-2026) ·
[9to5Mac: iPhone post-processing](https://9to5mac.com/2023/01/06/mkbhd-post-processing-ruining-iphone-photos/) ·
[PhoneArena: S23 Ultra](https://www.phonearena.com/news/galaxy-users-need-this-camera-fix-turn-your-galaxy-s23-ultra-into-camera-samsung-failed-to-make_id147848) ·
[Android Authority: CameraX RAW](https://www.androidauthority.com/jetpack-camerax-raw-photos-3498039/)
