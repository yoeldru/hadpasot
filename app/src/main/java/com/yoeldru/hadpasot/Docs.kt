// נכתב: 2026-11-02 21:14 (שעון ישראל)
package com.yoeldru.hadpasot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.SizeF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** מסמך שמורכב מעמודים שאפשר לצייר בכל גודל */
interface PageDoc {
    val name: String
    val pageCount: Int
    fun pageSize(i: Int): SizeF
    suspend fun render(i: Int, w: Int, h: Int): Bitmap
    fun close()
}

private fun whiteBitmap(w: Int, h: Int): Bitmap {
    val b = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    b.eraseColor(Color.WHITE)
    return b
}

class PdfDoc(file: File, override val name: String) : PageDoc {
    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = try {
        PdfRenderer(pfd)
    } catch (e: SecurityException) {
        pfd.close(); throw IOException("הקובץ ${name} מוגן בסיסמה – לא ניתן לפתוח")
    } catch (e: Exception) {
        pfd.close(); throw IOException("לא ניתן לקרוא את הקובץ ${name} כ-PDF")
    }
    private val mutex = Mutex()
    @Volatile
    private var closed = false
    private val sizes: List<SizeF> = (0 until renderer.pageCount).map { i ->
        val p = renderer.openPage(i)
        try {
            SizeF(p.width.toFloat(), p.height.toFloat())
        } finally {
            p.close()
        }
    }

    override val pageCount: Int get() = sizes.size
    override fun pageSize(i: Int): SizeF = sizes[i]

    override suspend fun render(i: Int, w: Int, h: Int): Bitmap = mutex.withLock {
        val bmp = whiteBitmap(w, h)
        if (!closed) {
            val p = renderer.openPage(i)
            try {
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            } finally {
                p.close()
            }
        }
        bmp
    }

    override fun close() {
        if (closed) return
        closed = true
        runBlocking {
            mutex.withLock {
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }
}

class ImageDoc(private val file: File, override val name: String) : PageDoc {
    private val rotation: Int
    private val size: SizeF

    init {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) throw IOException("לא ניתן לקרוא את התמונה ${name}")
        rotation = runCatching {
            when (ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val w = if (rotation % 180 == 0) o.outWidth else o.outHeight
        val h = if (rotation % 180 == 0) o.outHeight else o.outWidth
        size = SizeF(w * 72f / 150f, h * 72f / 150f)
    }

    override val pageCount: Int = 1
    override fun pageSize(i: Int): SizeF = size

    override suspend fun render(i: Int, w: Int, h: Int): Bitmap = withContext(Dispatchers.IO) {
        val tw = if (rotation % 180 == 0) w else h
        val th = if (rotation % 180 == 0) h else w
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= tw && bounds.outHeight / (sample * 2) >= th) sample *= 2
        val src = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("לא ניתן לפענח את התמונה ${name}")
        val out = whiteBitmap(w, h)
        val c = Canvas(out)
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        val r = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(r)
        m.postTranslate(-r.left, -r.top)
        m.postScale(out.width / r.width(), out.height / r.height())
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        src.recycle()
        out
    }

    override fun close() {}
}

class TextDoc(text: String, override val name: String) : PageDoc {
    private val pw = 595f
    private val ph = 842f
    private val margin = 50f
    private val mutex = Mutex()
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f
        color = Color.BLACK
    }
    private val layout: StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, (pw - 2 * margin).toInt())
        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
        .setLineSpacing(0f, 1.2f)
        .build()
    private val starts: List<Int>

    init {
        val list = mutableListOf(0)
        var top = 0
        for (line in 0 until layout.lineCount) {
            if (layout.getLineBottom(line) - top > ph - 2 * margin && line > list.last()) {
                list.add(line)
                top = layout.getLineTop(line)
            }
        }
        starts = list
    }

    override val pageCount: Int get() = starts.size
    override fun pageSize(i: Int): SizeF = SizeF(pw, ph)

    override suspend fun render(i: Int, w: Int, h: Int): Bitmap = mutex.withLock {
        val bmp = whiteBitmap(w, h)
        val c = Canvas(bmp)
        c.scale(bmp.width / pw, bmp.height / ph)
        val first = starts[i]
        val last = if (i + 1 < starts.size) starts[i + 1] else layout.lineCount
        val top = layout.getLineTop(first).toFloat()
        val bottom = layout.getLineTop(last).toFloat()
        c.translate(margin, margin - top)
        c.clipRect(0f, top, pw - 2 * margin, bottom)
        layout.draw(c)
        bmp
    }

