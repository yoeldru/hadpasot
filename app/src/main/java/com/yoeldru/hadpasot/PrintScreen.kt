// נכתב: 2026-11-02 21:50 (שעון ישראל)
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.yoeldru.hadpasot

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun PrintScreen(vm: MainViewModel) {
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.openUris(uris, append = vm.job != null)
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        vm.openUris(uris, append = vm.job != null)
    }
    val openFiles = { pickFiles.launch(arrayOf("*/*")) }
    val openImages = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    if (vm.job == null) EmptyPrint(vm, openFiles, openImages) else JobView(vm, openFiles, openImages)
}

@Composable
private fun EmptyPrint(vm: MainViewModel, openFiles: () -> Unit, openImages: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(20.dp))
        Icon(Icons.Filled.Print, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text("מה מדפיסים היום?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "PDF, וורד, אקסל, פאוורפוינט, תמונות וטקסט.\nאפשר גם לשתף קובץ לכאן מוואטסאפ, מייל או דרייב.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = openFiles, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("בחירת קבצים")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = openImages, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Filled.Image, null); Spacer(Modifier.width(8.dp)); Text("בחירת תמונות מהגלריה")
        }
        if (vm.printers.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("עוד לא הוגדרה מדפסת", fontWeight = FontWeight.Bold)
                    Text("עבור ללשונית \"מדפסות\" כדי לסרוק את הרשת או להוסיף מדפסת לפי כתובת IP.")
                    TextButton(onClick = { vm.tab = 1 }) { Text("להוספת מדפסת") }
                }
            }
        }
        if (vm.history.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("הודפסו לאחרונה", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            vm.history.take(4).forEach { h ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.reopen(h) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Description, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("הדפס שוב", color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun JobView(vm: MainViewModel, openFiles: () -> Unit, openImages: () -> Unit) {
    val j = vm.job ?: return
    val layout = vm.layout
    val o = vm.options
    val activity = LocalContext.current as? Activity
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.savePdf(uri)
    }
    val isPdfTarget = vm.printers.none { it.id == vm.target }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            FilesCard(vm, j, openFiles, openImages)
            if (layout != null) PreviewCard(vm, layout)
            TargetCard(vm)
            PresetsRow(vm)
            OptionsSections(vm, o, layout)
            Spacer(Modifier.height(12.dp))
        }
        Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (isPdfTarget) savePdf.launch(j.title.substringBeforeLast('.') + ".pdf") else vm.printNow()
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Icon(if (isPdfTarget) Icons.Filled.Save else Icons.Filled.Print, null)
                    Spacer(Modifier.width(8.dp))
                    val sheets = layout?.sheetCount ?: 0
                    val copies = o.copies
                    Text(
                        (if (isPdfTarget) "שמור PDF" else "הדפס") +
                            if (sheets > 0) " · ${sheets * copies} דפים" else "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "עוד") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("שמירה כ-PDF") }, leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null) },
                            onClick = { menu = false; savePdf.launch(j.title.substringBeforeLast('.') + ".pdf") },
                        )
                        DropdownMenuItem(
                            text = { Text("שיתוף PDF") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { menu = false; activity?.let { vm.sharePdf(it) } },
                        )
                        DropdownMenuItem(
                            text = { Text("הדפסה דרך אנדרואיד (Mopria/יצרן)") }, leadingIcon = { Icon(Icons.Filled.Print, null) },
                            onClick = { menu = false; activity?.let { vm.systemPrint(it) } },
                        )
                        DropdownMenuItem(
                            text = { Text("סגירת העבודה") }, leadingIcon = { Icon(Icons.Filled.Close, null) },
                            onClick = { menu = false; vm.closeCurrent() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilesCard(vm: MainViewModel, j: LoadedJob, openFiles: () -> Unit, openImages: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            j.files.forEachIndexed { i, f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (f.kind) {
                            Kind.PDF -> Icons.Filled.PictureAsPdf
                            Kind.IMAGE -> Icons.Filled.Image
                            else -> Icons.Filled.Description
                        },
                        null, tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val pc = j.docs.getOrNull(i)?.pageCount ?: 0
                        Text(
                            "$pc עמודים" + if (f.kind == Kind.OFFICE) " · הומר ל-PDF" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (j.files.size > 1) {
                        IconButton(onClick = { vm.moveFile(i, -1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, "למעלה") }
                        IconButton(onClick = { vm.moveFile(i, 1) }, enabled = i < j.files.size - 1) { Icon(Icons.Filled.ArrowDownward, "למטה") }
                    }
                    IconButton(onClick = { vm.removeFile(i) }) { Icon(Icons.Filled.Delete, "הסר") }
                }
            }
            Row {
                TextButton(onClick = openFiles) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("הוסף קובץ") }
                TextButton(onClick = openImages) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(4.dp)); Text("הוסף תמונות") }
            }
        }
    }
}

@Composable
private fun PreviewCard(vm: MainViewModel, layout: Layout) {
    val count = layout.sides.size
    val state = rememberPagerState(pageCount = { count })
    var zoom by remember { mutableStateOf<Int?>(null) }
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(vertical = 10.dp)) {
            HorizontalPager(
                state = state,
                modifier = Modifier.fillMaxWidth().height(360.dp),
                contentPadding = PaddingValues(horizontal = 36.dp),
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1,
            ) { i ->
                SideImage(vm, i, 720, Modifier.fillMaxSize().clickable { zoom = i })
            }
            val side = layout.sides.getOrNull(state.currentPage)
            val label = buildString {
                if (layout.duplex && side != null) {
                    append("גיליון ${side.sheet + 1} מתוך ${layout.sheetCount} · ")
                    append(if (side.isBack) "צד אחורי" else "צד קדמי")
                } else {
                    append("דף ${state.currentPage + 1} מתוך $count")
                }
            }
            Text(label, Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            val paper = if (layout.duplex) layout.sheetCount else layout.sides.size
            Text(
                "${layout.selectedPages} עמודים מתוך ${layout.totalPages} · $paper דפי נייר" +
                    (if (layout.booklet) " · חוברת" else "") + " · הקש להגדלה",
                Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    zoom?.let { ZoomDialog(vm, it) { zoom = null } }
}

@Composable
fun SideImage(vm: MainViewModel, index: Int, widthPx: Int, modifier: Modifier) {
    val key = vm.previewKey(index) + "/" + widthPx
    var bmp by remember(key) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        val b = try { vm.preview(index, widthPx) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
        if (b == null) failed = true else bmp = b
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        when {
            b != null -> Image(
                bitmap = b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.aspectRatio(b.width.toFloat() / b.height).shadow(4.dp).background(Color.White),
            )
            failed -> Text("לא ניתן להציג", color = MaterialTheme.colorScheme.error)
            else -> CircularProgressIndicator()
        }
    }
}

@Composable
private fun ZoomDialog(vm: MainViewModel, index: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color(0xEE111111))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, z, _ ->
                        scale = (scale * z).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    }
                },
        ) {
            SideImage(
                vm, index, 1600,
                Modifier.fillMaxSize().padding(12.dp).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Filled.Close, "סגור", tint = Color.White)
            }
        }
    }
}

