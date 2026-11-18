package app.obscura.camera.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obscura.camera.camera.OutputFormat
import app.obscura.camera.ui.CameraActions
import app.obscura.camera.ui.CameraUiState
import app.obscura.camera.ui.Ob
import app.obscura.camera.ui.Txt
import app.obscura.camera.ui.ltr

/**
 * Settings: how the screen looks, what is saved, and, out of the way under "advanced", the
 * sensor report, the raw live data and the JSON export.
 */
@Composable
fun SettingsPanel(s: CameraUiState, a: CameraActions, version: String, modifier: Modifier = Modifier) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(Ob.Ink).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("הגדרות", Ob.LabelHe.copy(color = Ob.Ivory, fontSize = 14.sp))
            Spacer(Modifier.weight(1f))
            Txt("סגירה", Ob.LabelHe.copy(color = Ob.Ivory), Modifier.press(a::closeSettings).padding(8.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            item { Head("תצוגה") }
            item { Choice("רשת שלישים", yesNo, s.grid) { if (it != s.grid) a.toggleGrid() } }
            item { Choice("פלס", yesNo, s.level) { if (it != s.level) a.toggleLevel() } }
            item { Choice("היסטוגרמה", yesNo, s.histogramOn) { if (it != s.histogramOn) a.toggleHistogram() } }
            item { Choice("הצעות קומפוזיציה", yesNo, s.coachOn, "בועה שקופה עם רעיון לקומפוזיציה, פעם בכמה שניות.") { if (it != s.coachOn) a.toggleCoach() } }

            item { Head("תמונה") }
            item { Choice("רזולוציה", listOf("50MP" to true, "חיישן" to false), s.hiRes, "50MP: מיזוג פריימי RAW, פחות רעש ויותר פרטים.") { if (it != s.hiRes) a.toggleHiRes() } }
            item { Choice("פריימים למיזוג", listOf(4, 6, 8, 10, 12).map { "$it" to it }, s.burstFrames, "יותר פריימים: נקי יותר, איטי יותר.") { a.setBurstFrames(it) } }
            item { Choice("איכות JPEG", listOf(90, 96, 100).map { "$it" to it }, s.jpegQuality) { a.setJpegQuality(it) } }
            item { Choice("שמירת DNG", yesNo, s.format == OutputFormat.RAW_JPEG, "קובץ RAW לצד ה-JPEG, לעריכה ב-Lightroom.") { if (it != (s.format == OutputFormat.RAW_JPEG)) a.toggleFormat() } }
            item { Choice("עיבוד טבעי", yesNo, s.natural, "בלי חידוד מלאכותי והפחתת רעש אגרסיבית של המכשיר.") { if (it != s.natural) a.toggleNatural() } }

            item { Head("מתקדם") }
            item { Choice("הקלטת אבחון", yesNo, s.diag, "כל צילום פורטרט או מאקרו נשמר כ-zip בהורדות: כל הפריימים, כל נתוני המצלמה וכל שלבי הביניים. לשליחה לניתוח.") { if (it != s.diag) a.setDiag(it) } }
            if (s.diag) {
                item { Choice("פריימים מלאים", yesNo, s.diagFull, "נדרש לשחזור מלא של המאקרו. בערך 9MB לפריים.") { a.setDiagFull(it) } }
                item { Choice("RAW לכל פריים", yesNo, s.diagRaw, "ניסיוני: אם המכשיר מאפשר RAW לצד הזרם, נשמר DNG לכל פריים בסריקה.") { a.setDiagRaw(it) } }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("דוח חיישן", a::toggleSensor)
                    Spacer(Modifier.width(8.dp))
                    Pill("ייצוא JSON", a::exportReport)
                    Spacer(Modifier.width(8.dp))
                    Pill(if (advanced) "הסתר נתונים חיים" else "נתונים חיים") { advanced = !advanced }
                }
            }
            if (advanced) {
                item { Txt("כל מה שהמצלמה מדווחת על כל פריים · ${s.resultDump.size} ערכים", Ob.LabelHe.copy(color = Ob.Smoke, fontSize = 11.sp), Modifier.padding(horizontal = Ob.gutter, vertical = 6.dp)) }
                items(s.resultDump, key = { it.first }) { (k, v) ->
                    Ltr {
                        Row(Modifier.fillMaxWidth().padding(horizontal = Ob.gutter, vertical = 3.dp)) {
                            Txt(k.removePrefix("android."), Ob.Mono.copy(color = Ob.Ash, fontSize = 10.sp), Modifier.width(170.dp))
                            Txt(v, Ob.Mono.copy(color = Ob.Ivory, fontSize = 10.sp), Modifier.weight(1f))
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)); Txt(ltr("OBSCURA $version"), Ob.Mono.copy(color = Ob.Smoke), Modifier.padding(Ob.gutter)) }
        }
    }
}

private val yesNo = listOf("כן" to true, "לא" to false)

@Composable
private fun Head(t: String) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(Ob.Hairline))
        Spacer(Modifier.height(12.dp))
        Txt(t, Ob.LabelHe.copy(color = Ob.Brass), Modifier.padding(horizontal = Ob.gutter))
    }
}

@Composable
private fun Pill(t: String, onClick: () -> Unit) =
    Txt(t, Ob.LabelHe.copy(color = Ob.Ivory), Modifier.border(0.8.dp, Ob.HairlineStrong, RoundedCornerShape(50)).press(onClick).padding(horizontal = 14.dp, vertical = 9.dp))
