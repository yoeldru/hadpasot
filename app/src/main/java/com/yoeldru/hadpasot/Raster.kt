// נכתב: 2026-11-02 21:11 (שעון ישראל)
package com.yoeldru.hadpasot

import android.graphics.Bitmap
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * כותב זרם רסטר למדפסות IPP Everywhere (PWG Raster) ו-AirPrint (Apple URF).
 * דחיסה: חזרת שורות + PackBits לפי פיקסלים, כפי שמוגדר בתקן PWG 5102.4.
 */
class RasterWriter(private val out: OutputStream, private val urf: Boolean, totalPages: Int) {
    private val total = totalPages

    init {
        if (urf) {
            out.write("UNIRAST".toByteArray(Charsets.US_ASCII)); out.write(0)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(totalPages).array())
        } else {
            out.write("RaS2".toByteArray(Charsets.US_ASCII))
        }
    }

    fun writePage(
        bmp: Bitmap, dpi: Int, gray: Boolean, paper: PaperSize,
        duplex: Boolean, tumble: Boolean, crossFeed: Int, feed: Int, quality: Int,
    ) {
        val w = bmp.width
        val h = bmp.height
        val bpp = if (gray) 1 else 3
        if (urf) {
            val b = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            b.put((bpp * 8).toByte())
            b.put((if (gray) 0 else 1).toByte())
            b.put((if (!duplex) 1 else if (tumble) 3 else 2).toByte())
            b.put(quality.toByte())
            b.putInt(0); b.putInt(0)
            b.putInt(w); b.putInt(h); b.putInt(dpi)
            b.putInt(0); b.putInt(0)
            out.write(b.array())
        } else {
            out.write(pwgHeader(w, h, dpi, gray, paper, duplex, tumble, crossFeed, feed, quality))
        }
        encodeBitmap(bmp, gray)
    }

    private fun pwgHeader(
        w: Int, h: Int, dpi: Int, gray: Boolean, paper: PaperSize,
        duplex: Boolean, tumble: Boolean, crossFeed: Int, feed: Int, quality: Int,
    ): ByteArray {
        val b = ByteBuffer.allocate(1796).order(ByteOrder.BIG_ENDIAN)
        fun str(off: Int, s: String) {
            val bytes = s.toByteArray(Charsets.US_ASCII)
            for (i in bytes.indices) if (i < 63) b.put(off + i, bytes[i])
        }
        str(0, "PwgRaster")
        b.putInt(272, if (duplex) 1 else 0)
        b.putInt(276, dpi); b.putInt(280, dpi)
        b.putInt(340, 1)
        b.putInt(352, paper.wPt.roundToInt()); b.putInt(356, paper.hPt.roundToInt())
        b.putInt(368, if (duplex && tumble) 1 else 0)
        b.putInt(372, w); b.putInt(376, h)
        b.putInt(384, 8)
        b.putInt(388, if (gray) 8 else 24)
        b.putInt(392, w * (if (gray) 1 else 3))
        b.putInt(396, 0)
        b.putInt(400, if (gray) 18 else 19)
        b.putInt(420, if (gray) 1 else 3)
        b.putInt(452, total)
        b.putInt(456, crossFeed)
        b.putInt(460, feed)
        b.putInt(464, 0); b.putInt(468, 0); b.putInt(472, w); b.putInt(476, h)
        b.putInt(480, 0x00FFFFFF)
        b.putInt(484, quality)
        str(1732, paper.ippName)
        return b.array()
    }

    private fun encodeBitmap(bmp: Bitmap, gray: Boolean) {
        val w = bmp.width
        val h = bmp.height
        val bpp = if (gray) 1 else 3
        val px = IntArray(w)
        var cur = ByteArray(w * bpp)
        var next = ByteArray(w * bpp)

        fun readRow(y: Int, dst: ByteArray) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            if (gray) {
                for (x in 0 until w) {
                    val c = px[x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val bl = c and 0xFF
                    dst[x] = ((r * 299 + g * 587 + bl * 114) / 1000).toByte()
                }
            } else {
                var j = 0
                for (x in 0 until w) {
                    val c = px[x]
                    dst[j++] = (c shr 16).toByte()
                    dst[j++] = (c shr 8).toByte()
                    dst[j++] = c.toByte()
                }
            }
        }

        var y = 0
        readRow(0, cur)
        while (y < h) {
            var rep = 0
            var haveNext = false
            while (y + rep + 1 < h) {
                readRow(y + rep + 1, next)
                if (rep < 255 && next.contentEquals(cur)) {
                    rep++
                } else {
                    haveNext = true
                    break
                }
            }
            out.write(rep)
            encodeLine(cur, w, bpp)
            y += rep + 1
            if (y < h) {
                if (haveNext) {
                    val t = cur; cur = next; next = t
                } else {
                    readRow(y, cur)
                }
            }
        }
    }

    private fun same(line: ByteArray, a: Int, b: Int, bpp: Int): Boolean {
        val ia = a * bpp
        val ib = b * bpp
        for (k in 0 until bpp) if (line[ia + k] != line[ib + k]) return false
        return true
    }

    private fun encodeLine(line: ByteArray, w: Int, bpp: Int) {
        var i = 0
        while (i < w) {
            var run = 1
            while (i + run < w && run < 128 && same(line, i, i + run, bpp)) run++
            if (run > 1) {
                out.write(run - 1)
                out.write(line, i * bpp, bpp)
                i += run
            } else {
                var cnt = 1
                while (i + cnt < w && cnt < 128) {
                    if (i + cnt + 1 < w && same(line, i + cnt, i + cnt + 1, bpp)) break
                    cnt++
                }
                if (cnt == 1) {
                    out.write(0)
                    out.write(line, i * bpp, bpp)
                } else {
                    out.write(257 - cnt)
                    out.write(line, i * bpp, cnt * bpp)
                }
                i += cnt
            }
        }
    }
}
