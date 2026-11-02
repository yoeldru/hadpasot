// נכתב: 2026-11-02 21:31 (שעון ישראל)
package com.yoeldru.hadpasot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

typealias Reporter = (String, Float?) -> Unit

sealed class PrintResult {
    class Done(val message: String) : PrintResult()
    class NeedFlip(val sheets: Int, val next: suspend (Reporter) -> PrintResult) : PrintResult()
}

class PrintRequest(
    val ctx: Context,
    val printer: PrinterInfo,
    val caps: PrinterCaps?,
    val layout: Layout,
    val docs: List<PageDoc>,
    val o: PrintOptions,
    val s: AppSettings,
    val title: String,
    val onJob: (IppClient, Int) -> Unit = { _, _ -> },
)

object Engine {

    fun paperPx(o: PrintOptions, dpi: Int): Pair<Int, Int> =
        Pair((o.paper.wMm / 25.4f * dpi).roundToInt(), (o.paper.hMm / 25.4f * dpi).roundToInt())

    /** יוצר PDF מהצדדים. portrait=true: כל העמודים לאורך (צדדים לרוחב מסובבים) – למדפסת */
    suspend fun buildPdf(
        ctx: Context, sides: List<Side>, docs: List<PageDoc>, o: PrintOptions, dpi: Int,
        portrait: Boolean, backTf: (Side) -> BackTransform, report: Reporter,
    ): File = withContext(Dispatchers.Default) {
        val pdf = PdfDocument()
        try {
            sides.forEachIndexed { idx, side ->
                ensureActive()
                report("מכין עמוד ${idx + 1} מתוך ${sides.size}", idx.toFloat() / sides.size)
                val rot = portrait && side.landscape
                val pageW = if (rot) side.h else side.w
                val pageH = if (rot) side.w else side.h
                val bw = (pageW / 72f * dpi).roundToInt()
                val bh = (pageH / 72f * dpi).roundToInt()
                val bmp = SideRenderer.render(side, docs, o, bw, bh, rot, backTf(side))
                val info = PdfDocument.PageInfo.Builder(pageW.roundToInt(), pageH.roundToInt(), idx + 1).create()
                val page = pdf.startPage(info)
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, pageW.roundToInt(), pageH.roundToInt()), Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                bmp.recycle()
            }
            report("שומר קובץ…", null)
            val f = File(ctx.cacheDir, "print-${System.currentTimeMillis()}.pdf")
            FileOutputStream(f).use { pdf.writeTo(it) }
            f
        } finally {
            pdf.close()
        }
    }

    fun backFor(sheetBack: String, shortEdge: Boolean): BackTransform = when (sheetBack) {
        "flipped" -> if (shortEdge) BackTransform.FLIP_X else BackTransform.FLIP_Y
        "rotated" -> if (shortEdge) BackTransform.NONE else BackTransform.ROTATE
        "manual-tumble" -> if (shortEdge) BackTransform.ROTATE else BackTransform.NONE
        else -> BackTransform.NONE
    }

    private suspend fun buildRaster(
        ctx: Context, sides: List<Side>, docs: List<PageDoc>, o: PrintOptions, urf: Boolean, dpi: Int, gray: Boolean,
        hwDuplex: Boolean, shortEdge: Boolean, sheetBack: String, rotateBacks: Boolean, report: Reporter,
    ): File = withContext(Dispatchers.Default) {
        val f = File(ctx.cacheDir, "job-${System.currentTimeMillis()}.${if (urf) "urf" else "pwg"}")
        val (bw, bh) = paperPx(o, dpi)
        BufferedOutputStream(FileOutputStream(f), 1 shl 16).use { out ->
            val rw = RasterWriter(out, urf, sides.size)
            sides.forEachIndexed { i, side ->
                ensureActive()
                report("מכין עמוד ${i + 1} מתוך ${sides.size}", i.toFloat() / sides.size)
                val hwTf = if (side.isBack && hwDuplex) backFor(sheetBack, shortEdge) else BackTransform.NONE
                val tf = if (hwTf != BackTransform.NONE) hwTf
                else if (side.isBack && rotateBacks) BackTransform.ROTATE else BackTransform.NONE
                val bmp = SideRenderer.render(side, docs, o, bw, bh, side.landscape, tf)
                val cross = if (hwTf == BackTransform.FLIP_X || hwTf == BackTransform.ROTATE) -1 else 1
                val feed = if (hwTf == BackTransform.FLIP_Y || hwTf == BackTransform.ROTATE) -1 else 1
                rw.writePage(bmp, dpi, gray, o.paper, hwDuplex, shortEdge, cross, feed, o.quality.ipp)
                bmp.recycle()
            }
        }
        f
    }

    private fun expand(sides: List<Side>, copies: Int, collate: Boolean, duplex: Boolean): List<Side> {
        if (copies <= 1) return sides
        val units: List<List<Side>> = if (duplex) sides.chunked(2) else sides.map { listOf(it) }
        return if (collate) (1..copies).flatMap { sides } else units.flatMap { u -> (1..copies).flatMap { u } }
    }

    private fun pickDpi(list: List<Int>, s: AppSettings, q: Quality): Int {
        val cap = if (q == Quality.DRAFT) minOf(150, s.maxDpi) else s.maxDpi
        val l = list.filter { it > 0 }.distinct()
        if (l.isEmpty()) return 300
        return l.filter { it <= cap }.maxOrNull() ?: l.min()
    }

    private fun urfDpis(urf: List<String>): List<Int> =
        urf.filter { it.startsWith("RS") }.flatMap { it.removePrefix("RS").split('-').mapNotNull { x -> x.toIntOrNull() } }

    private fun urfSheetBack(urf: List<String>): String = when {
        urf.contains("DM2") -> "flipped"
        urf.contains("DM3") -> "rotated"
        urf.contains("DM4") -> "manual-tumble"
        else -> "normal"
    }

    fun chooseFormat(caps: PrinterCaps, pref: String): String {
        val pwgOk = caps.pwgRes.isNotEmpty() && caps.pwgTypes.any { it == "sgray_8" || it == "srgb_8" }
        val urfOk = caps.urf.any { it == "W8" || it == "SRGB24" }
        fun usable(f: String) = when (f) {
            "image/pwg-raster" -> pwgOk && f in caps.formats
            "image/urf" -> urfOk && f in caps.formats
            else -> f in caps.formats
        }
        if (pref != "auto") return pref
        return listOf("image/pwg-raster", "image/urf", "application/pdf", "image/jpeg").firstOrNull { usable(it) } ?: "application/pdf"
    }

    suspend fun fetchCaps(p: PrinterInfo): PrinterCaps? {
        if (p.protocol == Protocol.RAW) return null
        return withContext(Dispatchers.IO) {
            val r = IppClient(p).getAttributes()
            if (!r.ok) throw IOException(IppText.error(r))
            capsFrom(r)
        }
    }

    suspend fun print(req: PrintRequest, report: Reporter): PrintResult {
        val L = req.layout
        val o = req.o
        val p = req.printer
        val caps = if (p.protocol == Protocol.RAW) null else (req.caps ?: run {
            report("מתחבר למדפסת…", null)
            fetchCaps(p)
        })
        val sidesKw = if (L.shortEdge) "two-sided-short-edge" else "two-sided-long-edge"
        val hw = L.duplex && p.protocol != Protocol.RAW && when (o.duplexMethod) {
            DuplexMethod.MANUAL -> false
            DuplexMethod.PRINTER -> true
            DuplexMethod.AUTO -> caps?.sides?.contains(sidesKw) == true
        }
        val copies = o.copies.coerceIn(1, 999)
        if (!L.duplex || hw) {
            send(req, caps, L.sides, hw, sidesKw, copies, o.collate, false, "", report)
            return PrintResult.Done("העבודה נשלחה למדפסת ${p.name}")
        }
        // דו-צדדי ידני: קודם כל הצדדים הקדמיים, ואז הפיכת הערימה והצדדים האחוריים
        val fronts = L.sides.filter { !it.isBack }
        val backs = L.sides.filter { it.isBack }
        val frontsAll = expand(fronts, copies, o.collate, false)
        val backsAll = expand(backs, copies, o.collate, false)
        val backsOrdered = if (req.s.manualBackReverse) backsAll.reversed() else backsAll
        send(req, caps, frontsAll, false, "one-sided", 1, true, false, "צד ראשון", report)
        return PrintResult.NeedFlip(frontsAll.size) { r ->
            send(req, caps, backsOrdered, false, "one-sided", 1, true, req.s.manualBackRotate, "צד שני", r)
            PrintResult.Done("ההדפסה הדו-צדדית הושלמה")
        }
    }

    private suspend fun send(
        req: PrintRequest, capsIn: PrinterCaps?, sides: List<Side>, hwDuplex: Boolean, sidesKw: String,
        copies: Int, collate: Boolean, rotateBacks: Boolean, label: String, report: Reporter,
    ) {
        val p = req.printer
        val o = req.o
        val backTf: (Side) -> BackTransform = { if (rotateBacks && it.isBack) BackTransform.ROTATE else BackTransform.NONE }

        if (p.protocol == Protocol.RAW) {
            val list = expand(sides, copies, collate, false)
            val f = buildPdf(req.ctx, list, req.docs, o, minOf(300, req.s.maxDpi), true, backTf, report)
            report("שולח למדפסת…", null)
            withContext(Dispatchers.IO) {
                Socket().use { s ->
                    s.connect(InetSocketAddress(p.host, p.port), 6000)
                    s.getOutputStream().use { os -> f.inputStream().use { it.copyTo(os, 65536) } }
                }
            }
            f.delete()
            return
        }

        val client = IppClient(p)
        val caps = capsIn ?: PrinterCaps()
        val format = chooseFormat(caps, p.format)
        val copiesAttr = copies > 1 && caps.copiesMax >= copies && format != "image/jpeg"
        val list = if (copiesAttr || copies <= 1) sides else expand(sides, copies, collate, hwDuplex)
        val mono = o.color == ColorMode.MONO || !caps.canColor
        val jobName = req.title + (if (label.isNotEmpty()) " – $label" else "")
        val sidesValue = if (hwDuplex) sidesKw else "one-sided"

        val attrs: (IppWriter) -> Unit = { w ->
            w.group(IppTag.JOB)
            if (copiesAttr) {
                w.int(IppTag.INTEGER, "copies", copies)
                val mdh = if (collate) "separate-documents-collated-copies" else "separate-documents-uncollated-copies"
                if (mdh in caps.mdh) w.str(IppTag.KEYWORD, "multiple-document-handling", mdh)
            }
            if (caps.sides.isEmpty() || sidesValue in caps.sides) w.str(IppTag.KEYWORD, "sides", sidesValue)
            if (caps.media.isEmpty() || o.paper.ippName in caps.media) w.str(IppTag.KEYWORD, "media", o.paper.ippName)
            if (caps.colorModes.isNotEmpty()) {
                val cm = if (mono) "monochrome" else "color"
                if (cm in caps.colorModes) w.str(IppTag.KEYWORD, "print-color-mode", cm)
            }
            if (o.quality.ipp in caps.qualities) w.int(IppTag.ENUM, "print-quality", o.quality.ipp)
            if (format == "application/pdf" && "fit" in caps.scaling) w.str(IppTag.KEYWORD, "print-scaling", "fit")
        }

        when (format) {
            "image/pwg-raster", "image/urf" -> {
                val urf = format == "image/urf"
                val dpi = pickDpi(if (urf) urfDpis(caps.urf) else caps.pwgRes, req.s, o.quality)
                val gray = if (urf) {
                    (mono && "W8" in caps.urf) || "SRGB24" !in caps.urf
                } else {
                    (mono && "sgray_8" in caps.pwgTypes) || "srgb_8" !in caps.pwgTypes
                }
                val sheetBack = if (urf) urfSheetBack(caps.urf) else caps.sheetBack
                val f = buildRaster(req.ctx, list, req.docs, o, urf, dpi, gray, hwDuplex, req.layout.shortEdge, sheetBack, rotateBacks, report)
                sendFile(req, client, f, format, jobName, attrs, report)
            }
            "image/jpeg" -> {
                val (bw, bh) = paperPx(o, minOf(300, req.s.maxDpi))
                list.forEachIndexed { i, side ->
                    report("מכין עמוד ${i + 1} מתוך ${list.size}", i.toFloat() / list.size)
                    val bmp = SideRenderer.render(side, req.docs, o, bw, bh, side.landscape, backTf(side))
                    val f = File(req.ctx.cacheDir, "job-$i-${System.currentTimeMillis()}.jpg")
                    withContext(Dispatchers.IO) { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
                    bmp.recycle()
                    sendFile(req, client, f, format, "$jobName (${i + 1})", attrs, report)
                }
            }
            else -> {
                val dpi = if (o.quality == Quality.DRAFT) 150 else minOf(300, req.s.maxDpi)
                val f = buildPdf(req.ctx, list, req.docs, o, dpi, true, backTf, report)
                sendFile(req, client, f, format, jobName, attrs, report)
            }
        }
    }

    private suspend fun sendFile(
        req: PrintRequest, client: IppClient, f: File, format: String, jobName: String,
        attrs: (IppWriter) -> Unit, report: Reporter,
    ) {
        report("שולח למדפסת…", 0f)
        val r = try {
            withContext(Dispatchers.IO) {
                client.printJob(f, format, jobName, attrs) { pr -> report("שולח למדפסת… ${(pr * 100).roundToInt()}%", pr) }
            }
        } finally {
            f.delete()
        }
        if (!r.ok) throw IOException(IppText.error(r))
        val jobId = r.int("job-id") ?: return
        req.onJob(client, jobId)
        waitJob(client, jobId, report)
    }

    private suspend fun waitJob(client: IppClient, jobId: Int, report: Reporter) {
        report("המדפסת קיבלה את העבודה…", null)
        repeat(45) {
            delay(2000)
            val jr = runCatching { withContext(Dispatchers.IO) { client.jobAttributes(jobId) } }.getOrNull() ?: return
            val st = jr.int("job-state") ?: return
            val reasons = jr.strings("job-state-reasons").filter { it != "none" && it != "job-printing" }
                .joinToString(", ") { IppText.reason(it) }
            val done = jr.int("job-impressions-completed")
            when (st) {
                9 -> return
                7 -> throw IOException("העבודה בוטלה במדפסת")
                8 -> throw IOException("ההדפסה נכשלה במדפסת" + if (reasons.isNotEmpty()) ": $reasons" else "")
                6 -> report("המדפסת עצרה" + if (reasons.isNotEmpty()) ": $reasons" else "", null)
                5 -> report("מדפיס…" + (if (done != null && done > 0) " ($done עמודים הודפסו)" else ""), null)
                else -> report("ממתין בתור המדפסת…", null)
            }
        }
    }
}
