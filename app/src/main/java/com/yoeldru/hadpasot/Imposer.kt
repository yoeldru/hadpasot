// נכתב: 2026-11-02 21:18 (שעון ישראל)
package com.yoeldru.hadpasot

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** עמוד מקור: מסמך, אינדקס, מספר עמוד כולל (מ-1), גודל בנקודות */
class SrcPage(val doc: Int, val index: Int, val number: Int, val w: Float, val h: Float)

/** תא על צד גיליון: אזור חיתוך, מלבן הציור (יכול לחרוג מהחיתוך), וסיבוב 90° */
class Cell(val page: SrcPage?, val clip: RectF, val dest: RectF, val rotate: Boolean)

/** צד אחד של גיליון, במידות לוגיות בנקודות (לרוחב אם w>h) */
class Side(val w: Float, val h: Float, val cells: List<Cell>, val isBack: Boolean, val sheet: Int) {
    val landscape: Boolean get() = w > h
    val blank: Boolean get() = cells.all { it.page == null }
}

class Layout(
    val sides: List<Side>,
    val sheetCount: Int,
    val duplex: Boolean,
    val shortEdge: Boolean,
    val selectedPages: Int,
    val totalPages: Int,
    val booklet: Boolean,
)

object Imposer {
    private const val MM = 72f / 25.4f

    /** "1-3,5,8-" → מספרי עמודים (מ-1). מחזיר null אם ריק או לא תקין */
    fun parseRange(s: String, max: Int): List<Int>? {
        val t = s.replace(" ", "").replace('–', '-')
        if (t.isEmpty() || max == 0) return null
        val out = ArrayList<Int>()
        for (part in t.split(',', ';')) {
            if (part.isEmpty()) continue
            if (part.contains('-')) {
                val a = part.substringBefore('-').toIntOrNull() ?: 1
                val b = part.substringAfter('-').toIntOrNull() ?: max
                if (a <= b) for (k in a..b) { if (k in 1..max) out.add(k) }
                else for (k in a downTo b) { if (k in 1..max) out.add(k) }
            } else {
                val k = part.toIntOrNull() ?: return null
                if (k in 1..max) out.add(k)
            }
        }
        return if (out.isEmpty()) null else out
    }

    fun build(docs: List<PageDoc>, o: PrintOptions): Layout {
        val all = ArrayList<SrcPage>()
        var n = 1
        docs.forEachIndexed { di, d ->
            for (i in 0 until d.pageCount) {
                val sz = d.pageSize(i)
                all.add(SrcPage(di, i, n++, sz.width, sz.height))
            }
        }
        var sel: List<SrcPage> = all
        parseRange(o.range, all.size)?.let { r -> sel = r.map { all[it - 1] } }
        sel = when (o.subset) {
            PageSubset.ODD -> sel.filter { it.number % 2 == 1 }
            PageSubset.EVEN -> sel.filter { it.number % 2 == 0 }
            PageSubset.ALL -> sel
        }
        if (o.reverse) sel = sel.reversed()
        return if (o.booklet) booklet(sel, all.size, o) else nup(sel, all.size, o)
    }

    private fun place(p: SrcPage, cell: RectF, mode: ScaleMode, custom: Int, autoRotate: Boolean): Pair<RectF, Boolean> {
        val cw = cell.width()
        val ch = cell.height()
        val rot = autoRotate && abs(p.w - p.h) > 1f && ((p.w > p.h) != (cw > ch))
        val w = if (rot) p.h else p.w
        val h = if (rot) p.w else p.h
        val s = when (mode) {
            ScaleMode.FIT -> min(cw / w, ch / h)
            ScaleMode.FILL -> max(cw / w, ch / h)
            ScaleMode.ACTUAL -> 1f
            ScaleMode.CUSTOM -> custom.coerceIn(5, 400) / 100f
        }
        val dw = w * s
        val dh = h * s
        val left = cell.left + (cw - dw) / 2f
        val top = cell.top + (ch - dh) / 2f
        return Pair(RectF(left, top, left + dw, top + dh), rot)
    }

    private fun fitScore(pw: Float, ph: Float, cw: Float, ch: Float, autoRotate: Boolean): Float {
        val a = min(cw / pw, ch / ph)
        val b = if (autoRotate) min(cw / ph, ch / pw) else 0f
        return max(a, b)
    }

