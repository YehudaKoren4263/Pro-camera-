# טכניקות של יצרנים אחרים: מה ייבאנו ואיך

תאריך: 2026-11-18 · גרסה 0.4.0

## העיקרון המשפטי
- **מימוש עצמאי בלבד.** כל טכניקה נבנתה מחדש מתיאור ציבורי: מאמר אקדמי, מדריך יצרן, ביקורת או פטנט שפורסם. לא הועתקו קוד, קבצי LUT, פרופילים או נכסים של אף חברה.
- **בלי סימנים מסחריים בממשק.** שמות כמו Classic Chrome, ‏Portra, ‏Velvia, ‏Kodachrome, ‏Live Composite, ‏Night Sight ו-Leica שייכים לבעליהם. באפליקציה כל מראה ומצב נושאים שם משלנו. ההשראה מתועדת כאן לשימוש פנימי בלבד.
- **פטנטים.** מימוש עצמאי לא מגן מפני פטנט. לפני הפצה מסחרית צריך בדיקת חופש פעולה (FTO) אצל עורך דין פטנטים, בעיקר למיזוג רצף (Google) ולקומפוזיט חי (OM Digital). זו לא ייעוץ משפטי.

## מפת הטכניקות

| טכניקה | אצל מי | מקור ציבורי | אצלנו | מצב |
|---|---|---|---|---|
| מיזוג רצף עמיד לתנועה | Google HDR+ / Night Sight | ‏[Hasinoff et al. 2016](https://research.google/pubs/burst-photography-for-high-dynamic-range-and-low-light-imaging-on-mobile-cameras/), ‏[Delbracio et al. 2021](https://arxiv.org/pdf/2102.09000) | מצב **לילה**: משקל לכל אריח ביחס לפריים ייחוס, אינטרפולציה בין אריחים | ✅ נבדק: רוח רפאים 6.1→1.5, רעש 19.5→5.8 |
| חשיפה ארוכה ופאנינג חישוביים | Google (Motion Mode) | ‏[Tabellion et al., TOG 2023](https://arxiv.org/pdf/2308.01379) | **חשיפה ארוכה**, **פאנינג** | ✅ (מילוי טשטוש בזרימה אופטית: הבא) |
| קומפוזיט חי, רק אור חדש | OM System Live Composite | ‏[OM System](https://omsystem.com/en/technology/computational/live_composite/), ‏[Fstoppers](https://fstoppers.com/gear/what-om-systems-live-view-live-composite-and-live-nd-and-how-use-them-668545) | **עקבות אור**, עם תצוגה מצטברת חיה על המסך | ✅ |
| ND חישובי עם מספר עצירות | OM System Live ND | ‏[OM System](https://omsystem.com/en/technology/computational/live_nd/) | מד **≈ NDx · עצירות** בחשיפה ארוכה | ✅ |
| מצבי צביעה באור (רכבים, גרפיטי, מים, כוכבים) | Huawei Light Painting | ‏[Huawei support](https://consumer.huawei.com/en/support/content/en-us15769097/) | עקבות אור, חשיפה ארוכה, שובל שביט | ✅ |
| שובלים דועכים (שביט) | StarStaX (comet mode) | ‏[StarStaX usage](https://markus-enzweiler.de/starstax-usage/) | **שובל שביט**: Lighten עם דעיכה לכל פריים | ✅ נבדק: ראש 255, זנב 75 |
| הסרת עוברים ושבים (חציון זמני) | Photoshop Stack Mode "Median" | ‏[Fstoppers](https://fstoppers.com/photoshop/how-quickly-and-easily-remove-people-crowded-photo-automatically-using-photoshop-595935), ‏[Paul Bourke](https://paulbourke.net/miscellaneous/removing_tourists) | **הסרת אנשים** על המכשיר: remedian בבסיס 3, תשעה פריימים מפוזרים בזמן | ✅ נבדק: שגיאה מקסימלית 5/255. **לא מצאנו אפליקציית טלפון שעושה את זה על המכשיר** |
| חשיפה מרובה במצלמה | Fujifilm, Nikon, Canon | מדריכי יצרן | **חשיפה כפולה** עם פריים ראשון שקוף בתצוגה | ✅ |
| הדמיות פילם | Fujifilm Film Simulations ועוד | ביקורות ותיאורים פומביים של האופי | 23 מראות ב-6 קטגוריות (טבלה למטה) | ✅ |
| בוקה סטריאו | Xiaomi / MediaTek VSDOF | Probe v2 מהמכשיר: מצלמה 5, הרחבת BOKEH של Camera2 | — | ⏭ הבא: דרך Camera2 Extensions (API ציבורי) |
| לילה של היצרן | Xiaomi | Probe v2: הרחבת NIGHT | — | ⏭ השוואה מול הלילה שלנו |
| 50MP | Xiaomi remosaic | Probe v2: ‏`xiaomi.remosaic.enabled`, ‏`com.mediatek.control.capture.remosaicenable` ניתנים לשליחה | — | 🧪 צריך ניסוי על המכשיר |
| Ultra HDR ‏/ 10 ביט | Google, אנדרואיד 14 | Probe v2: ‏`JPEG_R` ו-`YCBCR_P010` זמינים בכל המצלמות | — | ⏭ |

## מראות: השראה (פנימי, לא מופיע באפליקציה)

| שם אצלנו | אופי | השראה כללית |
|---|---|---|
| Medium Format | טבעי, גלגול היילייטים רך | מדע צבע של מצלמות מדיום פורמט |
| Ivory 400 | נגטיב פורטרטים פסטלי, עור חם | נגטיב צבע מקצועי לפורטרטים, ISO 400 |
| Amber 200 | נגטיב צרכני זהוב וגרעיני | נגטיב צבע צרכני, ISO 200 |
| Verde 400 | ירוקים קרירים, צללים ירקרקים | נגטיב צבע יומיומי יפני |
| Chroma 100 | רווי וחד | נגטיב צבע עדין גרעין ורווי |
| Pastel 400 | אוורירי, טורקיז | נגטיב מקצועי פסטלי (הופסק) |
| Slide 50 / Slide 100 | שקופית רוויה / מאוזנת | שקופיות E-6 |
| Chrome 64 | אדום חם, כחול עמוק | שקופית K-14 היסטורית |
| Reportage | רוויה נמוכה, טונים קשים | הדמיה דוקומנטרית של יצרן יפני |
| Street Neg | ניגוד קשה, צללים טורקיז | הדמיית נגטיב רחוב של יצרן יפני |
| Nostalgia | ענבר חם ורך | הדמיה נוסטלגית של יצרן יפני |
| Rangefinder | שחורים עמוקים, אדום עשיר, ויניט | מדע הצבע של מצלמות טווח-מדיד גרמניות |
| Positive | חי, צללים כחלחלים | מצב פוזיטיב של מצלמות קומפקט יפניות |
| Cinema Flat | שטוח וקולנועי | הדמיית פילם קולנוע |
| Tungsten 800 | איזון קר, אדום בוהק | פילם קולנוע טונגסטן מותאם לסטילס |
| Bleach / Cross | עקיפת הלבנה / פיתוח צולב | תהליכי מעבדה |
| Silver 400 / Mono Red / Noir / Selenium | שחור-לבן: קלאסי / פילטר אדום / עמוק / מוטון | פילם שחור-לבן, פילטרים, טונינג במעבדה |

## איכות המראות, במספרים (`LookEngineTest`)
- Neutral הוא זהות: סטייה של עד 1/255.
- אף מראה לא הופך את סולם האפורים.
- מראות שחור-לבן אפורים באמת (לפני טונינג).
- **גיוון:** כל זוג מראות נבדל לפחות ב-3/255 בממוצע על 512 צבעים. הזוג הקרוב ביותר כרגע הוא Slide 100 מול Positive, ‏5.1. הבדיקה כבר תפסה פעם אחת את Silver 400 ו-Selenium כמעט זהים (1.3), והם תוקנו.
