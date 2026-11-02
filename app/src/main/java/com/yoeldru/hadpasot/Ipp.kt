// נכתב: 2026-11-02 21:08 (שעון ישראל)
package com.yoeldru.hadpasot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object IppTag {
    const val OP = 0x01
    const val JOB = 0x02
    const val END = 0x03
    const val INTEGER = 0x21
    const val BOOLEAN = 0x22
    const val ENUM = 0x23
    const val RESOLUTION = 0x32
    const val RANGE = 0x33
    const val BEG_COLLECTION = 0x34
    const val END_COLLECTION = 0x37
    const val NAME = 0x42
    const val KEYWORD = 0x44
    const val URI = 0x45
    const val CHARSET = 0x47
    const val LANGUAGE = 0x48
    const val MIME = 0x49
    const val MEMBER = 0x4A
}

object IppOp {
    const val PRINT_JOB = 0x0002
    const val CANCEL_JOB = 0x0008
    const val GET_JOB_ATTRIBUTES = 0x0009
    const val GET_PRINTER_ATTRIBUTES = 0x000B
}

data class IppResolution(val x: Int, val y: Int, val units: Int)

class IppWriter(op: Int, requestId: Int) {
    private val bos = ByteArrayOutputStream()
    private val out = DataOutputStream(bos)

    init {
        out.writeByte(2); out.writeByte(0)
        out.writeShort(op)
        out.writeInt(requestId)
    }

    fun group(tag: Int) = out.writeByte(tag)

    fun raw(tag: Int, name: String, value: ByteArray) {
        val n = name.toByteArray(Charsets.UTF_8)
        out.writeByte(tag)
        out.writeShort(n.size); out.write(n)
        out.writeShort(value.size); out.write(value)
    }

    fun str(tag: Int, name: String, value: String) = raw(tag, name, value.toByteArray(Charsets.UTF_8))

    fun strs(tag: Int, name: String, values: List<String>) {
        values.forEachIndexed { i, v -> str(tag, if (i == 0) name else "", v) }
    }

    fun int(tag: Int, name: String, value: Int) = raw(tag, name, ByteBuffer.allocate(4).putInt(value).array())

    fun finish(): ByteArray {
        out.writeByte(IppTag.END)
        out.flush()
        return bos.toByteArray()
    }
}

class IppGroup(val tag: Int) {
    val attrs = LinkedHashMap<String, MutableList<Any>>()
}

class IppResponse(val status: Int, val groups: List<IppGroup>) {
    val ok: Boolean get() = status < 0x0100
    fun values(name: String): List<Any> = groups.flatMap { it.attrs[name] ?: emptyList<Any>() }
    fun strings(name: String): List<String> = values(name).filterIsInstance<String>()
    fun ints(name: String): List<Int> = values(name).filterIsInstance<Int>()
    fun int(name: String): Int? = ints(name).firstOrNull()
    fun string(name: String): String? = strings(name).firstOrNull()
}

object IppParser {
    fun parse(bytes: ByteArray): IppResponse {
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        inp.readUnsignedByte(); inp.readUnsignedByte()
        val status = inp.readUnsignedShort()
        inp.readInt()
        val groups = ArrayList<IppGroup>()
        var cur: IppGroup? = null
        var lastName: String? = null
        try {
            while (true) {
                val tag = inp.readUnsignedByte()
                if (tag == IppTag.END) break
                if (tag < 0x10) {
                    val g = IppGroup(tag)
                    groups.add(g); cur = g; lastName = null
                    continue
                }
                val nl = inp.readUnsignedShort()
                val name = String(readN(inp, nl), Charsets.UTF_8)
                val vl = inp.readUnsignedShort()
                val v = readN(inp, vl)
                val value: Any? = if (tag == IppTag.BEG_COLLECTION) readCollection(inp) else decode(tag, v)
                var g = cur
                if (g == null) { g = IppGroup(0); groups.add(g); cur = g }
                val key: String = if (nl > 0) name else (lastName ?: continue)
                lastName = key
                val list = g.attrs.getOrPut(key) { mutableListOf() }
                if (value != null) list.add(value)
            }
        } catch (_: EOFException) {
        }
        return IppResponse(status, groups)
    }

    private fun readN(inp: DataInputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        inp.readFully(b)
        return b
    }

    private fun readCollection(inp: DataInputStream): Map<String, List<Any>> {
        val map = LinkedHashMap<String, MutableList<Any>>()
        var current: String? = null
        while (true) {
            val tag = inp.readUnsignedByte()
            val nl = inp.readUnsignedShort(); readN(inp, nl)
            val vl = inp.readUnsignedShort(); val v = readN(inp, vl)
            when (tag) {
                IppTag.END_COLLECTION -> return map
                IppTag.MEMBER -> {
                    val k = String(v, Charsets.UTF_8)
                    current = k
                    map.getOrPut(k) { mutableListOf() }
                }
                IppTag.BEG_COLLECTION -> {
                    val c = readCollection(inp)
                    current?.let { map[it]?.add(c) }
                }
                else -> {
                    val d = decode(tag, v)
                    if (d != null) current?.let { map[it]?.add(d) }
                }
            }
        }
    }

