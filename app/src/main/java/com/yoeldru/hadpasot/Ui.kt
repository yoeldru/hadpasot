// נכתב: 2026-11-02 21:45 (שעון ישראל)
@file:OptIn(ExperimentalMaterial3Api::class)

package com.yoeldru.hadpasot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun App(vm: MainViewModel) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val titles = listOf("הדפסה", "מדפסות", "היסטוריה", "הגדרות")
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(titles[vm.tab], fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = vm.tab == 0, onClick = { vm.tab = 0 },
                        icon = { Icon(Icons.Filled.Print, null) }, label = { Text("הדפסה") },
                    )
                    NavigationBarItem(
                        selected = vm.tab == 1, onClick = { vm.tab = 1 },
                        icon = { Icon(Icons.Filled.Wifi, null) }, label = { Text("מדפסות") },
                    )
                    NavigationBarItem(
                        selected = vm.tab == 2, onClick = { vm.tab = 2 },
                        icon = { Icon(Icons.Filled.History, null) }, label = { Text("היסטוריה") },
                    )
                    NavigationBarItem(
                        selected = vm.tab == 3, onClick = { vm.tab = 3 },
                        icon = {
                            BadgedBox(badge = { if (vm.update != null) Badge() }) {
                                Icon(Icons.Filled.Settings, null)
                            }
                        },
                        label = { Text("הגדרות") },
                    )
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (vm.tab) {
                    0 -> PrintScreen(vm)
                    1 -> PrintersScreen(vm)
                    2 -> HistoryScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
        BusyDialog(vm)
        MessageDialog(vm)
        FlipDialog(vm)
        UpdateDialog(vm)
    }
}

@Composable
fun BusyDialog(vm: MainViewModel) {
    val msg = vm.busy ?: return
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Card {
            Column(Modifier.padding(24.dp).width(280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val p = vm.progress
                if (p == null) CircularProgressIndicator()
                else LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(16.dp))
                Text(msg, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { vm.cancelPrint(); vm.busy = null }) { Text("ביטול") }
            }
        }
    }
}

@Composable
fun MessageDialog(vm: MainViewModel) {
    val m = vm.message ?: return
    AlertDialog(
        onDismissRequest = { vm.message = null },
        icon = {
            Icon(
                if (m.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null,
                tint = if (m.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(m.title) },
        text = { if (m.text.isNotBlank()) Text(m.text) },
        confirmButton = { TextButton(onClick = { vm.message = null }) { Text("אישור") } },
    )
}

@Composable
fun FlipDialog(vm: MainViewModel) {
    val f = vm.flip ?: return
    val s = vm.settings
    AlertDialog(
        onDismissRequest = {},
        title = { Text("הפוך את הדפים") },
        text = {
            Text(
                "הצד הראשון נשלח (${f.sheets} דפים).\n\n" +
                    "1. חכה שכל הדפים יצאו מהמדפסת.\n" +
                    "2. הכנס את הערימה בחזרה למגש כשהצד המודפס " +
                    (if (s.manualBackRotate) "פונה כלפי מטה והחלק העליון של הדף פונה לכיוון המדפסת." else "פונה כלפי מטה, באותו כיוון שבו יצא.") +
                    "\n3. לחץ \"המשך\".\n\n" +
                    "אם הצד השני יוצא הפוך או בסדר שגוי – אפשר לשנות בהגדרות ← דו-צדדי ידני."
            )
        },
        confirmButton = { TextButton(onClick = { vm.continueFlip() }) { Text("המשך – הדפס צד שני") } },
        dismissButton = { TextButton(onClick = { vm.cancelFlip() }) { Text("ביטול") } },
    )
}

@Composable
fun UpdateDialog(vm: MainViewModel) {
    val u = vm.update ?: return
    if (!vm.showUpdateDialog) return
    val activity = LocalContext.current as? android.app.Activity
    AlertDialog(
        onDismissRequest = { vm.dismissUpdate() },
        title = { Text("יש גרסה חדשה (1.${u.version})") },
        text = { Text(if (u.notes.isNotBlank()) u.notes.take(600) else "לחץ \"עדכן\" כדי להוריד ולהתקין את הגרסה החדשה.") },
        confirmButton = {
            TextButton(onClick = { activity?.let { vm.openUrl(it, u.url) }; vm.dismissUpdate() }) { Text("עדכן") }
        },
        dismissButton = { TextButton(onClick = { vm.dismissUpdate() }) { Text("אחר כך") } },
    )
}

// ---------- רכיבים משותפים ----------

@Composable
fun <T> Choice(label: String, items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items.forEach { (v, t) ->
                FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(t) })
            }
        }
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { if (value > range.first) onChange(value - 1) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { if (value < range.last) onChange(value + 1) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
fun NumberField(label: String, value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(value) { mutableStateOf(if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            t.replace(',', '.').toFloatOrNull()?.let { onChange(it) } ?: if (t.isBlank()) onChange(0f) else Unit
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun Section(title: String, icon: ImageVector, open: Boolean = false, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(open) }
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) { content() }
        }
    }
}

@Composable
fun SmallIconButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc) }
}
