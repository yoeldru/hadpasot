// נכתב: 2026-11-02 21:05 (שעון ישראל)
// עודכן: 2026-11-04 17:50 (שעון ישראל) – סימוני קיפול וסיכות בחוברת, כפתורים נבחרים בולטים
package com.yoeldru.hadpasot

import org.json.JSONArray
import org.json.JSONObject

enum class PaperSize(val label: String, val wMm: Float, val hMm: Float, val ippName: String) {
    A4("A4", 210f, 297f, "iso_a4_210x297mm"),
    A5("A5", 148f, 210f, "iso_a5_148x210mm"),
    A3("A3", 297f, 420f, "iso_a3_297x420mm"),
    B5("B5", 176f, 250f, "iso_b5_176x250mm"),
    LETTER("Letter", 215.9f, 279.4f, "na_letter_8.5x11in"),
    LEGAL("Legal", 215.9f, 355.6f, "na_legal_8.5x14in");

    val wPt: Float get() = wMm / 25.4f * 72f
    val hPt: Float get() = hMm / 25.4f * 72f
}

enum class Orientation(val label: String) { AUTO("אוטומטי"), PORTRAIT("לאורך"), LANDSCAPE("לרוחב") }

enum class Duplex(val label: String) {
    NONE("חד-צדדי"),
    LONG_EDGE("דו-צדדי · היפוך בצד הארוך"),
    SHORT_EDGE("דו-צדדי · היפוך בצד הקצר")
}

enum class DuplexMethod(val label: String) { AUTO("אוטומטי"), PRINTER("ע\"י המדפסת"), MANUAL("ידני – הפיכת ערימה") }

enum class PageSubset(val label: String) { ALL("כל העמודים"), ODD("אי-זוגיים"), EVEN("זוגיים") }

enum class ScaleMode(val label: String) { FIT("התאמה לדף"), ACTUAL("גודל מקורי"), FILL("מילוי הדף"), CUSTOM("אחוז") }

enum class NUpOrder(val label: String) {
    RTL_ROWS("מימין לשמאל"),
    LTR_ROWS("משמאל לימין"),
    RTL_COLUMNS("עמודות מימין"),
    LTR_COLUMNS("עמודות משמאל")
}

enum class ColorMode(val label: String) { COLOR("צבע"), MONO("שחור-לבן") }

enum class Quality(val label: String, val ipp: Int) { DRAFT("טיוטה", 3), NORMAL("רגילה", 4), HIGH("גבוהה", 5) }

enum class Binding(val label: String) { RIGHT("כריכה מימין (עברית)"), LEFT("כריכה משמאל (לועזית)") }

enum class Protocol(val label: String) { IPP("IPP"), IPPS("IPPS"), RAW("RAW 9100") }

enum class Kind { PDF, IMAGE, TEXT, OFFICE }

inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: def

data class PrintOptions(
    val copies: Int = 1,
    val collate: Boolean = true,
    val range: String = "",
    val subset: PageSubset = PageSubset.ALL,
    val reverse: Boolean = false,
    val paper: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.AUTO,
    val duplex: Duplex = Duplex.NONE,
    val duplexMethod: DuplexMethod = DuplexMethod.AUTO,
    val pagesPerSheet: Int = 1,
    val nupOrder: NUpOrder = NUpOrder.RTL_ROWS,
    val borders: Boolean = false,
    val autoRotate: Boolean = true,
    val scale: ScaleMode = ScaleMode.FIT,
    val customScale: Int = 100,
    val marginMm: Float = 0f,
    val booklet: Boolean = false,
    val binding: Binding = Binding.RIGHT,
    val signatureSheets: Int = 0,
    val gutterMm: Float = 0f,
    val color: ColorMode = ColorMode.COLOR,
    val quality: Quality = Quality.NORMAL,
    val pageNumbers: Boolean = false,
    val watermark: String = "",
    val foldLine: Boolean = true,
    val stapleMarks: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("copies", copies); put("collate", collate); put("range", range)
        put("subset", subset.name); put("reverse", reverse); put("paper", paper.name)
        put("orientation", orientation.name); put("duplex", duplex.name); put("duplexMethod", duplexMethod.name)
        put("pps", pagesPerSheet); put("nupOrder", nupOrder.name); put("borders", borders)
        put("autoRotate", autoRotate); put("scale", scale.name); put("customScale", customScale)
        put("margin", marginMm.toDouble()); put("booklet", booklet); put("binding", binding.name)
        put("sig", signatureSheets); put("gutter", gutterMm.toDouble()); put("color", color.name)
        put("quality", quality.name); put("pageNumbers", pageNumbers); put("watermark", watermark)
        put("foldLine", foldLine); put("stapleMarks", stapleMarks)
    }

    companion object {
        fun fromJson(j: JSONObject?): PrintOptions {
            val d = PrintOptions()
            if (j == null) return d
            return PrintOptions(
                copies = j.optInt("copies", d.copies),
                collate = j.optBoolean("collate", d.collate),
                range = j.optString("range", d.range),
                subset = enumOr(j.optString("subset"), d.subset),
                reverse = j.optBoolean("reverse", d.reverse),
                paper = enumOr(j.optString("paper"), d.paper),
                orientation = enumOr(j.optString("orientation"), d.orientation),
                duplex = enumOr(j.optString("duplex"), d.duplex),
                duplexMethod = enumOr(j.optString("duplexMethod"), d.duplexMethod),
                pagesPerSheet = j.optInt("pps", d.pagesPerSheet),
                nupOrder = enumOr(j.optString("nupOrder"), d.nupOrder),
                borders = j.optBoolean("borders", d.borders),
                autoRotate = j.optBoolean("autoRotate", d.autoRotate),
                scale = enumOr(j.optString("scale"), d.scale),
                customScale = j.optInt("customScale", d.customScale),
                marginMm = j.optDouble("margin", 0.0).toFloat(),
                booklet = j.optBoolean("booklet", d.booklet),
                binding = enumOr(j.optString("binding"), d.binding),
                signatureSheets = j.optInt("sig", d.signatureSheets),
                gutterMm = j.optDouble("gutter", 0.0).toFloat(),
                color = enumOr(j.optString("color"), d.color),
                quality = enumOr(j.optString("quality"), d.quality),
                pageNumbers = j.optBoolean("pageNumbers", d.pageNumbers),
                watermark = j.optString("watermark", d.watermark),
                foldLine = j.optBoolean("foldLine", d.foldLine),
                stapleMarks = j.optBoolean("stapleMarks", d.stapleMarks),
            )
        }
    }
}

