// נכתב: 2026-11-02 21:21 (שעון ישראל)
package com.yoeldru.hadpasot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class BackTransform { NONE, FLIP_X, FLIP_Y, ROTATE }

object SideRenderer {
    private const val MAX_CELL_PX = 5000f

    /**
     * מצייר צד גיליון לתמונה בגודל bw×bh.
     * rotate=true: צד לרוחב מסובב 90° נגד כיוון השעון לתוך דף לאורך (כמו landscape של IPP).
     */
    suspend fun render(
        side: Side, docs: List<PageDoc>, o: PrintOptions,
        bw: Int, bh: Int, rotate: Boolean, back: BackTransform = BackTransform.NONE,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(bw.coerceAtLeast(1), bh.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        when (back) {
            BackTransform.FLIP_X -> c.scale(-1f, 1f, bw / 2f, bh / 2f)
            BackTransform.FLIP_Y -> c.scale(1f, -1f, bw / 2f, bh / 2f)
            BackTransform.ROTATE -> c.rotate(180f, bw / 2f, bh / 2f)
            BackTransform.NONE -> {}
        }
        val s: Float
        if (rotate) {
            c.translate(0f, bh.toFloat())
            c.rotate(-90f)
            s = bh / side.w
        } else {
            s = bw / side.w
        }
        c.scale(s, s)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (o.color == ColorMode.MONO) {
            paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 0.6f; color = Color.GRAY
        }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; textSize = 8f; textAlign = Paint.Align.CENTER
        }

        for (cell in side.cells) {
            val p = cell.page
            if (p != null) {
                c.save()
                c.clipRect(cell.clip)
                val dw = cell.dest.width() * s
                val dh = cell.dest.height() * s
                var pw = if (cell.rotate) dh else dw
                var ph = if (cell.rotate) dw else dh
                val k = min(1f, MAX_CELL_PX / max(pw, ph).coerceAtLeast(1f))
                pw *= k; ph *= k
                val pb = docs[p.doc].render(p.index, pw.roundToInt().coerceAtLeast(1), ph.roundToInt().coerceAtLeast(1))
                val m = Matrix()
                if (cell.rotate) {
                    m.setRotate(90f)
                    m.postTranslate(pb.height.toFloat(), 0f)
                    m.postScale(cell.dest.width() / pb.height, cell.dest.height() / pb.width)
                } else {
                    m.setScale(cell.dest.width() / pb.width, cell.dest.height() / pb.height)
                }
                m.postTranslate(cell.dest.left, cell.dest.top)
                c.drawBitmap(pb, m, paint)
                pb.recycle()
                if (o.pageNumbers) {
                    val y = min(cell.dest.bottom, cell.clip.bottom) - 5f
                    c.drawText(p.number.toString(), cell.clip.centerX(), y, num)
                }
                c.restore()
            }
            if (o.borders && side.cells.size > 1) c.drawRect(cell.clip, border)
        }

        if (o.watermark.isNotBlank()) {
            val wp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(55, 120, 120, 120)
                textSize = side.w / 9f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            c.save()
            c.rotate(-35f, side.w / 2f, side.h / 2f)
            c.drawText(o.watermark, side.w / 2f, side.h / 2f + wp.textSize / 3f, wp)
            c.restore()
        }
        return bmp
    }
}
