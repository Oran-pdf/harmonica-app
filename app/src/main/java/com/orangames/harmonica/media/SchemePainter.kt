package com.orangames.harmonica.media

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.orangames.harmonica.data.FrameMarks
import com.orangames.harmonica.data.Look
import com.orangames.harmonica.data.NoteEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

object SchemePainter {
    private const val BLOW = 0xFFFF4B3C.toInt()
    private const val DRAW = 0xFF3DDC6A.toInt()
    private const val ENTER = 0.18
    private const val EXIT = 0.48

    fun drawOnFrame(
        canvas: Canvas,
        videoW: Int,
        videoH: Int,
        key: String,
        time: Double,
        events: List<NoteEvent>,
        look: Look,
    ) {
        if (look.id == "simple") {
            HudPainter.drawOnFrame(canvas, videoW, videoH, key, marksAt(events, time))
            return
        }
        val layout = measureHud(videoW)
        val x = (videoW - layout.width) / 2f
        val limit = (videoH - layout.height - 8).toFloat()
        val y = (videoH * 0.18f).coerceIn(8f, max(8f, limit))
        draw(canvas, layout, x, y, key, time, events, look)
    }

    fun draw(
        canvas: Canvas,
        layout: HudLayout,
        ox: Float,
        oy: Float,
        key: String,
        time: Double,
        events: List<NoteEvent>,
        look: Look,
        animate: Boolean = true,
    ) {
        if (look.id == "simple") {
            HudPainter.drawHud(canvas, layout, ox, oy, key, marksAt(events, time))
            return
        }
        val shown = if (animate) look else look.copy(motion = 0)
        drawBackdrop(canvas, layout, ox, oy, shown.backdrop)
        drawHarp(canvas, layout, ox, oy, key, shown.harp)
        val litIds = HashSet<String>()
        for (event in events) {
            if (time < event.t0 || time >= event.t1) continue
            for (hit in hits(layout, event)) litIds.add(idOf(hit))
        }
        for (hit in layout.squares) {
            if (idOf(hit) in litIds) continue
            drawIdle(canvas, ox + hit.cx, oy + hit.cy, hit.half, shown.idle, shown.harp)
        }
        for (event in events) {
            val age = time - event.t0
            val since = time - event.t1
            val active = time >= event.t0 && time < event.t1
            val leaving = animate && since in 0.0..EXIT
            if (!active && !leaving) continue
            val enter = if (active) (age / ENTER).coerceIn(0.0, 1.0).toFloat() else 1f
            val exit = if (leaving) (since / EXIT).coerceIn(0.0, 1.0).toFloat() else 0f
            val group = hits(layout, event)
            if (group.isEmpty()) continue
            if (active || shown.motion == 6 || shown.motion == 7) {
                drawLitGroup(canvas, layout, ox, oy, group, shown, enter, exit, time, active)
            }
            if (leaving) {
                drawMotion(canvas, layout, ox, oy, group, shown.motion, exit, event.breath == "blow")
            }
        }
    }

    private fun marksAt(events: List<NoteEvent>, time: Double): FrameMarks? {
        val event = events.firstOrNull { time >= it.t0 && time < it.t1 } ?: return null
        return FrameMarks(event.breath, event.holes)
    }

    private fun idOf(hit: SquareHit) = "${hit.hole}:${hit.bend}:${hit.above}"

    private fun hits(layout: HudLayout, event: NoteEvent): List<SquareHit> {
        return layout.squares.filter { hit ->
            val breath = if (hit.above) "blow" else "draw"
            event.breath == breath && event.holes.any { it.hole == hit.hole && it.bend == hit.bend }
        }
    }

    private fun drawBackdrop(canvas: Canvas, layout: HudLayout, ox: Float, oy: Float, kind: Int) {
        if (kind < 0) return
        val pad = layout.sq * 1.15f
        val left = ox - pad
        val top = oy - pad * 0.55f
        val right = ox + layout.width + pad
        val bottom = oy + layout.height + pad * 0.7f
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val w = right - left
        val h = bottom - top
        canvas.save()
        val clip = Path()
        clip.addCircle(left + w * 0.22f, cy, h * 0.48f, Path.Direction.CW)
        clip.addCircle(cx, cy - h * 0.08f, h * 0.55f, Path.Direction.CW)
        clip.addCircle(right - w * 0.22f, cy, h * 0.48f, Path.Direction.CW)
        clip.addOval(RectF(left + w * 0.08f, top + h * 0.18f, right - w * 0.08f, bottom - h * 0.08f), Path.Direction.CW)
        canvas.clipPath(clip)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (kind) {
            0 -> parchment(canvas, paint, left, top, right, bottom)
            1 -> smoke(canvas, paint, left, top, right, bottom)
            2 -> walnut(canvas, paint, left, top, right, bottom)
            3 -> mist(canvas, paint, left, top, right, bottom)
            4 -> velvet(canvas, paint, left, top, right, bottom)
            5 -> aurora(canvas, paint, left, top, right, bottom)
            6 -> ember(canvas, paint, left, top, right, bottom)
            else -> festival(canvas, paint, cx, cy, w, h)
        }
        canvas.restore()
    }