    private fun nup(sel: List<SrcPage>, total: Int, o: PrintOptions): Layout {
        val n = o.pagesPerSheet.coerceIn(1, 16)
        val pw = o.paper.wPt
        val ph = o.paper.hPt
        val m = o.marginMm * MM
        val gap = if (n > 1) 6f else 0f
        val rep = sel.firstOrNull()
        val repW = rep?.w ?: pw
        val repH = rep?.h ?: ph

        // בחירת כיוון דף ורשת (עמודות × שורות) שנותנים את ההגדלה הגדולה ביותר
        var bestLand = false
        var bestCols = 1
        var bestRows = n
        if (n > 1) {
            var bestScore = -1f
            val orients = when (o.orientation) {
                Orientation.PORTRAIT -> listOf(false)
                Orientation.LANDSCAPE -> listOf(true)
                Orientation.AUTO -> listOf(false, true)
            }
            for (land in orients) {
                val cw0 = if (land) ph else pw
                val ch0 = if (land) pw else ph
                for (cols in 1..n) {
                    if (n % cols != 0) continue
                    val rows = n / cols
                    val cw = (cw0 - 2 * m - gap * (cols - 1)) / cols
                    val chh = (ch0 - 2 * m - gap * (rows - 1)) / rows
                    if (cw <= 0f || chh <= 0f) continue
                    val sc = fitScore(repW, repH, cw, chh, o.autoRotate)
                    if (sc > bestScore + 0.0001f) {
                        bestScore = sc; bestLand = land; bestCols = cols; bestRows = rows
                    }
                }
            }
        }

        val sides = ArrayList<Side>()
        val chunks: List<List<SrcPage>> = if (sel.isEmpty()) listOf(emptyList()) else sel.chunked(n)
        chunks.forEachIndexed { idx, chunk ->
            val land = if (n == 1) {
                when (o.orientation) {
                    Orientation.PORTRAIT -> false
                    Orientation.LANDSCAPE -> true
                    Orientation.AUTO -> chunk.firstOrNull()?.let { it.w > it.h } ?: false
                }
            } else bestLand
            val sw = if (land) ph else pw
            val sh = if (land) pw else ph
            val cols = if (n == 1) 1 else bestCols
            val rows = if (n == 1) 1 else bestRows
            val cw = (sw - 2 * m - gap * (cols - 1)) / cols
            val chh = (sh - 2 * m - gap * (rows - 1)) / rows
            val cells = ArrayList<Cell>()
            for (k in 0 until n) {
                val byRows = o.nupOrder == NUpOrder.RTL_ROWS || o.nupOrder == NUpOrder.LTR_ROWS
                val rtl = o.nupOrder == NUpOrder.RTL_ROWS || o.nupOrder == NUpOrder.RTL_COLUMNS
                val row = if (byRows) k / cols else k % rows
                var col = if (byRows) k % cols else k / rows
                if (rtl) col = cols - 1 - col
                val left = m + col * (cw + gap)
                val top = m + row * (chh + gap)
                val clip = RectF(left, top, left + cw, top + chh)
                val p = chunk.getOrNull(k)
                if (p == null) {
                    cells.add(Cell(null, clip, clip, false))
                } else {
                    val mode = if (n == 1) o.scale else ScaleMode.FIT
                    val (dest, rot) = place(p, clip, mode, o.customScale, o.autoRotate)
                    cells.add(Cell(p, clip, dest, rot))
                }
            }
            sides.add(Side(sw, sh, cells, false, idx))
        }

        if (o.duplex == Duplex.NONE) {
            return Layout(sides, sides.size, false, false, sel.size, total, false)
        }
        val out = ArrayList<Side>()
        var sheet = 0
        var i = 0
        while (i < sides.size) {
            val f = sides[i]
            out.add(Side(f.w, f.h, f.cells, false, sheet))
            val b = sides.getOrNull(i + 1)
            if (b != null) out.add(Side(b.w, b.h, b.cells, true, sheet))
            else out.add(Side(f.w, f.h, emptyList(), true, sheet))
            sheet++
            i += 2
        }
        return Layout(out, sheet, true, o.duplex == Duplex.SHORT_EDGE, sel.size, total, false)
    }

    private fun booklet(sel: List<SrcPage>, total: Int, o: PrintOptions): Layout {
        val pw = o.paper.wPt
        val ph = o.paper.hPt
        val m = o.marginMm * MM
        val g = o.gutterMm * MM
        val sw = ph
        val sh = pw
        val leftCell = RectF(m, m, sw / 2f - g / 2f, sh - m)
        val rightCell = RectF(sw / 2f + g / 2f, m, sw - m, sh - m)
        val perSig = if (o.signatureSheets > 0) o.signatureSheets * 4 else Int.MAX_VALUE
        val chunks: List<List<SrcPage>> = if (sel.isEmpty()) listOf(emptyList()) else sel.chunked(min(perSig, sel.size))
        val sides = ArrayList<Side>()
        var sheet = 0

        fun cell(p: SrcPage?, r: RectF): Cell {
            if (p == null) return Cell(null, r, r, false)
            val (dest, rot) = place(p, r, ScaleMode.FIT, 100, o.autoRotate)
            return Cell(p, r, dest, rot)
        }

        for (chunk in chunks) {
            val count = max(4, ((chunk.size + 3) / 4) * 4)
            val pages: List<SrcPage?> = chunk + List(count - chunk.size) { null }
            for (s in 0 until count / 4) {
                val outerA = pages[count - 2 * s - 1]
                val outerB = pages[2 * s]
                val innerA = pages[2 * s + 1]
                val innerB = pages[count - 2 * s - 2]
                val front: List<Cell>
                val back: List<Cell>
                if (o.binding == Binding.LEFT) {
                    front = listOf(cell(outerA, leftCell), cell(outerB, rightCell))
                    back = listOf(cell(innerA, leftCell), cell(innerB, rightCell))
                } else {
                    front = listOf(cell(outerB, leftCell), cell(outerA, rightCell))
                    back = listOf(cell(innerB, leftCell), cell(innerA, rightCell))
                }
                sides.add(Side(sw, sh, front, false, sheet))
                sides.add(Side(sw, sh, back, true, sheet))
                sheet++
            }
        }
        return Layout(sides, sheet, true, true, sel.size, total, true)
    }
}
