// נכתב: 2026-11-02 22:01 (שעון ישראל)
@file:OptIn(ExperimentalMaterial3Api::class)

package com.yoeldru.hadpasot

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: MainViewModel) {
    if (vm.history.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("עוד לא הודפס כלום.", style = MaterialTheme.typography.titleMedium)
            Text(
                "כל הדפסה נשמרת כאן (עד 40 אחרונות), ואפשר להדפיס אותה שוב בלחיצה – עם אותן הגדרות.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val fmt = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(vm.history, key = { it.id }) { h ->
            Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (h.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null,
                        tint = if (h.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${fmt.format(Date(h.time))} · ${h.target} · ${h.pages} עמ' · ${h.sheets} דפים" +
                                (if (h.options.copies > 1) " · ${h.options.copies} עותקים" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (h.note.isNotBlank()) {
                            Text(
                                h.note, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                                color = if (h.ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    IconButton(onClick = { vm.reopen(h) }) { Icon(Icons.Filled.Print, "הדפס שוב") }
                    IconButton(onClick = { vm.deleteHistory(h) }) { Icon(Icons.Filled.Delete, "מחק") }
                }
            }
        }
        item {
            TextButton(onClick = { vm.clearHistory() }, modifier = Modifier.padding(vertical = 8.dp)) { Text("ניקוי כל ההיסטוריה") }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s = vm.settings
    val activity = LocalContext.current as? Activity
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {

        Section("שירות המרה – וורד, אקסל, פאוורפוינט", Icons.Filled.CloudSync, open = s.convertUrl.isBlank()) {
            Text(
                "קבצי אופיס מומרים ל-PDF דרך Google Drive שלך, בעזרת סקריפט קטן ב-Apps Script (חד-פעמי):\n" +
                    "1. לחץ \"העתק קוד\" למטה.\n" +
                    "2. היכנס ל-script.google.com ← פרויקט חדש, והדבק את הקוד במקום הקיים.\n" +
                    "3. בשורה const SECRET שנה את המילה לסיסמה משלך.\n" +
                    "4. בצד שמאל: שירותים (+) ← Drive API ← הוסף.\n" +
                    "5. פריסה ← פריסה חדשה ← אפליקציית אינטרנט. הפעלה בתור: אני. גישה: כל אחד. אשר הרשאות.\n" +
                    "6. העתק את כתובת ה-URL שמסתיימת ב-/exec והדבק כאן, יחד עם הסיסמה.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { vm.copyAppsScript() }, modifier = Modifier.padding(vertical = 6.dp)) { Text("העתק קוד Apps Script") }
            var url by remember { mutableStateOf(s.convertUrl) }
            var key by remember { mutableStateOf(s.convertKey) }
            OutlinedTextField(
                url, { url = it; vm.saveSettings(vm.settings.copy(convertUrl = it.trim())) },
                label = { Text("כתובת השירות (…/exec)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                key, { key = it; vm.saveSettings(vm.settings.copy(convertKey = it)) },
                label = { Text("סיסמה (SECRET)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { vm.testConverter() }, enabled = url.isNotBlank()) { Text("בדיקת חיבור") }
        }

        Section("איכות ושליחה למדפסת", Icons.Filled.Print) {
            Choice("רזולוציה מקסימלית למדפסת", listOf(150, 300, 600).map { it to "$it dpi" }, s.maxDpi) { v ->
                vm.saveSettings(vm.settings.copy(maxDpi = v))
            }
            Text(
                "300 מתאים כמעט לכל דבר. 600 – חד יותר אבל איטי וצורך זיכרון רב.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Choice("רזולוציה בשמירת PDF", listOf(150, 200, 300).map { it to "$it dpi" }, s.pdfDpi) { v ->
                vm.saveSettings(vm.settings.copy(pdfDpi = v))
            }
        }

        Section("דו-צדדי ידני", Icons.Filled.SwapVert) {
            Toggle(
                "הדפס את הצד השני בסדר הפוך", s.manualBackReverse,
                "מתאים לרוב המדפסות (הדף האחרון יוצא למעלה). אם הצד השני מודפס על הדפים הלא נכונים – כבה.",
            ) { v -> vm.saveSettings(vm.settings.copy(manualBackReverse = v)) }
            Toggle(
                "סובב את הצד השני ב-180°", s.manualBackRotate,
                "אם הצד השני יוצא הפוך (ראש למטה) – הדלק.",
            ) { v -> vm.saveSettings(vm.settings.copy(manualBackRotate = v)) }
        }

        Section("אודות ועדכונים", Icons.Filled.Info, open = vm.update != null) {
            Text("הדפסות · גרסה ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.Bold)
            val u = vm.update
            if (u != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Badge()
                    Spacer(Modifier.width(8.dp))
                    Text("יש גרסה חדשה: 1.${u.version}")
                }
                OutlinedButton(onClick = { activity?.let { vm.installUpdate(it) } }) { Text("עדכן עכשיו") }
            } else {
                TextButton(onClick = { vm.checkUpdate() }) { Text("בדוק עדכונים") }
            }
            OutlinedButton(onClick = { activity?.let { vm.shareApp(it) } }, modifier = Modifier.padding(top = 6.dp)) {
                Text("שתף את האפליקציה")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "האפליקציה מדברת ישירות עם המדפסת ברשת (IPP / AirPrint / Mopria / RAW 9100), בלי שרת ובלי חשבון.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