    private fun decode(tag: Int, v: ByteArray): Any? = try {
        when (tag) {
            in 0x10..0x1F -> null
            IppTag.INTEGER, IppTag.ENUM -> if (v.size >= 4) ByteBuffer.wrap(v).int else null
            IppTag.BOOLEAN -> v.isNotEmpty() && v[0].toInt() != 0
            IppTag.RESOLUTION -> if (v.size >= 9) {
                val b = ByteBuffer.wrap(v)
                val x = b.int
                val y = b.int
                IppResolution(x, y, b.get().toInt())
            } else null
            IppTag.RANGE -> if (v.size >= 8) {
                val b = ByteBuffer.wrap(v)
                val lo = b.int
                IntRange(lo, b.int)
            } else null
            0x35, 0x36 -> {
                val b = ByteBuffer.wrap(v)
                val ll = b.short.toInt() and 0xFFFF
                b.position(2 + ll)
                val tl = b.short.toInt() and 0xFFFF
                String(v, 4 + ll, tl, Charsets.UTF_8)
            }
            in 0x40..0x4F -> String(v, Charsets.UTF_8)
            else -> v
        }
    } catch (_: Exception) {
        null
    }
}

object Tls {
    fun trustAll(conn: HttpsURLConnection) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        conn.sslSocketFactory = ctx.socketFactory
        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
    }
}

class IppClient(private val p: PrinterInfo) {
    private val hostPart = if (p.host.contains(':')) "[${p.host}]" else p.host
    private val cleanPath = p.path.trim().trimStart('/')
    val printerUri = "${if (p.protocol == Protocol.IPPS) "ipps" else "ipp"}://$hostPart:${p.port}/$cleanPath"
    private val httpUrl = "${if (p.protocol == Protocol.IPPS) "https" else "http"}://$hostPart:${p.port}/$cleanPath"

    private fun nextId(): Int = (System.nanoTime() and 0x7FFFFFFF).toInt().coerceAtLeast(1)

    private fun op(w: IppWriter, jobId: Int? = null) {
        w.group(IppTag.OP)
        w.str(IppTag.CHARSET, "attributes-charset", "utf-8")
        w.str(IppTag.LANGUAGE, "attributes-natural-language", "en")
        w.str(IppTag.URI, "printer-uri", printerUri)
        if (jobId != null) w.int(IppTag.INTEGER, "job-id", jobId)
        w.str(IppTag.NAME, "requesting-user-name", "Hadpasot")
    }

    fun getAttributes(timeout: Int = 8000): IppResponse {
        val w = IppWriter(IppOp.GET_PRINTER_ATTRIBUTES, nextId())
        op(w)
        w.strs(IppTag.KEYWORD, "requested-attributes", listOf("all", "media-col-database"))
        return send(w.finish(), null, timeout, null)
    }

    fun printJob(file: File, format: String, jobName: String, jobAttrs: (IppWriter) -> Unit, onProgress: (Float) -> Unit): IppResponse {
        val w = IppWriter(IppOp.PRINT_JOB, nextId())
        op(w)
        w.str(IppTag.NAME, "job-name", jobName.take(120))
        w.str(IppTag.MIME, "document-format", format)
        jobAttrs(w)
        return send(w.finish(), file, 300_000, onProgress)
    }

    fun jobAttributes(jobId: Int): IppResponse {
        val w = IppWriter(IppOp.GET_JOB_ATTRIBUTES, nextId())
        op(w, jobId)
        w.strs(IppTag.KEYWORD, "requested-attributes", listOf("job-state", "job-state-reasons", "job-impressions-completed", "job-state-message"))
        return send(w.finish(), null, 8000, null)
    }

    fun cancelJob(jobId: Int) {
        val w = IppWriter(IppOp.CANCEL_JOB, nextId())
        op(w, jobId)
        send(w.finish(), null, 8000, null)
    }

    private fun send(body: ByteArray, data: File?, readTimeout: Int, onProgress: ((Float) -> Unit)?): IppResponse {
        val conn = URL(httpUrl).openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection) Tls.trustAll(conn)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.connectTimeout = 6000
            conn.readTimeout = readTimeout
            conn.setRequestProperty("Content-Type", "application/ipp")
            val total = body.size.toLong() + (data?.length() ?: 0L)
            conn.setFixedLengthStreamingMode(total)
            conn.outputStream.use { os ->
                os.write(body)
                if (data != null) {
                    val buf = ByteArray(65536)
                    var sent = body.size.toLong()
                    var lastReport = 0L
                    data.inputStream().use { ins ->
                        while (true) {
                            val r = ins.read(buf)
                            if (r < 0) break
                            os.write(buf, 0, r)
                            sent += r
                            if (sent - lastReport > 256 * 1024) {
                                lastReport = sent
                                onProgress?.invoke(sent.toFloat() / total)
                            }
                        }
                    }
                }
                os.flush()
            }
            val code = conn.responseCode
            if (code != 200) throw IOException("המדפסת החזירה HTTP $code")
            val resp = conn.inputStream.use { it.readBytes() }
            return IppParser.parse(resp)
        } finally {
            conn.disconnect()
        }
    }
}

