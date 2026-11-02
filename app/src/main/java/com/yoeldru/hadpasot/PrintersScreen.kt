// נכתב: 2026-11-02 21:56 (שעון ישראל)
@file:OptIn(ExperimentalMaterial3Api::class)

package com.yoeldru.hadpasot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PrintersScreen(vm: MainViewModel) {
    var adding by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<PrinterInfo?>(null) }
    var editing by remember { mutableStateOf<PrinterInfo?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (vm.scanning) vm.stopScan() else vm.startScan() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp))
                Text(if (vm.scanning) "עצור סריקה" else "סרוק את הרשת")
            }
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("הוסף לפי IP")
            }
        }

        if (vm.scanning || vm.found.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("נמצאו ברשת", style = MaterialTheme.typography.titleMedium)
            if (vm.scanning) {
                LinearProgressIndicator(progress = { vm.scanProgress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("מחפש מדפסות (Bonjour + סריקת כתובות)…", style = MaterialTheme.typography.bodySmall)
            }
            vm.found.forEach { f ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.Bold)
                            Text(
                                "${f.host}:${f.port} · ${f.protocol.label}" + (if (f.model.isNotBlank() && f.model != f.name) " · ${f.model}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { vm.addFound(f) }) { Text("הוסף") }
                    }
                }
            }
            if (!vm.scanning && vm.found.isEmpty()) {
                Text("לא נמצאו מדפסות חדשות. ודא שהטלפון מחובר לאותה רשת Wi-Fi כמו המדפסת, או הוסף לפי IP.")
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("המדפסות שלי", style = MaterialTheme.typography.titleMedium)
        if (vm.printers.isEmpty()) {
            Text(
                "עוד אין מדפסות. לחץ \"סרוק את הרשת\" – רוב המדפסות הביתיות והמשרדיות (HP, Canon, Epson, Brother, Xerox…) נמצאות אוטומטית.",
                modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        vm.printers.forEach { p ->
            PrinterCard(vm, p, onDetails = { details = p; vm.refreshCaps(p) }, onEdit = { editing = p })
        }
    }

    if (adding) AddPrinterDialog(vm, null) { adding = false }
    editing?.let { e -> AddPrinterDialog(vm, e) { editing = null } }
    details?.let { d -> PrinterDetailsDialog(vm, d) { details = null } }
}

@Composable
private fun PrinterCard(vm: MainViewModel, p: PrinterInfo, onDetails: () -> Unit, onEdit: () -> Unit) {
    val c = vm.caps[p.id]
    val err = vm.capsError[p.id]
    val isDefault = vm.target == p.id
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Print, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.Bold)
                    Text("${p.protocol.label} · ${p.address}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { vm.selectTarget(p.id) }) {
                    Icon(if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder, "ברירת מחדל",
                        tint = if (isDefault) Color(0xFFF5B400) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val status = when {
                err != null -> err
                c != null -> IppText.state(c.state) + c.reasons.filter { it != "none" }.take(3)
                    .joinToString("") { " · " + IppText.reason(it) }
                else -> null
            }
            if (status != null) {
                Text(
                    status, style = MaterialTheme.typography.bodySmall,
                    color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (c != null && c.markers.isNotEmpty()) InkLevels(c.markers)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { vm.refreshCaps(p) }) { Icon(Icons.Filled.Refresh, "רענן") }
                IconButton(onClick = onDetails) { Icon(Icons.Filled.Info, "פרטים") }
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "עריכה") }
                TextButton(onClick = { vm.printTestPage(p) }) { Text("דף ניסיון") }
            }
        }
    }
}