    override fun close() {}

    companion object {
        fun decode(bytes: ByteArray): String = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (e: CharacterCodingException) {
            String(bytes, runCatching { Charset.forName("windows-1255") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }
}

/** דף ניסיון: כותרת, תאריך, פסי צבע ומדרג אפור */
class TestPageDoc(private val printerName: String) : PageDoc {
    override val name = "דף ניסיון"
    override val pageCount = 1
    override fun pageSize(i: Int) = SizeF(PaperSize.A4.wPt, PaperSize.A4.hPt)

    override suspend fun render(i: Int, w: Int, h: Int): Bitmap = withContext(Dispatchers.Default) {
        val bmp = whiteBitmap(w, h)
        val c = Canvas(bmp)
        val pw = PaperSize.A4.wPt
        c.scale(bmp.width / pw, bmp.height / PaperSize.A4.hPt)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER }
        t.textSize = 30f
        c.drawText("דף ניסיון – הדפסות", pw / 2, 90f, t)
        t.textSize = 14f
        c.drawText(printerName, pw / 2, 120f, t)
        c.drawText(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date()), pw / 2, 142f, t)
        val colors = intArrayOf(Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE)
        val bw = (pw - 100f) / colors.size
        val fill = Paint()
        colors.forEachIndexed { k, col ->
            fill.color = col
            c.drawRect(50f + k * bw, 180f, 50f + (k + 1) * bw, 260f, fill)
        }
        for (k in 0 until 16) {
            val g = 255 - k * 17
            fill.color = Color.rgb(g, g, g)
            val sw = (pw - 100f) / 16
            c.drawRect(50f + k * sw, 290f, 50f + (k + 1) * sw, 340f, fill)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 0.5f }
        c.drawRect(20f, 20f, pw - 20f, PaperSize.A4.hPt - 20f, line)
        var y = 380f
        while (y < 760f) {
            c.drawLine(50f, y, pw - 50f, y, line); y += 20f
        }
        t.textSize = 12f
        c.drawText("המסגרת החיצונית במרחק 7 מ\"מ מקצה הדף", pw / 2, 790f, t)
        bmp
    }

    override fun close() {}
}

object DocLoader {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")
    private val officeExt = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "html", "htm")
    private val textExt = setOf("txt", "csv", "log", "md", "json", "xml")

    fun kindOf(name: String, mime: String?, file: File): Kind? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime?.lowercase() ?: ""
        return when {
            m == "application/pdf" || ext == "pdf" -> Kind.PDF
            m.startsWith("image/") || ext in imageExt -> Kind.IMAGE
            ext in officeExt || m.contains("officedocument") || m.contains("msword") || m.contains("ms-excel") ||
                m.contains("ms-powerpoint") || m.contains("opendocument") || m == "application/rtf" || m == "text/html" -> Kind.OFFICE
            m.startsWith("text/") || ext in textExt -> Kind.TEXT
            isPdf(file) -> Kind.PDF
            isImage(file) -> Kind.IMAGE
            else -> null
        }
    }

    private fun isPdf(f: File): Boolean = runCatching {
        f.inputStream().use { val b = ByteArray(5); it.read(b) == 5 && String(b, Charsets.US_ASCII) == "%PDF-" }
    }.getOrDefault(false)

    private fun isImage(f: File): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        return o.outWidth > 0
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "rtf" -> "application/rtf"
        "html", "htm" -> "text/html"
        else -> "application/octet-stream"
    }

    /** פותח קובץ עבודה; קבצי אופיס מומרים ל-PDF דרך שירות ההמרה (פעם אחת, ונשמרים) */
    fun open(jf: JobFile, s: AppSettings): PageDoc {
        val f = File(jf.path)
        if (!f.exists()) throw IOException("הקובץ ${jf.name} לא נמצא")
        return when (jf.kind) {
            Kind.PDF -> PdfDoc(f, jf.name)
            Kind.IMAGE -> ImageDoc(f, jf.name)
            Kind.TEXT -> TextDoc(TextDoc.decode(f.readBytes()), jf.name)
            Kind.OFFICE -> {
                val pdf = File(f.parentFile, f.name + ".converted.pdf")
                if (!pdf.exists() || pdf.length() == 0L) {
                    Converter.convert(f, jf.name, mimeFor(jf.name), s.convertUrl, s.convertKey, pdf)
                }
                PdfDoc(pdf, jf.name)
            }
        }
    }
}