object IppText {
    fun error(r: IppResponse): String {
        val msg = r.string("status-message")
        val base = when (r.status) {
            0x0400 -> "המדפסת לא הבינה את הבקשה"
            0x0401 -> "אין הרשאה להדפיס במדפסת זו"
            0x0403 -> "המדפסת דורשת התחברות"
            0x0406 -> "המדפסת לא נמצאה בכתובת זו"
            0x040A -> "המדפסת לא תומכת בפורמט שנשלח – נסה פורמט אחר בפרטי המדפסת"
            0x040B -> "המדפסת לא תומכת באחת ההגדרות שנבחרו"
            0x0500 -> "שגיאה פנימית במדפסת"
            0x0506 -> "המדפסת לא מקבלת עבודות כרגע"
            0x0507 -> "המדפסת עסוקה, נסה שוב בעוד רגע"
            else -> "שגיאת מדפסת"
        }
        return base + String.format(" (0x%04X)", r.status) + (if (!msg.isNullOrBlank()) "\n$msg" else "")
    }

    fun state(s: Int): String = when (s) {
        3 -> "פנויה"
        4 -> "מדפיסה"
        5 -> "עצורה"
        else -> "לא ידוע"
    }

    fun reason(r: String): String {
        val base = r.removeSuffix("-error").removeSuffix("-warning").removeSuffix("-report")
        return when (base) {
            "none" -> "תקין"
            "media-empty", "media-needed" -> "נגמר הנייר"
            "media-low" -> "מעט נייר"
            "media-jam" -> "נייר תקוע"
            "toner-low" -> "הטונר עומד להיגמר"
            "toner-empty" -> "נגמר הטונר"
            "marker-supply-low" -> "הדיו עומד להיגמר"
            "marker-supply-empty" -> "נגמר הדיו"
            "marker-waste-almost-full" -> "מיכל הפסולת כמעט מלא"
            "marker-waste-full" -> "מיכל הפסולת מלא"
            "door-open", "cover-open" -> "מכסה פתוח"
            "offline" -> "לא מקוונת"
            "paused" -> "מושהית"
            "shutdown" -> "כבויה"
            "input-tray-missing" -> "מגש נייר חסר"
            "output-tray-missing" -> "מגש יציאה חסר"
            "output-area-full" -> "מגש היציאה מלא"
            "spool-area-full" -> "זיכרון המדפסת מלא"
            "connecting-to-device" -> "מתחברת"
            "cleaning" -> "בניקוי"
            "other" -> "הודעה אחרת"
            else -> r
        }
    }

    fun format(f: String): String = when (f) {
        "auto" -> "אוטומטי"
        "application/pdf" -> "PDF"
        "image/pwg-raster" -> "PWG Raster"
        "image/urf" -> "Apple URF"
        "image/jpeg" -> "JPEG"
        else -> f
    }
}

fun capsFrom(r: IppResponse): PrinterCaps {
    val names = r.strings("marker-names")
    val levels = r.ints("marker-levels")
    val colors = r.strings("marker-colors")
    val markers = names.mapIndexed { i, n ->
        val c = colors.getOrNull(i)?.let { s ->
            runCatching { android.graphics.Color.parseColor(s.take(7)) }.getOrNull()
        }
        Marker(n, levels.getOrNull(i) ?: -1, c)
    }
    return PrinterCaps(
        name = r.string("printer-name") ?: "",
        model = r.string("printer-make-and-model") ?: r.string("printer-info") ?: "",
        formats = r.strings("document-format-supported"),
        sides = r.strings("sides-supported"),
        colorModes = r.strings("print-color-mode-supported"),
        media = r.strings("media-supported"),
        pwgRes = r.values("pwg-raster-document-resolution-supported").filterIsInstance<IppResolution>().map { it.x },
        pwgTypes = r.strings("pwg-raster-document-type-supported"),
        sheetBack = r.string("pwg-raster-document-sheet-back") ?: "normal",
        urf = r.strings("urf-supported"),
        copiesMax = r.values("copies-supported").filterIsInstance<IntRange>().firstOrNull()?.last ?: 1,
        mdh = r.strings("multiple-document-handling-supported"),
        qualities = r.ints("print-quality-supported"),
        scaling = r.strings("print-scaling-supported"),
        state = r.int("printer-state") ?: 0,
        reasons = r.strings("printer-state-reasons"),
        markers = markers,
    )
}