@Composable
fun InkLevels(markers: List<Marker>) {
    Column(Modifier.padding(top = 8.dp)) {
        markers.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Text(m.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(110.dp), maxLines = 1)
                Box(
                    Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (m.level >= 0) {
                        Box(
                            Modifier.fillMaxWidth(m.level.coerceIn(0, 100) / 100f).fillMaxHeight()
                                .background(m.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary),
                        )
                    }
                }
                Text(
                    if (m.level >= 0) "${m.level}%" else "?",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp).padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AddPrinterDialog(vm: MainViewModel, edit: PrinterInfo?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(edit?.name ?: "") }
    var host by remember { mutableStateOf(edit?.host ?: "192.168.1.") }
    var protocol by remember { mutableStateOf(edit?.protocol ?: Protocol.IPP) }
    var port by remember { mutableStateOf((edit?.port ?: 631).toString()) }
    var path by remember { mutableStateOf(edit?.path ?: "") }
    var format by remember { mutableStateOf(edit?.format ?: "auto") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (edit == null) "הוספת מדפסת" else "עריכת מדפסת") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("שם (לא חובה)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    host, { host = it }, label = { Text("כתובת IP או שם") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
                )
                Choice("פרוטוקול", Protocol.entries.map { it to it.label }, protocol) { v ->
                    protocol = v
                    port = when (v) { Protocol.RAW -> "9100"; else -> if (port == "9100") "631" else port }
                }
                OutlinedTextField(
                    port, { port = it.filter { ch -> ch.isDigit() } }, label = { Text("פורט") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                if (protocol != Protocol.RAW) {
                    OutlinedTextField(
                        path, { path = it }, label = { Text("נתיב (ריק = זיהוי אוטומטי)") }, placeholder = { Text("ipp/print") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (edit != null && protocol != Protocol.RAW) {
                    Choice(
                        "פורמט שליחה",
                        listOf("auto", "image/pwg-raster", "image/urf", "application/pdf", "image/jpeg").map { it to IppText.format(it) },
                        format,
                    ) { format = it }
                    Text(
                        "אם ההדפסה יוצאת משובשת או לא יוצאת – נסה פורמט אחר.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank(),
                onClick = {
                    val prt = port.toIntOrNull() ?: if (protocol == Protocol.RAW) 9100 else 631
                    if (edit == null) {
                        vm.addManual(name, host, prt, path, protocol)
                    } else {
                        vm.updatePrinter(
                            edit.copy(
                                name = name.ifBlank { edit.name }, host = host.trim(), port = prt,
                                path = path.trim().trimStart('/'), protocol = protocol, format = format,
                            )
                        )
                        vm.caps.remove(edit.id)
                    }
                    onClose()
                },
            ) { Text(if (edit == null) "בדוק והוסף" else "שמור") }
        },
        dismissButton = {
            Row {
                if (edit != null) TextButton(onClick = { vm.removePrinter(edit); onClose() }) {
                    Icon(Icons.Filled.Delete, null); Text("מחק")
                }
                TextButton(onClick = onClose) { Text("ביטול") }
            }
        },
    )
}

@Composable
private fun PrinterDetailsDialog(vm: MainViewModel, p: PrinterInfo, onClose: () -> Unit) {
    val c = vm.caps[p.id]
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(p.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DetailRow("כתובת", "${p.protocol.label} · ${p.address}")
                if (c == null) {
                    Text(
                        vm.capsError[p.id] ?: if (p.protocol == Protocol.RAW) "חיבור RAW – אין מידע מהמדפסת" else "טוען פרטים…",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    if (c.model.isNotBlank()) DetailRow("דגם", c.model)
                    DetailRow("מצב", IppText.state(c.state))
                    DetailRow("הודעות", c.reasons.joinToString(", ") { IppText.reason(it) }.ifBlank { "אין" })
                    DetailRow("דו-צדדי", if (c.canDuplex) "כן" else "לא (יבוצע ידנית)")
                    DetailRow("צבע", if (c.canColor) "כן" else "לא")
                    DetailRow("פורמטים", c.formats.joinToString(", ") { IppText.format(it) })
                    DetailRow("פורמט שנבחר", IppText.format(if (p.format == "auto") Engine.chooseFormat(c, "auto") else p.format))
                    if (c.pwgRes.isNotEmpty()) DetailRow("רזולוציות", c.pwgRes.distinct().joinToString(", ") + " dpi")
                    if (c.copiesMax > 1) DetailRow("עותקים במדפסת", "עד ${c.copiesMax}")
                    DetailRow("גדלי נייר", c.media.size.toString())
                    if (c.markers.isNotEmpty()) {
                        Text("מפלסי דיו / טונר", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        InkLevels(c.markers)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("סגור") } },
        dismissButton = { TextButton(onClick = { vm.printTestPage(p); onClose() }) { Text("דף ניסיון") } },
    )
}

@Composable
private fun DetailRow(k: String, v: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$k: ", fontWeight = FontWeight.Bold)
        Text(v)
    }
}