@Composable
private fun TargetCard(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    val cur = vm.printers.firstOrNull { it.id == vm.target }
    LaunchedEffect(cur?.id) { if (cur != null && vm.caps[cur.id] == null) vm.refreshCaps(cur) }
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("יעד", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (cur == null) Icons.Filled.PictureAsPdf else Icons.Filled.Print, null)
                    Spacer(Modifier.width(8.dp))
                    Text(cur?.name ?: "שמירה כקובץ PDF", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    vm.printers.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) }, leadingIcon = { Icon(Icons.Filled.Print, null) },
                            onClick = { vm.selectTarget(p.id); open = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("שמירה כקובץ PDF") }, leadingIcon = { Icon(Icons.Filled.PictureAsPdf, null) },
                        onClick = { vm.selectTarget("pdf"); open = false },
                    )
                    DropdownMenuItem(
                        text = { Text("הוספת מדפסת…") }, leadingIcon = { Icon(Icons.Filled.Add, null) },
                        onClick = { open = false; vm.tab = 1 },
                    )
                }
            }
            if (cur != null) {
                val c = vm.caps[cur.id]
                val err = vm.capsError[cur.id]
                val line = when {
                    err != null -> err
                    c != null -> listOfNotNull(
                        IppText.state(c.state),
                        if (c.canDuplex) "דו-צדדי ✓" else "ללא דו-צדדי (ידני)",
                        if (c.canColor) "צבע ✓" else "שחור-לבן",
                        c.reasons.filter { it != "none" }.take(2).joinToString(", ") { IppText.reason(it) }.ifBlank { null },
                    ).joinToString(" · ")
                    cur.protocol == Protocol.RAW -> "חיבור RAW ישיר (פורט ${cur.port})"
                    else -> "בודק מצב מדפסת…"
                }
                Text(
                    line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
                    color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PresetsRow(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Bookmark, null); Spacer(Modifier.width(6.dp)); Text("הגדרות שמורות")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                vm.presets.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { vm.applyPreset(p); open = false },
                        trailingIcon = {
                            IconButton(onClick = { vm.deletePreset(p) }) { Icon(Icons.Filled.Delete, "מחק") }
                        },
                    )
                }
            }
        }
        OutlinedButton(onClick = { saving = true }) { Icon(Icons.Filled.Save, null); Spacer(Modifier.width(6.dp)); Text("שמור") }
    }
    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("שמירת ההגדרות הנוכחיות") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("שם (למשל: מבחן כיתתי)") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.savePreset(name.trim()); saving = false }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("ביטול") } },
        )
    }
}

