package com.orangames.harmonica.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.orangames.harmonica.data.FrameMarks
import kotlin.math.max
import kotlin.math.min

data class SquareHit(
    val hole: Int,
    val bend: Int,
    val above: Boolean,
    val cx: Float,
    val cy: Float,
    val half: Float,
)

data class HudLayout(
    val width: Int,
    val height: Int,
    val holeX: FloatArray,
    val barTop: Float,
    val barBottom: Float,
    val sq: Float,
    val gap: Float,
    val cellW: Float,
    val keyCx: Float,
    val squares: List<SquareHit>,
)

private val ABOVE_COUNTS = intArrayOf(1, 1, 1, 1, 1, 1, 1, 2, 2, 3)
private val BELOW_COUNTS = intArrayOf(2, 3, 4, 2, 1, 2, 1, 1, 1, 1)

fun measureHud(videoW: Int): HudLayout {
    val width = min(videoW, max(280, (videoW * 0.94f).toInt())).coerceAtLeast(160)
    val cols = 11
    val gap = max(2, width / 140).toFloat()
    val cell = (width - gap * (cols + 1)) / cols
    val sq = max(12f, cell * 0.62f)
    val barH = max(28f, cell * 0.95f)
    val maxAbove = 3
    val maxBelow = 4
    val padY = max(8f, sq / 3f)
    val height = (padY + maxAbove * (sq + gap) + barH + maxBelow * (sq + gap) + padY).toInt()
    val centers = FloatArray(cols)
    var x = gap
    for (i in 0 until cols) {
        centers[i] = x + cell / 2f
        x += cell + gap
    }
    val holeX = FloatArray(10) { centers[it + 1] }
    val barTop = padY + maxAbove * (sq + gap)
    val barBottom = barTop + barH
    val squares = ArrayList<SquareHit>(40)
    for (h in 0 until 10) {
        for (i in 0 until ABOVE_COUNTS[h]) {
            val cy = barTop - gap - sq / 2f - i * (sq + gap)
            squares.add(SquareHit(h + 1, i, true, holeX[h], cy, sq / 2f))
        }
        for (i in 0 until BELOW_COUNTS[h]) {
            val cy = barBottom + gap + sq / 2f + i * (sq + gap)
            squares.add(SquareHit(h + 1, i, false, holeX[h], cy, sq / 2f))
        }
    }
    return HudLayout(width, height, holeX, barTop, barBottom, sq, gap, cell, centers[0], squares)
}

object HudPainter {
    private const val BLUE = 0xEB3AA8FF.toInt()
    private const val BROWN = 0xFFA67C3E.toInt()
    private const val BAR_EDGE = 0xFFF5F5F5.toInt()
    private const val CELL_BG = 0xFF0C0C0C.toInt()
    private const val NUMBER = 0xFFD2D2D2.toInt()
    private const val ABOVE = 0xFFFF3434.toInt()
    private const val ABOVE_EDGE = 0xFF961212.toInt()
    private const val BELOW = 0xFF46FF50.toInt()
    private const val BELOW_EDGE = 0xFF148C1E.toInt()

    fun barBitmap(videoW: Int, key: String, marks: FrameMarks?): Bitmap {
        val layout = measureHud(videoW)
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        drawHud(Canvas(bitmap), layout, 0f, 0f, key, marks)
        return bitmap
    }

    fun drawOnFrame(canvas: Canvas, videoW: Int, videoH: Int, key: String, marks: FrameMarks?) {
        val layout = measureHud(videoW)
        val x = (videoW - layout.width) / 2f
        val limit = (videoH - layout.height - 8).toFloat()
        val y = (videoH * 0.18f).coerceIn(8f, max(8f, limit))
        drawHud(canvas, layout, x, y, key, marks)
    }

