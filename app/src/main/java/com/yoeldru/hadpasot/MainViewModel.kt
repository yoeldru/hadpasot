// נכתב: 2026-11-02 21:36 (שעון ישראל)
package com.yoeldru.hadpasot

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToInt

class LoadedJob(val id: String, val dir: File, val files: List<JobFile>, val docs: List<PageDoc>, val version: Int) {
    val title: String
        get() = when {
            files.isEmpty() -> "עבודת הדפסה"
            files.size == 1 -> files[0].name
            else -> "${files[0].name} ועוד ${files.size - 1}"
        }
}

class FlipRequest(val sheets: Int, val next: suspend (Reporter) -> PrintResult)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>()
    private val store = Store(app)

    var tab by mutableIntStateOf(0)
    var settings by mutableStateOf(store.settings()); private set
    var printers by mutableStateOf(store.printers()); private set
    var presets by mutableStateOf(store.presets()); private set
    var history by mutableStateOf(store.history()); private set
    var options by mutableStateOf(store.defaultOptions()); private set
    var target by mutableStateOf(store.target()); private set
    var job by mutableStateOf<LoadedJob?>(null); private set

    var busy by mutableStateOf<String?>(null)
    var progress by mutableStateOf<Float?>(null)
    var message by mutableStateOf<Msg?>(null)
    var flip by mutableStateOf<FlipRequest?>(null)
    var update by mutableStateOf<Updater.Info?>(null)
    var showUpdateDialog by mutableStateOf(false)

    val caps = mutableStateMapOf<String, PrinterCaps>()
    val capsError = mutableStateMapOf<String, String>()
    val found = mutableStateListOf<FoundPrinter>()
    var scanning by mutableStateOf(false)
    var scanProgress by mutableStateOf(0f)

    private var printJob: Job? = null
    private var scanJob: Job? = null
    private var lastIpp: Pair<IppClient, Int>? = null

    val layout: Layout? by derivedStateOf {
        val j = job
        if (j == null) null else runCatching { Imposer.build(j.docs, options) }.getOrNull()
    }

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    init {
        cleanupOrphans()
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { Updater.check() }.getOrNull() }
            update = info
            if (info != null && store.seenUpdate() < info.version) showUpdateDialog = true
        }
    }

    // ---------- עבודה וקבצים ----------

    private fun reporter(): Reporter = { msg, p -> busy = msg; progress = p }

    private fun fail(e: Throwable) {
        message = Msg("שגיאה", e.message ?: e.toString(), ok = false)
    }

    fun openUris(uris: List<Uri>, append: Boolean = false) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            busy = "טוען קבצים…"; progress = null
            val errors = ArrayList<String>()
            try {
                val base = if (append) job else null
                val id = base?.id ?: newId()
                val dir = base?.dir ?: File(ctx.filesDir, "jobs/$id").apply { mkdirs() }
                val files = base?.files?.toMutableList() ?: mutableListOf()
                val docs = base?.docs?.toMutableList() ?: mutableListOf()
                for (u in uris) {
                    try {
                        val jf = withContext(Dispatchers.IO) { copyIn(u, dir) }
                        busy = if (jf.kind == Kind.OFFICE) "ממיר את ${jf.name} ל-PDF…" else "פותח ${jf.name}…"
                        val d = withContext(Dispatchers.IO) { DocLoader.open(jf, settings) }
                        files.add(jf); docs.add(d)
                    } catch (e: Exception) {
                        errors.add(e.message ?: "שגיאה בקובץ")
                    }
                }
                if (files.isEmpty()) {
                    if (!append) dir.deleteRecursively()
                } else {
                    if (!append) {
                        job?.let { closeJob(it) }
                        options = store.defaultOptions()
                    }
                    job = LoadedJob(id, dir, files, docs, (base?.version ?: 0) + 1)
                    tab = 0
                }
            } catch (e: Exception) {
                errors.add(e.message ?: e.toString())
            } finally {
                busy = null
            }
            if (errors.isNotEmpty()) message = Msg("לא כל הקבצים נפתחו", errors.joinToString("\n"), ok = false)
        }
    }

    fun openText(text: String) {
        viewModelScope.launch {
            val f = File(ctx.cacheDir, "shared-text.txt")
            withContext(Dispatchers.IO) { f.writeText(text) }
            openUris(listOf(Uri.fromFile(f)))
        }
    }

    private fun copyIn(u: Uri, dir: File): JobFile {
        val cr = ctx.contentResolver
        var name: String? = null
        if (u.scheme == "content") {
            runCatching {
                cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
            }
        } else {
            name = u.lastPathSegment
        }
        val mime = if (u.scheme == "content") cr.getType(u) else null
        var clean = (name ?: "קובץ").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (!clean.contains('.') && mime != null) {
            val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (ext != null) clean += ".$ext"
        }
        var f = File(dir, clean)
        var k = 2
        while (f.exists()) {
            f = File(dir, clean.substringBeforeLast('.') + " ($k)." + clean.substringAfterLast('.', "bin")); k++
        }
        val ins = (if (u.scheme == "file") u.path?.let { File(it).inputStream() } else cr.openInputStream(u))
            ?: throw IOException("לא ניתן לקרוא את הקובץ")
        ins.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        val kind = DocLoader.kindOf(clean, mime, f)
        if (kind == null) {
            f.delete()
            throw IOException("סוג הקובץ \"$clean\" אינו נתמך")
        }
        return JobFile(f.absolutePath, clean, kind)
    }

    fun removeFile(index: Int) {
        val j = job ?: return
        if (j.files.size <= 1) { closeCurrent(); return }
        runCatching { j.docs[index].close() }
        job = LoadedJob(j.id, j.dir, j.files.filterIndexed { i, _ -> i != index }, j.docs.filterIndexed { i, _ -> i != index }, j.version + 1)
    }

    fun moveFile(index: Int, delta: Int) {
        val j = job ?: return
        val to = index + delta
        if (to !in j.files.indices) return
        val f = j.files.toMutableList(); val d = j.docs.toMutableList()
        f.add(to, f.removeAt(index)); d.add(to, d.removeAt(index))
        job = LoadedJob(j.id, j.dir, f, d, j.version + 1)
    }

    fun closeCurrent() {
        job?.let { closeJob(it) }
        job = null
    }

    private fun closeJob(j: LoadedJob) {
        cache.evictAll()
        val inHistory = history.any { it.dir == j.dir.absolutePath }
        viewModelScope.launch(Dispatchers.IO) {
            j.docs.forEach { runCatching { it.close() } }
            if (!inHistory) j.dir.deleteRecursively()
        }
    }

    private fun cleanupOrphans() {
        viewModelScope.launch(Dispatchers.IO) {
            val keep = history.map { it.dir }.toSet()
            File(ctx.filesDir, "jobs").listFiles()?.forEach { if (it.absolutePath !in keep) it.deleteRecursively() }
            ctx.cacheDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        }
    }

    // ---------- אפשרויות ופריסטים ----------

    fun setOptions(f: (PrintOptions) -> PrintOptions) {
        options = f(options)
    }

    fun applyPreset(p: Preset) {
        options = p.options.copy(range = options.range)
    }

    fun savePreset(name: String) {
        val list = presets.filter { it.name != name } + Preset(name, options.copy(range = ""))
        presets = list; store.savePresets(list)
    }

    fun deletePreset(p: Preset) {
        val list = presets.filter { it.name != p.name }
        presets = list; store.savePresets(list)
    }

    fun saveDefaultOptions() {
        store.saveDefaultOptions(options.copy(range = ""))
        message = Msg("נשמר", "האפשרויות הנוכחיות יהיו ברירת המחדל לעבודות חדשות")
    }

    fun resetOptions() {
        options = PrintOptions()
    }

    // ---------- תצוגה מקדימה ----------

    fun previewKey(index: Int): String {
        val j = job
        return "${j?.id}/${j?.version}/${options.hashCode()}/$index"
    }

    suspend fun preview(index: Int, widthPx: Int): Bitmap? {
        val j = job ?: return null
        val l = layout ?: return null
        val side = l.sides.getOrNull(index) ?: return null
        val o = options
        val key = "${previewKey(index)}/$widthPx"
        cache.get(key)?.let { return it }
        val h = (widthPx * side.h / side.w).roundToInt()
        val bmp = withContext(Dispatchers.Default) { SideRenderer.render(side, j.docs, o, widthPx, h, false) }
        cache.put(key, bmp)
        return bmp
    }

    // ---------- מדפסות ----------

    fun selectTarget(t: String) {
        target = t; store.saveTarget(t)
        printers.firstOrNull { it.id == t }?.let { refreshCaps(it) }
    }

    fun refreshCaps(p: PrinterInfo) {
        if (p.protocol == Protocol.RAW) return
        viewModelScope.launch {
            try {
                val c = Engine.fetchCaps(p)
                if (c != null) { caps[p.id] = c; capsError.remove(p.id) }
            } catch (e: Exception) {
                capsError[p.id] = "לא מגיבה: " + (e.message ?: "")
                caps.remove(p.id)
            }
        }
    }

    fun addPrinter(p: PrinterInfo, c: PrinterCaps?) {
        val list = printers.filter { !(it.host == p.host && it.port == p.port && it.path == p.path) } + p
        printers = list; store.savePrinters(list)
        if (c != null) caps[p.id] = c
        if (target == "pdf" || printers.size == 1) selectTarget(p.id)
    }

    fun updatePrinter(p: PrinterInfo) {
        val list = printers.map { if (it.id == p.id) p else it }
        printers = list; store.savePrinters(list)
    }

    fun removePrinter(p: PrinterInfo) {
        val list = printers.filter { it.id != p.id }
        printers = list; store.savePrinters(list)
        if (target == p.id) selectTarget(list.firstOrNull()?.id ?: "pdf")
    }

    fun addFound(f: FoundPrinter) {
        viewModelScope.launch {
            busy = "מתחבר ל-${f.name}…"
            try {
                val r = withContext(Dispatchers.IO) { Discovery.probe(f.host, f.port, f.path.ifEmpty { null }, f.protocol) }
                if (r == null) {
                    if (f.protocol == Protocol.RAW) message = Msg("לא נמצאה מדפסת", "${f.host}:${f.port} לא מגיבה", false)
                    else addPrinter(PrinterInfo(newId(), f.name, f.host, f.port, f.path, f.protocol, f.model), null)
                } else {
                    val name = if (f.source == "Bonjour") f.name else r.first.name
                    addPrinter(r.first.copy(name = name), r.second)
                    message = Msg("המדפסת נוספה", name)
                }
            } finally {
                busy = null
            }
        }
    }

    fun addManual(name: String, host: String, port: Int, path: String, protocol: Protocol) {
        viewModelScope.launch {
            busy = "בודק את $host…"
            try {
                val r = withContext(Dispatchers.IO) { Discovery.probe(host.trim(), port, path.ifBlank { null }, protocol) }
                if (r == null) {
                    message = Msg(
                        "המדפסת לא ענתה",
                        "לא הצלחתי להתחבר ל-$host:$port.\nבדוק שהמדפסת דולקת ומחוברת לאותה רשת, ושהכתובת נכונה.\nאפשר לנסות גם פרוטוקול RAW (פורט 9100).",
                        false,
                    )
                } else {
                    addPrinter(r.first.copy(name = name.ifBlank { r.first.name }), r.second)
                    message = Msg("המדפסת נוספה", name.ifBlank { r.first.name })
                }
            } finally {
                busy = null
            }
        }
    }

    fun startScan() {
        if (scanning) return
        found.clear()
        scanning = true
        scanProgress = 0f
        scanJob = viewModelScope.launch {
            val a = launch {
                withTimeoutOrNull(15000) {
                    Discovery.mdns(ctx).collect { addFoundToList(it) }
                }
            }
            val b = launch {
                runCatching {
                    Discovery.scanSubnet(ctx) { p -> scanProgress = p }.collect { addFoundToList(it) }
                }
            }
            a.join(); b.join()
            scanning = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanning = false
    }

    private fun addFoundToList(f: FoundPrinter) {
        if (printers.any { it.host == f.host && it.port == f.port }) return
        val i = found.indexOfFirst { it.host == f.host && it.port == f.port }
        if (i < 0) found.add(f) else if (f.source == "Bonjour") found[i] = f
    }

    fun printTestPage(p: PrinterInfo) {
        val doc = TestPageDoc(p.name)
        val o = PrintOptions()
        val l = Imposer.build(listOf(doc), o)
        runPrint(p.name, null) { r ->
            Engine.print(PrintRequest(ctx, p, caps[p.id], l, listOf(doc), o, settings, "דף ניסיון"), r)
        }
    }

    // ---------- הדפסה ----------

    fun printNow() {
        val j = job ?: return
        val l = layout ?: return
        val p = printers.firstOrNull { it.id == target }
        if (p == null) {
            message = Msg("לא נבחרה מדפסת", "בחר מדפסת או \"שמירה כקובץ PDF\"", false)
            return
        }
        val o = options
        runPrint(p.name, j) { r ->
            Engine.print(
                PrintRequest(ctx, p, caps[p.id], l, j.docs, o, settings, j.title) { client, id -> lastIpp = Pair(client, id) },
                r,
            )
        }
    }

    private fun runPrint(targetName: String, j: LoadedJob?, block: suspend (Reporter) -> PrintResult) {
        printJob?.cancel()
        printJob = viewModelScope.launch {
            busy = "מתחיל…"; progress = null
            try {
                when (val res = block(reporter())) {
                    is PrintResult.Done -> {
                        message = Msg("נשלח בהצלחה", res.message)
                        if (j != null) addHistory(j, targetName, true, "")
                    }
                    is PrintResult.NeedFlip -> {
                        flip = FlipRequest(res.sheets) { r ->
                            val out = res.next(r)
                            if (j != null) addHistory(j, targetName, true, "דו-צדדי ידני")
                            out
                        }
                    }
                }
            } catch (e: CancellationException) {
                message = Msg("ההדפסה בוטלה", "")
                throw e
            } catch (e: Exception) {
                fail(e)
                if (j != null) addHistory(j, targetName, false, e.message ?: "")
            } finally {
                busy = null; progress = null; lastIpp = null
            }
        }
    }

    fun continueFlip() {
        val f = flip ?: return
        flip = null
        printJob = viewModelScope.launch {
            busy = "מתחיל צד שני…"
            try {
                val res = f.next(reporter())
                if (res is PrintResult.Done) message = Msg("הושלם", res.message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = null; progress = null; lastIpp = null
            }
        }
    }

    fun cancelFlip() {
        flip = null
    }

    fun cancelPrint() {
        val ipp = lastIpp
        printJob?.cancel()
        if (ipp != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { ipp.first.cancelJob(ipp.second) } }
        }
    }

    private suspend fun buildLogicalPdf(): File? {
        val j = job ?: return null
        val l = layout ?: return null
        return Engine.buildPdf(ctx, l.sides, j.docs, options, settings.pdfDpi, false, { BackTransform.NONE }, reporter())
    }

    fun savePdf(uri: Uri) {
        val j = job ?: return
        printJob = viewModelScope.launch {
            busy = "מכין PDF…"
            try {
                val f = buildLogicalPdf() ?: return@launch
                withContext(Dispatchers.IO) {
                    val os = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("לא ניתן לכתוב לקובץ")
                    os.use { o -> f.inputStream().use { it.copyTo(o) } }
                    f.delete()
                }
                message = Msg("הקובץ נשמר", "קובץ ה-PDF נשמר בהצלחה")
                addHistory(j, "קובץ PDF", true, "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = null; progress = null
            }
        }
    }

    fun sharePdf(activity: Activity) {
        printJob = viewModelScope.launch {
            busy = "מכין PDF לשיתוף…"
            try {
                val f = buildLogicalPdf() ?: return@launch
                val named = File(ctx.cacheDir, (job?.title ?: "הדפסה").substringBeforeLast('.').take(60) + ".pdf")
                f.renameTo(named)
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", named)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(send, "שיתוף PDF"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = null; progress = null
            }
        }
    }

    /** הדפסה דרך שירות ההדפסה של אנדרואיד (Mopria / תוסף יצרן) */
    fun systemPrint(activity: Activity) {
        val j = job ?: return
        val l = layout ?: return
        printJob = viewModelScope.launch {
            busy = "מכין להדפסה…"
            try {
                val f = buildLogicalPdf() ?: return@launch
                val pm = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val attrs = PrintAttributes.Builder()
                    .setDuplexMode(
                        when {
                            !l.duplex -> PrintAttributes.DUPLEX_MODE_NONE
                            l.shortEdge -> PrintAttributes.DUPLEX_MODE_SHORT_EDGE
                            else -> PrintAttributes.DUPLEX_MODE_LONG_EDGE
                        }
                    )
                    .setColorMode(if (options.color == ColorMode.MONO) PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_COLOR)
                    .build()
                pm.print(j.title, FilePrintAdapter(f, j.title, l.sides.size), attrs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = null; progress = null
            }
        }
    }

    // ---------- היסטוריה ----------

    private fun addHistory(j: LoadedJob, targetName: String, ok: Boolean, note: String) {
        val l = layout
        val e = HistoryEntry(
            id = newId(), time = System.currentTimeMillis(), title = j.title, target = targetName,
            sheets = l?.sheetCount ?: 0, pages = l?.selectedPages ?: 0, ok = ok, note = note,
            dir = j.dir.absolutePath, files = j.files, options = options,
        )
        val all = listOf(e) + history
        val keep = all.take(40)
        val dropped = all.drop(40)
        history = keep; store.saveHistory(keep)
        val keepDirs = keep.map { it.dir }.toSet() + (job?.dir?.absolutePath ?: "")
        viewModelScope.launch(Dispatchers.IO) {
            dropped.forEach { if (it.dir !in keepDirs) File(it.dir).deleteRecursively() }
        }
    }

    fun reopen(h: HistoryEntry) {
        viewModelScope.launch {
            busy = "פותח את \"${h.title}\"…"
            try {
                val docs = withContext(Dispatchers.IO) { h.files.map { DocLoader.open(it, settings) } }
                job?.let { if (it.dir.absolutePath != h.dir) closeJob(it) }
                job = LoadedJob(newId(), File(h.dir), h.files, docs, 1)
                options = h.options
                tab = 0
            } catch (e: Exception) {
                message = Msg("לא ניתן לפתוח", "ייתכן שהקבצים נמחקו.\n${e.message}", false)
            } finally {
                busy = null
            }
        }
    }

    fun deleteHistory(h: HistoryEntry) {
        val list = history.filter { it.id != h.id }
        history = list; store.saveHistory(list)
        if (list.none { it.dir == h.dir } && job?.dir?.absolutePath != h.dir) {
            viewModelScope.launch(Dispatchers.IO) { File(h.dir).deleteRecursively() }
        }
    }

    fun clearHistory() {
        val dirs = history.map { it.dir }.toSet()
        history = emptyList(); store.saveHistory(emptyList())
        val cur = job?.dir?.absolutePath
        viewModelScope.launch(Dispatchers.IO) { dirs.forEach { if (it != cur) File(it).deleteRecursively() } }
    }

    // ---------- הגדרות ועדכונים ----------

    fun saveSettings(s: AppSettings) {
        settings = s; store.saveSettings(s)
    }

    fun testConverter() {
        viewModelScope.launch {
            busy = "בודק את שירות ההמרה…"
            val r = Converter.test(settings.convertUrl, settings.convertKey)
            busy = null
            message = Msg("שירות ההמרה", r, r.startsWith("✔"))
        }
    }

    fun copyAppsScript() {
        val code = ctx.resources.openRawResource(R.raw.apps_script).use { it.readBytes().toString(Charsets.UTF_8) }
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Apps Script", code))
        message = Msg("הקוד הועתק", "הדבק אותו בפרויקט Apps Script חדש (ההוראות בהגדרות)")
    }

    fun checkUpdate() {
        viewModelScope.launch {
            busy = "בודק עדכונים…"
            val info = withContext(Dispatchers.IO) { runCatching { Updater.check() }.getOrNull() }
            busy = null
            update = info
            if (info != null) showUpdateDialog = true
            else message = Msg("אין עדכון", "יש לך את הגרסה האחרונה (${BuildConfig.VERSION_NAME})")
        }
    }

    fun dismissUpdate() {
        update?.let { store.saveSeenUpdate(it.version) }
        showUpdateDialog = false
    }

    fun openUrl(activity: Activity, url: String) {
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun shareApp(activity: Activity) {
        val text = "אפליקציית \"הדפסות\" – הדפסה מהטלפון לכל מדפסת ברשת, כולל חוברות ודו-צדדי.\nלהורדה:\n${Updater.APK_URL}"
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        activity.startActivity(Intent.createChooser(i, "שיתוף האפליקציה"))
    }

    override fun onCleared() {
        job?.docs?.forEach { runCatching { it.close() } }
        super.onCleared()
    }
}

/** מעביר קובץ PDF מוכן לשירות ההדפסה של אנדרואיד */
class FilePrintAdapter(private val file: File, private val name: String, private val pages: Int) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
        callback: PrintDocumentAdapter.LayoutResultCallback, extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled(); return
        }
        val info = PrintDocumentInfo.Builder("$name.pdf")
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(pages)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pageRanges: Array<out PageRange>?, destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?, callback: PrintDocumentAdapter.WriteResultCallback,
    ) {
        try {
            file.inputStream().use { i -> FileOutputStream(destination.fileDescriptor).use { o -> i.copyTo(o) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