@Composable
private fun OptionsSections(vm: MainViewModel, o: PrintOptions, layout: Layout?) {
    Section("בסיסי", Icons.Filled.Tune, open = true) {
        Stepper("עותקים", o.copies, 1..999) { v -> vm.setOptions { it.copy(copies = v) } }
        if (o.copies > 1) {
            Toggle("איסוף עותקים", o.collate, "1,2,3 · 1,2,3 במקום 1,1 · 2,2 · 3,3") { v -> vm.setOptions { it.copy(collate = v) } }
        }
        var range by remember(o.range) { mutableStateOf(o.range) }
        OutlinedTextField(
            value = range,
            onValueChange = { t -> range = t; vm.setOptions { it.copy(range = t) } },
            label = { Text("עמודים (למשל 1-3, 5, 8-)") },
            placeholder = { Text("הכל") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Choice("אילו עמודים", PageSubset.entries.map { it to it.label }, o.subset) { v -> vm.setOptions { it.copy(subset = v) } }
        Choice("צבע", ColorMode.entries.map { it to it.label }, o.color) { v -> vm.setOptions { it.copy(color = v) } }
        Choice("דו-צדדי", Duplex.entries.map { it to it.label }, if (o.booklet) Duplex.SHORT_EDGE else o.duplex) { v ->
            vm.setOptions { it.copy(duplex = v, booklet = false) }
        }
        if (layout != null && layout.sides.any { it.landscape } && o.duplex == Duplex.LONG_EDGE && !o.booklet) {
            Text(
                "טיפ: לדפים לרוחב (למשל 2 בדף) בדרך כלל מתאים \"היפוך בצד הקצר\".",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
    Section("פריסה ועמודים מרובים", Icons.Filled.GridView) {
        Choice("גודל נייר", PaperSize.entries.map { it to it.label }, o.paper) { v -> vm.setOptions { it.copy(paper = v) } }
        Choice("כיוון", Orientation.entries.map { it to it.label }, o.orientation) { v -> vm.setOptions { it.copy(orientation = v) } }
        if (!o.booklet) {
            Choice("עמודים בכל צד של דף", listOf(1, 2, 4, 6, 9, 16).map { it to it.toString() }, o.pagesPerSheet) { v ->
                vm.setOptions { it.copy(pagesPerSheet = v) }
            }
            if (o.pagesPerSheet > 1) {
                Choice("סדר העמודים", NUpOrder.entries.map { it to it.label }, o.nupOrder) { v -> vm.setOptions { it.copy(nupOrder = v) } }
                Toggle("מסגרת סביב כל עמוד", o.borders) { v -> vm.setOptions { it.copy(borders = v) } }
            } else {
                Choice("קנה מידה", ScaleMode.entries.map { it to it.label }, o.scale) { v -> vm.setOptions { it.copy(scale = v) } }
                if (o.scale == ScaleMode.CUSTOM) {
                    Stepper("אחוז הגדלה", o.customScale, 10..400) { v -> vm.setOptions { it.copy(customScale = v) } }
                }
            }
        }
        NumberField("שוליים (מ\"מ)", o.marginMm, { v -> vm.setOptions { it.copy(marginMm = v.coerceIn(0f, 50f)) } }, Modifier.fillMaxWidth())
        Toggle("סיבוב אוטומטי של עמודים", o.autoRotate, "מסובב עמוד לרוחב כדי שיתאים טוב יותר") { v -> vm.setOptions { it.copy(autoRotate = v) } }
        Toggle("סדר הפוך (מהסוף להתחלה)", o.reverse) { v -> vm.setOptions { it.copy(reverse = v) } }
    }
    Section("חוברת ודו-צדדי", Icons.Filled.AutoStories) {
        Toggle("הדפסה כחוברת", o.booklet, "מקפלים באמצע ומקבלים חוברת. מודפס דו-צדדי בצד הקצר.") { v ->
            vm.setOptions { it.copy(booklet = v) }
        }
        if (o.booklet) {
            Choice("כריכה", Binding.entries.map { it to it.label }, o.binding) { v -> vm.setOptions { it.copy(binding = v) } }
            Stepper("גיליונות בכל קונטרס (0 = הכל יחד)", o.signatureSheets, 0..30) { v -> vm.setOptions { it.copy(signatureSheets = v) } }
            Text(
                "לחוברת עבה: מחלקים לקונטרסים של 5-8 גיליונות, כל קונטרס מקופל בנפרד ואז כורכים יחד.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NumberField("רווח בקיפול (מ\"מ)", o.gutterMm, { v -> vm.setOptions { it.copy(gutterMm = v.coerceIn(0f, 40f)) } }, Modifier.fillMaxWidth())
        }
        if (o.booklet || o.duplex != Duplex.NONE) {
            Choice("מי הופך את הדף", DuplexMethod.entries.map { it to it.label }, o.duplexMethod) { v ->
                vm.setOptions { it.copy(duplexMethod = v) }
            }
            Text(
                "אוטומטי: אם המדפסת יודעת להדפיס דו-צדדי היא תעשה זאת, ואם לא – האפליקציה תדפיס צד אחד, תבקש ממך להפוך את הערימה ותדפיס את הצד השני.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Section("מתקדם", Icons.Filled.ContentCopy) {
        Choice("איכות", Quality.entries.map { it to it.label }, o.quality) { v -> vm.setOptions { it.copy(quality = v) } }
        Toggle("מספרי עמודים", o.pageNumbers, "מוסיף את מספר העמוד בתחתית כל עמוד") { v -> vm.setOptions { it.copy(pageNumbers = v) } }
        var wm by remember(o.watermark) { mutableStateOf(o.watermark) }
        OutlinedTextField(
            value = wm, onValueChange = { t -> wm = t; vm.setOptions { it.copy(watermark = t) } },
            label = { Text("סימן מים (למשל: טיוטה, העתק)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(onClick = { vm.saveDefaultOptions() }) { Text("קבע כברירת מחדל") }
            TextButton(onClick = { vm.resetOptions() }) { Text("איפוס") }
        }
    }
}
