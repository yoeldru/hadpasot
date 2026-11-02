// נכתב: 2026-11-02 21:27 (שעון ישראל)
package com.yoeldru.hadpasot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("hadpasot", Context.MODE_PRIVATE)

    private fun arr(key: String): JSONArray = runCatching { JSONArray(sp.getString(key, "[]")) }.getOrDefault(JSONArray())
    private fun obj(key: String): JSONObject? = sp.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun printers(): List<PrinterInfo> = arr("printers").let { a -> (0 until a.length()).map { PrinterInfo.fromJson(a.getJSONObject(it)) } }
    fun savePrinters(list: List<PrinterInfo>) =
        sp.edit().putString("printers", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()

    fun target(): String = sp.getString("target", "pdf") ?: "pdf"
    fun saveTarget(t: String) = sp.edit().putString("target", t).apply()

    fun settings(): AppSettings = AppSettings.fromJson(obj("settings"))
    fun saveSettings(s: AppSettings) = sp.edit().putString("settings", s.toJson().toString()).apply()

    fun defaultOptions(): PrintOptions = PrintOptions.fromJson(obj("defaultOptions"))
    fun saveDefaultOptions(o: PrintOptions) = sp.edit().putString("defaultOptions", o.toJson().toString()).apply()

    fun history(): List<HistoryEntry> = arr("history").let { a ->
        (0 until a.length()).mapNotNull { runCatching { HistoryEntry.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }
    fun saveHistory(list: List<HistoryEntry>) =
        sp.edit().putString("history", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()

    fun presets(): List<Preset> {
        if (!sp.contains("presets")) return builtInPresets()
        val a = arr("presets")
        return (0 until a.length()).map {
            val j = a.getJSONObject(it)
            Preset(j.optString("name"), PrintOptions.fromJson(j.optJSONObject("options")))
        }
    }
    fun savePresets(list: List<Preset>) = sp.edit().putString(
        "presets",
        JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("options", it.options.toJson())) } }.toString()
    ).apply()

    fun seenUpdate(): Int = sp.getInt("seenUpdate", 0)
    fun saveSeenUpdate(v: Int) = sp.edit().putInt("seenUpdate", v).apply()

    private fun builtInPresets() = listOf(
        Preset("רגיל", PrintOptions()),
        Preset("דו-צדדי", PrintOptions(duplex = Duplex.LONG_EDGE)),
        Preset("דף עבודה חסכוני (2 בדף, דו-צדדי, ש/ל)", PrintOptions(pagesPerSheet = 2, duplex = Duplex.SHORT_EDGE, color = ColorMode.MONO)),
        Preset("חוברת", PrintOptions(booklet = true)),
        Preset("תמונה על כל הדף", PrintOptions(scale = ScaleMode.FILL, quality = Quality.HIGH)),
        Preset("טיוטה בשחור-לבן", PrintOptions(color = ColorMode.MONO, quality = Quality.DRAFT)),
    )
}