data class PrinterInfo(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val path: String,
    val protocol: Protocol,
    val model: String = "",
    val format: String = "auto",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port)
        put("path", path); put("protocol", protocol.name); put("model", model); put("format", format)
    }

    val address: String get() = if (protocol == Protocol.RAW) "$host:$port" else "$host:$port/$path"

    companion object {
        fun fromJson(j: JSONObject) = PrinterInfo(
            id = j.optString("id"),
            name = j.optString("name"),
            host = j.optString("host"),
            port = j.optInt("port", 631),
            path = j.optString("path", "ipp/print"),
            protocol = enumOr(j.optString("protocol"), Protocol.IPP),
            model = j.optString("model"),
            format = j.optString("format", "auto"),
        )
    }
}

data class Marker(val name: String, val level: Int, val color: Int?)

data class PrinterCaps(
    val name: String = "",
    val model: String = "",
    val formats: List<String> = emptyList(),
    val sides: List<String> = emptyList(),
    val colorModes: List<String> = emptyList(),
    val media: List<String> = emptyList(),
    val pwgRes: List<Int> = emptyList(),
    val pwgTypes: List<String> = emptyList(),
    val sheetBack: String = "normal",
    val urf: List<String> = emptyList(),
    val copiesMax: Int = 1,
    val mdh: List<String> = emptyList(),
    val qualities: List<Int> = emptyList(),
    val scaling: List<String> = emptyList(),
    val state: Int = 0,
    val reasons: List<String> = emptyList(),
    val markers: List<Marker> = emptyList(),
) {
    val canDuplex: Boolean get() = sides.any { it.startsWith("two-sided") }
    val canColor: Boolean get() = colorModes.isEmpty() || colorModes.any { it.contains("color") }
}

data class JobFile(val path: String, val name: String, val kind: Kind) {
    fun toJson(): JSONObject = JSONObject().put("path", path).put("name", name).put("kind", kind.name)

    companion object {
        fun fromJson(j: JSONObject) = JobFile(j.optString("path"), j.optString("name"), enumOr(j.optString("kind"), Kind.PDF))
    }
}

data class HistoryEntry(
    val id: String,
    val time: Long,
    val title: String,
    val target: String,
    val sheets: Int,
    val pages: Int,
    val ok: Boolean,
    val note: String,
    val dir: String,
    val files: List<JobFile>,
    val options: PrintOptions,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("time", time); put("title", title); put("target", target)
        put("sheets", sheets); put("pages", pages); put("ok", ok); put("note", note); put("dir", dir)
        put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
        put("options", options.toJson())
    }

    companion object {
        fun fromJson(j: JSONObject): HistoryEntry {
            val fa = j.optJSONArray("files") ?: JSONArray()
            return HistoryEntry(
                id = j.optString("id"), time = j.optLong("time"), title = j.optString("title"),
                target = j.optString("target"), sheets = j.optInt("sheets"), pages = j.optInt("pages"),
                ok = j.optBoolean("ok"), note = j.optString("note"), dir = j.optString("dir"),
                files = (0 until fa.length()).map { JobFile.fromJson(fa.getJSONObject(it)) },
                options = PrintOptions.fromJson(j.optJSONObject("options")),
            )
        }
    }
}

data class Preset(val name: String, val options: PrintOptions)

data class AppSettings(
    val convertUrl: String = "",
    val convertKey: String = "",
    val maxDpi: Int = 300,
    val pdfDpi: Int = 200,
    val manualBackReverse: Boolean = true,
    val manualBackRotate: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("convertUrl", convertUrl); put("convertKey", convertKey); put("maxDpi", maxDpi)
        put("pdfDpi", pdfDpi); put("backReverse", manualBackReverse); put("backRotate", manualBackRotate)
    }

    companion object {
        fun fromJson(j: JSONObject?): AppSettings {
            val d = AppSettings()
            if (j == null) return d
            return AppSettings(
                convertUrl = j.optString("convertUrl"),
                convertKey = j.optString("convertKey"),
                maxDpi = j.optInt("maxDpi", d.maxDpi),
                pdfDpi = j.optInt("pdfDpi", d.pdfDpi),
                manualBackReverse = j.optBoolean("backReverse", d.manualBackReverse),
                manualBackRotate = j.optBoolean("backRotate", d.manualBackRotate),
            )
        }
    }
}

data class Msg(val title: String, val text: String = "", val ok: Boolean = true)