    private fun fill(canvas: Canvas, paint: Paint, color: Int, rect: RectF) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawOval(rect, paint)
    }

    private fun softWash(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float, topColor: Int, bottomColor: Int, glow: Int) {
        paint.shader = LinearGradient(left, top, left, bottom, topColor, bottomColor, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawRect(left, top, right, bottom, paint)
        paint.shader = null
        paint.color = glow
        val w = right - left
        val h = bottom - top
        canvas.drawOval(RectF(left + w * 0.18f, top + h * 0.22f, right - w * 0.12f, bottom - h * 0.18f), paint)
    }

    private fun parchment(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        softWash(canvas, paint, left, top, right, bottom, 0xD8E6D2B4.toInt(), 0xC8B89A72.toInt(), 0x44C4A57A.toInt())
    }

    private fun smoke(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        softWash(canvas, paint, left, top, right, bottom, 0xC83A342E.toInt(), 0xC0161310.toInt(), 0x335C534A.toInt())
    }

    private fun walnut(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        softWash(canvas, paint, left, top, right, bottom, 0xD86A4630.toInt(), 0xC8322016.toInt(), 0x44C4A07A.toInt())
    }

    private fun mist(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        softWash(canvas, paint, left, top, right, bottom, 0xC8D4D6DA.toInt(), 0xB8A8B0B6.toInt(), 0x33586870.toInt())
    }

    private fun velvet(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        softWash(canvas, paint, left, top, right, bottom, 0xD84A3430.toInt(), 0xC21A1210.toInt(), 0x33684840.toInt())
    }

    private fun aurora(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        val h = bottom - top
        fill(canvas, paint, 0xCC6C4DFF.toInt(), RectF(left, top + h * 0.15f, right, top + h * 0.72f))
        fill(canvas, paint, 0xCC14C8C2.toInt(), RectF(left + (right - left) * 0.15f, top, right, top + h * 0.55f))
        fill(canvas, paint, 0xDDF0C14A.toInt(), RectF(left, top + h * 0.42f, right - (right - left) * 0.1f, bottom))
    }

    private fun ember(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        val w = right - left
        val h = bottom - top
        fill(canvas, paint, 0xE6C4232A.toInt(), RectF(left, top + h * 0.2f, left + w * 0.7f, bottom))
        fill(canvas, paint, 0xEEF06A22.toInt(), RectF(left + w * 0.28f, top, right, top + h * 0.75f))
        fill(canvas, paint, 0xEEF6C14A.toInt(), RectF(left + w * 0.4f, top + h * 0.35f, right, bottom))
    }

    private fun festival(canvas: Canvas, paint: Paint, cx: Float, cy: Float, w: Float, h: Float) {
        val colors = intArrayOf(
            0xEEFF4B6A.toInt(),
            0xEEFFD23A.toInt(),
            0xEE2EE7FF.toInt(),
            0xEEB06BFF.toInt(),
            0xEE3DDC6A.toInt(),
            0xEEFF8A2A.toInt(),
        )
        val reach = max(w, h) * 0.55f
        paint.style = Paint.Style.FILL
        for (i in colors.indices) {
            val a0 = -PI.toFloat() * 0.15f + (i / colors.size.toFloat()) * PI.toFloat() * 1.15f
            val a1 = a0 + PI.toFloat() * 1.15f / colors.size * 0.82f
            val path = Path()
            path.moveTo(cx, cy)
            path.lineTo(cx + cos(a0) * reach, cy + sin(a0) * reach * 0.62f)
            path.quadTo(
                cx + cos((a0 + a1) / 2f) * reach * 1.15f,
                cy + sin((a0 + a1) / 2f) * reach * 0.75f,
                cx + cos(a1) * reach,
                cy + sin(a1) * reach * 0.62f,
            )
            path.close()
            paint.color = colors[i]
            canvas.drawPath(path, paint)
        }
    }

    private fun drawHarp(canvas: Canvas, layout: HudLayout, ox: Float, oy: Float, key: String, harp: Int) {
        val bar = RectF(ox + 4f, oy + layout.barTop, ox + layout.width - 5f, oy + layout.barBottom)
        val radius = (bar.height() / 2f).coerceAtMost(18f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (harp) {
            1 -> paint.shader = LinearGradient(bar.left, bar.top, bar.left, bar.bottom, 0xFF8A5A2C.toInt(), 0xFFF0D7A2.toInt(), Shader.TileMode.CLAMP)
            2 -> paint.shader = LinearGradient(bar.left, bar.top, bar.left, bar.bottom, 0xFF4A3424.toInt(), 0xFF1A120C.toInt(), Shader.TileMode.CLAMP)
            3 -> paint.color = 0xFFF4EFE4.toInt()
            4 -> paint.color = 0xFF101014.toInt()
            5 -> paint.color = 0x66FFFFFF
            6 -> paint.shader = LinearGradient(bar.left, bar.top, bar.left, bar.bottom, 0xFF8C3E24.toInt(), 0xFFE7A070.toInt(), Shader.TileMode.CLAMP)
            7 -> paint.shader = LinearGradient(bar.left, bar.top, bar.left, bar.bottom, 0xFF243044.toInt(), 0xFF101826.toInt(), Shader.TileMode.CLAMP)
            else -> paint.color = 0xFFA67C3E.toInt()
        }
        canvas.drawRoundRect(bar, radius, radius, paint)
        if (harp == 1 || harp == 6) {
            paint.shader = null
            paint.color = 0x55FFFFFF
            canvas.drawRoundRect(
                RectF(bar.left + 10f, bar.top + bar.height() * 0.14f, bar.right - 10f, bar.top + bar.height() * 0.36f),
                radius,
                radius,
                paint,
            )
        }
        if (harp == 4 || harp == 5 || harp == 2) {
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = if (harp == 2) 0x66E7B56A else 0xFFD9D3C8.toInt()
            canvas.drawRoundRect(bar, radius, radius, paint)
            paint.style = Paint.Style.FILL
        }
        val dark = harp == 0 || harp == 1 || harp == 3 || harp == 6
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) 0xFF2A180C.toInt() else Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = max(12f, layout.sq * 0.9f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val labels = ArrayList<String>(11)
        labels.add(key)
        for (n in 1..10) labels.add(if (n < 10) n.toString() else "0")
        val centers = FloatArray(11)
        centers[0] = layout.keyCx
        layout.holeX.copyInto(centers, 1)
        val fm = text.fontMetrics
        val ty = (bar.top + bar.bottom) / 2f - (fm.ascent + fm.descent) / 2f
        for (i in labels.indices) canvas.drawText(labels[i], ox + centers[i], ty, text)
    }

    private fun drawIdle(canvas: Canvas, x: Float, y: Float, half: Float, idle: Int, harp: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (idle) {
            1 -> {
                paint.color = 0x59FFFFFF
                canvas.drawCircle(x, y, half * 0.55f, paint)
            }
            2 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = if (harp == 3) 0xFF3A2A1C.toInt() else 0xAAFFFFFF.toInt()
                canvas.drawCircle(x, y, half * 0.62f, paint)
            }
            3 -> {
                paint.color = 0x66000000
                canvas.drawCircle(x, y, half * 0.62f, paint)
            }
            4 -> {
                paint.color = 0xFF5C4636.toInt()
                canvas.drawCircle(x, y, half * 0.42f, paint)
            }
            5 -> {
                paint.color = 0xFF1A1A1A.toInt()
                canvas.drawCircle(x, y, half * 0.22f, paint)
            }
            6 -> {
                paint.strokeWidth = max(2f, half * 0.18f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = if (harp == 3) 0xFF3A2A1C.toInt() else 0x99FFFFFF.toInt()
                canvas.drawLine(x - half * 0.45f, y, x + half * 0.45f, y, paint)
            }
            7 -> Unit
            else -> {
                paint.color = Color.WHITE
                val box = RectF(x - half, y - half, x + half, y + half)
                canvas.drawRoundRect(box, 2f, 2f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = 0xFF141414.toInt()
                canvas.drawRoundRect(box, 2f, 2f, paint)
            }
        }
    }

    private fun drawLitGroup(
        canvas: Canvas,
        layout: HudLayout,
        ox: Float,
        oy: Float,
        group: List<SquareHit>,
        look: Look,
        enter: Float,
        exit: Float,
        time: Double,
        active: Boolean,
    ) {
        val rows = group.groupBy { it.above to it.bend }
        for ((_, row) in rows) {
            val sorted = row.sortedBy { it.hole }
            val run = ArrayList<SquareHit>()
            fun flush() {
                if (run.isEmpty()) return
                val xs = run.map { ox + it.cx }
                val cy = oy + run.map { it.cy }.average().toFloat()
                val scale = bodyScale(look.motion, enter, exit, active)
                val alpha = bodyAlpha(look.motion, enter, exit, active, time, run.first().hole)
                if (look.lit in 1..5 && run.size >= 2) {
                    val radius = (layout.sq / 2f + 1f) * scale
                    drawLitCapsule(canvas, xs.min(), xs.max(), cy, radius, run.first().above, look.lit, alpha)
                } else {
                    val cx = (xs.min() + xs.max()) / 2f
                    val halfX = layout.sq / 2f + (xs.max() - xs.min()) / 2f + 2f
                    val halfY = layout.sq / 2f + 1f
                    drawLit(canvas, cx, cy, halfX * scale, halfY * scale, run.first().above, look.lit, alpha)
                }
                run.clear()
            }
            for (hit in sorted) {
                if (run.isNotEmpty() && hit.hole != run.last().hole + 1) flush()
                run.add(hit)
            }
            flush()
        }
    }

    private fun bodyScale(motion: Int, enter: Float, exit: Float, active: Boolean): Float {
        if (!active && motion != 7) return 1f
        val eased = ease(enter)
        return when (motion) {
            0 -> if (active) 1f else 0f
            3 -> if (active) 1.12f - 0.12f * eased else 1f + exit * 0.12f
            7 -> if (active) {
                if (eased < 0.65f) lerp(0.3f, 1.16f, eased / 0.65f) else lerp(1.16f, 1f, (eased - 0.65f) / 0.35f)
            } else {
                1f + exit * 0.25f
            }
            6 -> if (active) lerp(0.86f, 1f, eased) else 1f
            else -> if (active) lerp(0.25f, 1f, eased) else 1f
        }
    }

    private fun bodyAlpha(motion: Int, enter: Float, exit: Float, active: Boolean, time: Double, hole: Int): Float {
        if (motion == 0) return if (active) 1f else 0f
        val base = if (active) ease(enter) else (1f - exit)
        val flicker = if (motion == 2 && active) 0.84f + 0.16f * sin(time * 16.0 + hole).toFloat() else 1f
        return (base * flicker).coerceIn(0f, 1f)
    }

    private fun drawLit(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, above: Boolean, lit: Int, alpha: Float) {
        if (alpha <= 0.02f || rx <= 0f) return
        val color = if (above) BLOW else DRAW
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val oval = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
        when (lit) {
            1 -> {
                paint.color = withAlpha(color, 0.28f * alpha)
                canvas.drawOval(RectF(cx - rx * 1.7f, cy - ry * 1.7f, cx + rx * 1.7f, cy + ry * 1.7f), paint)
                paint.color = withAlpha(color, alpha)
                canvas.drawOval(oval, paint)
            }
            2 -> {
                paint.color = withAlpha(0xFFFFE7A8.toInt(), 0.35f * alpha)
                canvas.drawOval(RectF(cx - rx * 1.45f, cy - ry * 1.45f, cx + rx * 1.45f, cy + ry * 1.45f), paint)
                paint.color = withAlpha(color, alpha)
                canvas.drawOval(oval, paint)
                paint.color = withAlpha(Color.WHITE, 0.75f * alpha)
                canvas.drawCircle(cx - rx * 0.2f, cy - ry * 0.25f, min(rx, ry) * 0.28f, paint)
            }
            3 -> {
                paint.color = withAlpha(color, 0.3f * alpha)
                canvas.drawCircle(cx, cy, max(rx, ry) * 1.15f, paint)
                paint.color = withAlpha(color, alpha)
                canvas.drawOval(oval, paint)
            }
            4 -> {
                paint.color = withAlpha(color, 0.4f * alpha)
                canvas.drawOval(RectF(cx - rx * 1.1f, cy - ry * 1.1f, cx + rx * 1.1f, cy + ry * 1.1f), paint)
                paint.color = withAlpha(color, alpha)
                canvas.drawOval(oval, paint)
                paint.color = withAlpha(Color.WHITE, 0.8f * alpha)
                canvas.drawCircle(cx - rx * 0.25f, cy - ry * 0.28f, min(rx, ry) * 0.22f, paint)
            }
            5 -> {
                paint.shader = android.graphics.RadialGradient(
                    cx, cy, max(rx, ry),
                    intArrayOf(withAlpha(Color.WHITE, alpha), withAlpha(color, alpha), withAlpha(color, 0.15f * alpha)),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawOval(oval, paint)
            }
            6 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(2f, min(rx, ry) * 0.28f)
                paint.color = withAlpha(color, alpha)
                canvas.drawOval(oval, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, min(rx, ry) * 0.28f, paint)
            }
            7 -> {
                paint.color = withAlpha(color, alpha)
                val path = Path()
                path.moveTo(cx, cy - ry)
                path.lineTo(cx + rx * 0.45f, cy)
                path.lineTo(cx, cy + ry)
                path.lineTo(cx - rx * 0.45f, cy)
                path.close()
                canvas.drawPath(path, paint)
            }
            else -> {
                paint.color = withAlpha(color, alpha)
                canvas.drawOval(oval, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = if (above) 0xFF961212.toInt() else 0xFF148C1E.toInt()
                canvas.drawOval(oval, paint)
            }
        }
    }

    private fun drawLitCapsule(
        canvas: Canvas,
        leftCx: Float,
        rightCx: Float,
        cy: Float,
        radius: Float,
        above: Boolean,
        lit: Int,
        alpha: Float,
    ) {
        if (alpha <= 0.02f || radius <= 0f) return
        val color = if (above) BLOW else DRAW
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun blob(r: Float, c: Int) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = c
            canvas.drawPath(capsulePath(leftCx, rightCx, cy, r), paint)
        }
        when (lit) {
            1 -> {
                blob(radius * 1.7f, withAlpha(color, 0.28f * alpha))
                blob(radius, withAlpha(color, alpha))
            }
            2 -> {
                blob(radius * 1.45f, withAlpha(0xFFFFE7A8.toInt(), 0.35f * alpha))
                blob(radius, withAlpha(color, alpha))
                paint.color = withAlpha(Color.WHITE, 0.75f * alpha)
                canvas.drawCircle(leftCx - radius * 0.2f, cy - radius * 0.25f, radius * 0.28f, paint)
            }
            3 -> {
                blob(radius * 1.15f, withAlpha(color, 0.3f * alpha))
                blob(radius, withAlpha(color, alpha))
            }
            4 -> {
                blob(radius * 1.1f, withAlpha(color, 0.4f * alpha))
                blob(radius, withAlpha(color, alpha))
                paint.color = withAlpha(Color.WHITE, 0.8f * alpha)
                canvas.drawCircle(leftCx - radius * 0.25f, cy - radius * 0.28f, radius * 0.22f, paint)
            }
            else -> {
                paint.shader = LinearGradient(
                    leftCx, cy, rightCx, cy,
                    intArrayOf(withAlpha(Color.WHITE, alpha), withAlpha(color, alpha), withAlpha(Color.WHITE, alpha)),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                paint.style = Paint.Style.FILL
                canvas.drawPath(capsulePath(leftCx, rightCx, cy, radius), paint)
            }
        }
    }

    private fun capsulePath(leftCx: Float, rightCx: Float, cy: Float, radius: Float): Path {
        val path = Path()
        path.moveTo(leftCx, cy - radius)
        path.lineTo(rightCx, cy - radius)
        path.arcTo(RectF(rightCx - radius, cy - radius, rightCx + radius, cy + radius), -90f, 180f, false)
        path.lineTo(leftCx, cy + radius)
        path.arcTo(RectF(leftCx - radius, cy - radius, leftCx + radius, cy + radius), 90f, 180f, false)
        path.close()
        return path
    }

    private fun drawMotion(
        canvas: Canvas,
        layout: HudLayout,
        ox: Float,
        oy: Float,
        group: List<SquareHit>,
        motion: Int,
        exit: Float,
        blow: Boolean,
    ) {
        if (motion == 0 || motion == 6) return
        val color = if (blow) BLOW else DRAW
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val origins = group.map { android.graphics.PointF(ox + it.cx, oy + it.cy) }
        val radius = layout.sq / 2f
        when (motion) {
            1 -> shatter(canvas, paint, origins, radius, color, exit)
            2 -> rise(canvas, paint, origins, radius, color, exit)
            3 -> splatter(canvas, paint, origins, radius, color, exit)
            4 -> burst(canvas, paint, origins, radius, color, exit)
            5 -> drops(canvas, paint, origins, radius, color, exit)
            7 -> burst(canvas, paint, origins, radius * 0.7f, color, exit)
        }
    }

    private fun shatter(canvas: Canvas, paint: Paint, origins: List<android.graphics.PointF>, radius: Float, color: Int, exit: Float) {
        origins.forEachIndexed { index, origin ->
            for (i in 0 until 7) {
                val angle = (i / 7f) * 2f * PI.toFloat() + index
                val dist = ease(exit) * radius * 3.2f
                paint.color = withAlpha(color, 1f - exit)
                canvas.drawCircle(origin.x + cos(angle) * dist, origin.y + sin(angle) * dist, radius * 0.26f * (1f - exit), paint)
            }
        }
    }

    private fun rise(canvas: Canvas, paint: Paint, origins: List<android.graphics.PointF>, radius: Float, color: Int, exit: Float) {
        origins.forEachIndexed { index, origin ->
            for (i in 0 until 5) {
                val drift = (i - 2) * radius * 0.4f
                val up = exit * exit * radius * 5f
                val x = origin.x + drift
                val y = origin.y - up
                paint.color = withAlpha(0xFFFFE7A8.toInt(), 1f - exit)
                canvas.drawCircle(x, y, radius * 0.2f * (1f - exit * 0.5f), paint)
                paint.color = withAlpha(color, (1f - exit) * 0.45f)
                canvas.drawCircle(x, y, radius * 0.38f * (1f - exit), paint)
            }
        }
    }

    private fun splatter(canvas: Canvas, paint: Paint, origins: List<android.graphics.PointF>, radius: Float, color: Int, exit: Float) {
        origins.forEachIndexed { index, origin ->
            for (i in 0 until 8) {
                val angle = (i / 8f) * 2f * PI.toFloat() + index * 0.4f
                val dist = exit * radius * 2.3f
                val drop = exit * exit * radius * 3.4f
                paint.color = withAlpha(color, 1f - exit)
                canvas.drawCircle(
                    origin.x + cos(angle) * dist,
                    origin.y + sin(angle) * dist * 0.55f + drop,
                    radius * (0.12f + (i % 3) * 0.05f),
                    paint,
                )
            }
        }
    }

    private fun burst(canvas: Canvas, paint: Paint, origins: List<android.graphics.PointF>, radius: Float, color: Int, exit: Float) {
        if (origins.isEmpty()) return
        val cx = origins.map { it.x }.average().toFloat()
        val cy = origins.map { it.y }.average().toFloat()
        val span = origins.maxOf { it.x } - origins.minOf { it.x }
        val ring = (radius + span / 2f) * (1f + ease(exit) * 1.8f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.22f * (1f - exit)
        paint.color = withAlpha(color, 1f - exit)
        canvas.drawCircle(cx, cy, ring, paint)
        paint.style = Paint.Style.FILL
        for (i in 0 until 10) {
            val angle = (i / 10f) * 2f * PI.toFloat()
            val dist = ease(exit) * (radius + span / 4f) * 2.2f
            canvas.drawCircle(cx + cos(angle) * dist, cy + sin(angle) * dist, radius * 0.16f * (1f - exit), paint)
        }
    }

    private fun drops(canvas: Canvas, paint: Paint, origins: List<android.graphics.PointF>, radius: Float, color: Int, exit: Float) {
        val dirs = floatArrayOf(-1.1f, -0.2f, 0.55f, 1.15f)
        for (origin in origins) {
            for (i in dirs.indices) {
                val dx = dirs[i] * ease(exit) * radius * 2.1f
                val dy = ((i % 2) - 0.5f) * exit * radius
                val r = radius * (0.36f - exit * 0.14f)
                paint.color = withAlpha(color, 0.9f * (1f - exit))
                canvas.drawCircle(origin.x + dx, origin.y + dy, r, paint)
                paint.color = withAlpha(Color.WHITE, 0.7f * (1f - exit))
                canvas.drawCircle(origin.x + dx - r * 0.2f, origin.y + dy - r * 0.2f, r * 0.22f, paint)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
