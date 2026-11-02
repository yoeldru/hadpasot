// נכתב: 2026-11-02 21:24 (שעון ישראל)
package com.yoeldru.hadpasot

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

data class FoundPrinter(
    val name: String,
    val host: String,
    val port: Int,
    val path: String,
    val protocol: Protocol,
    val model: String,
    val source: String,
)

object Discovery {
    val defaultPaths = listOf("ipp/print", "ipp", "ipp/printer", "printer", "")

    /** גילוי מדפסות בשירות Bonjour / mDNS */
    fun mdns(ctx: Context): Flow<FoundPrinter> = callbackFlow {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        for (type in listOf("_ipp._tcp", "_ipps._tcp", "_pdl-datastream._tcp")) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { queue.trySend(info) }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                listeners.add(l)
            } catch (_: Exception) {
            }
        }
        val worker = launch {
            for (info in queue) {
                val r = withTimeoutOrNull(6000) { resolve(nsd, info) } ?: continue
                val host = hostOf(r) ?: continue
                val attrs = r.attributes ?: emptyMap()
                fun txt(k: String): String = attrs[k]?.let { String(it, Charsets.UTF_8) } ?: ""
                val type = r.serviceType ?: info.serviceType ?: ""
                val proto = when {
                    type.contains("_ipps") -> Protocol.IPPS
                    type.contains("_pdl") -> Protocol.RAW
                    else -> Protocol.IPP
                }
                send(
                    FoundPrinter(
                        name = r.serviceName ?: host,
                        host = host,
                        port = r.port,
                        path = if (proto == Protocol.RAW) "" else txt("rp").ifEmpty { "ipp/print" },
                        protocol = proto,
                        model = txt("ty"),
                        source = "Bonjour",
                    )
                )
            }
        }
        awaitClose {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            worker.cancel()
            queue.close()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(si: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(si)
                    }
                })
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    private fun hostOf(si: NsdServiceInfo): String? {
        val addr: InetAddress? = if (Build.VERSION.SDK_INT >= 34) {
            si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull()
        } else si.host
        return addr?.hostAddress
    }

    fun localPrefix(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val a = lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: return null
        return a.hostAddress?.substringBeforeLast('.')
    }

    private fun portOpen(host: String, port: Int, timeout: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeout); true }
    } catch (_: Exception) {
        false
    }

    /** בודק כתובת IP: מנסה IPP בנתיבים מקובלים, ואחר כך פורט 9100 */
    fun probe(host: String, port: Int = 631, path: String? = null, protocol: Protocol = Protocol.IPP): Pair<PrinterInfo, PrinterCaps?>? {
        if (protocol == Protocol.RAW) {
            return if (portOpen(host, port, 2500)) {
                Pair(PrinterInfo(newId(), "מדפסת $host", host, port, "", Protocol.RAW), null)
            } else null
        }
        val paths = if (path.isNullOrBlank()) defaultPaths else listOf(path.trim().trimStart('/'))
        for (pth in paths) {
            val p = PrinterInfo(newId(), host, host, port, pth, protocol)
            val r = runCatching { IppClient(p).getAttributes(3000) }.getOrNull() ?: continue
            if (!r.ok && r.status != 0x0001) continue
            val caps = capsFrom(r)
            val name = caps.model.ifBlank { caps.name }.ifBlank { "מדפסת $host" }
            return Pair(p.copy(name = name, model = caps.model), caps)
        }
        return null
    }

    /** סריקת הרשת המקומית (/24) לפורטים 631 ו-9100 */
    fun scanSubnet(ctx: Context, onProgress: (Float) -> Unit): Flow<FoundPrinter> = channelFlow {
        val prefix = localPrefix(ctx) ?: return@channelFlow
        val sem = Semaphore(40)
        var done = 0
        coroutineScope {
            for (i in 1..254) {
                launch(Dispatchers.IO) {
                    sem.withPermit {
                        val host = "$prefix.$i"
                        if (portOpen(host, 631, 450)) {
                            val r = probe(host)
                            if (r != null) {
                                send(FoundPrinter(r.first.name, host, 631, r.first.path, Protocol.IPP, r.first.model, "סריקת רשת"))
                            }
                        } else if (portOpen(host, 9100, 300)) {
                            send(FoundPrinter("מדפסת $host", host, 9100, "", Protocol.RAW, "", "סריקת רשת"))
                        }
                    }
                    synchronized(this@Discovery) { done++ }
                    onProgress(done / 254f)
                }
            }
        }
    }
}

object Converter {
    /** ממיר קובץ אופיס ל-PDF דרך שירות Apps Script של המשתמש */
    fun convert(src: File, name: String, mime: String, url: String, key: String, out: File) {
        if (url.isBlank()) throw IOException("כדי להדפיס קבצי וורד/אקסל/פאוורפוינט צריך להגדיר את שירות ההמרה (הגדרות ← שירות המרה)")
        val body = JSONObject()
            .put("key", key)
            .put("name", name)
            .put("mimeType", mime)
            .put("data", Base64.encodeToString(src.readBytes(), Base64.NO_WRAP))
            .toString().toByteArray(Charsets.UTF_8)
        var conn = URL(url.trim()).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15000
        conn.readTimeout = 240000
        conn.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        var code = conn.responseCode
        var hops = 0
        while (code in 300..399 && hops < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.readTimeout = 240000
            code = conn.responseCode
            hops++
        }
        if (code != 200) {
            conn.disconnect()
            throw IOException("שירות ההמרה החזיר שגיאה ($code)")
        }
        val txt = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()
        val j = try {
            JSONObject(txt)
        } catch (e: Exception) {
            throw IOException("תשובה לא תקינה משירות ההמרה – ודא שהפריסה היא 'אפליקציית אינטרנט' עם גישה ל'כל אחד'")
        }
        if (!j.optBoolean("ok")) throw IOException("ההמרה נכשלה: " + j.optString("error"))
        out.writeBytes(Base64.decode(j.getString("data"), Base64.DEFAULT))
    }

    suspend fun test(url: String, key: String): String = withContext(Dispatchers.IO) {
        try {
            val u = url.trim() + (if (url.contains('?')) "&" else "?") + "key=" + URLEncoder.encode(key, "UTF-8")
            var conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 200) return@withContext "שגיאה: HTTP $code"
            val j = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            conn.disconnect()
            if (j.optBoolean("ok")) "✔ שירות ההמרה מחובר ותקין" else "השירות ענה, אבל: " + j.optString("error")
        } catch (e: Exception) {
            "לא ניתן להתחבר: ${e.message}"
        }
    }
}

object Updater {
    const val REPO = "yoeldru/hadpasot"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/hadpasot.apk"

    data class Info(val version: Int, val url: String, val notes: String)

    fun check(): Info? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000; conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode != 200) return null
            val j = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val v = j.optString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: return null
            if (v <= BuildConfig.VERSION_CODE) return null
            val assets = j.optJSONArray("assets")
            var url = APK_URL
            if (assets != null) for (k in 0 until assets.length()) {
                val a = assets.getJSONObject(k)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url", url)
            }
            return Info(v, url, j.optString("body"))
        } finally {
            conn.disconnect()
        }
    }
}

fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()