    fun drawHud(
        canvas: Canvas,
        layout: HudLayout,
        ox: Float,
        oy: Float,
        key: String,
        marks: FrameMarks?,
    ) {
        val firstAbove = layout.squares.filter { it.above }.minOf { it.cy }
        val firstBelow = layout.squares.filter { !it.above }.maxOf { it.cy }
        val pillTop = oy + firstAbove - layout.sq / 2f - 6f
        val pillBot = oy + firstBelow + layout.sq / 2f + 6f
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }
        val radius = (pillBot - pillTop) / 2f
        canvas.drawRoundRect(RectF(ox + 2f, pillTop, ox + layout.width - 3f, pillBot), radius, radius, pill)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BROWN
            style = Paint.Style.FILL
        }
        val bar = RectF(ox + 4f, oy + layout.barTop, ox + layout.width - 5f, oy + layout.barBottom)
        canvas.drawRoundRect(bar, 6f, 6f, barPaint)
        barPaint.style = Paint.Style.STROKE
        barPaint.strokeWidth = 2f
        barPaint.color = BAR_EDGE
        canvas.drawRoundRect(bar, 6f, 6f, barPaint)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = max(12f, layout.sq * 0.92f)
        }
        val labels = ArrayList<String>(11)
        labels.add(key)
        for (n in 1..10) labels.add(if (n < 10) n.toString() else "0")
        val centers = FloatArray(11)
        centers[0] = layout.keyCx
        layout.holeX.copyInto(centers, 1)
        for (i in labels.indices) {
            val cx = ox + centers[i]
            val left = cx - layout.cellW * 0.38f
            val right = cx + layout.cellW * 0.38f
            val top = oy + layout.barTop + 4f
            val bot = oy + layout.barBottom - 4f
            val cell = Paint(Paint.ANTI_ALIAS_FLAG)
            if (i == 0) {
                cell.color = BROWN
                canvas.drawRect(left, top, right, bot, cell)
                text.color = Color.WHITE
            } else {
                cell.color = CELL_BG
                canvas.drawRoundRect(RectF(left, top, right, bot), 3f, 3f, cell)
                text.color = NUMBER
            }
            val fm = text.fontMetrics
            val ty = (top + bot) / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(labels[i], cx, ty, text)
        }

        val square = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF141414.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        for (hit in layout.squares) {
            val box = RectF(
                ox + hit.cx - hit.half,
                oy + hit.cy - hit.half,
                ox + hit.cx + hit.half,
                oy + hit.cy + hit.half,
            )
            canvas.drawRoundRect(box, 2f, 2f, square)
            canvas.drawRoundRect(box, 2f, 2f, edge)
        }

        if (marks == null || marks.holes.isEmpty()) return
        val lit = layout.squares.filter { hit ->
            val breath = if (hit.above) "blow" else "draw"
            marks.lit(hit.hole, hit.bend, breath)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        for ((keyPair, group) in lit.groupBy { it.above to it.bend }) {
            val sorted = group.sortedBy { it.hole }
            val run = ArrayList<SquareHit>()
            fun flush() {
                if (run.isEmpty()) return
                val xs = run.map { it.cx }
                val ys = run.map { it.cy }
                val cx = ox + (xs.min() + xs.max()) / 2f
                val cy = oy + ys.average().toFloat()
                val halfX = layout.sq / 2f + (xs.max() - xs.min()) / 2f + 2f
                val halfY = layout.sq / 2f + 1f
                val above = keyPair.first
                fill.color = if (above) ABOVE else BELOW
                stroke.color = if (above) ABOVE_EDGE else BELOW_EDGE
                val oval = RectF(cx - halfX, cy - halfY, cx + halfX, cy + halfY)
                canvas.drawOval(oval, fill)
                canvas.drawOval(oval, stroke)
                run.clear()
            }
            for (hit in sorted) {
                if (run.isNotEmpty() && hit.hole != run.last().hole + 1) flush()
                run.add(hit)
            }
            flush()
        }
    }
}
