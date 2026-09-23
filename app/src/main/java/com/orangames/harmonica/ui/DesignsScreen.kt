package com.orangames.harmonica.ui

import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private enum class DesignStyle(val title: String, val blurb: String) {
    Brass("Brass", "A metal harp. Notes glow on, then crack into shards."),
    Lantern("Lantern", "Warm wood. Notes catch fire, then sparks lift away."),
    Ink("Ink", "Paper and ink. Notes stamp down, then splatter off."),
    Stage("Stage", "Neon lights. Notes bloom, then burst in a ring."),
    Glass("Glass", "Frosted glass. Notes swell, then break into drops."),
}

private data class PhraseNote(
    val t0: Float,
    val t1: Float,
    val blow: Boolean,
    val from: Int,
    val to: Int,
)

private data class NotePhase(val enter: Float, val exit: Float)

private const val LOOP = 6.4f
private const val ENTER = 0.22f
private const val EXIT = 0.5f

private val PHRASE = listOf(
    PhraseNote(0.15f, 1.15f, true, 3, 3),
    PhraseNote(1.35f, 2.45f, false, 1, 1),
    PhraseNote(2.6f, 3.85f, true, 4, 6),
    PhraseNote(4.05f, 5.15f, false, 5, 5),
    PhraseNote(5.3f, 5.85f, true, 8, 8),
)

private val Blow = Color(0xFFFF4B3C)
private val Draw = Color(0xFF3DDC6A)

@Composable
fun DesignsScreen(onBack: () -> Unit) {
    var open by remember { mutableStateOf<DesignStyle?>(null) }
    val shown = open
    if (shown != null) {
        DesignDetail(shown, onBack = { open = null })
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.width(14.dp))
            Text("Designs", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "These are samples. Your videos stay as they are until you pick one.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            items(DesignStyle.entries) { style ->
                DesignCard(style) { open = style }
            }
        }
    }
}

@Composable
private fun DesignCard(style: DesignStyle, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBrown)
            .border(1.dp, Line, shape)
            .clickable(onClick = onOpen)
            .padding(12.dp),
    ) {
        Text(style.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(style.blurb, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        DesignStage(
            style,
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(14.dp)),
        )
    }
}

@Composable
private fun DesignDetail(style: DesignStyle, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(style.title, style = MaterialTheme.typography.headlineLarge)
                Text(style.blurb, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(18.dp))
        DesignStage(
            style,
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(22.dp)),
        )
        Spacer(Modifier.height(22.dp))
    }
}

@Composable
private fun DesignStage(style: DesignStyle, modifier: Modifier) {
    val clock = rememberInfiniteTransition(label = style.title)
    val time by clock.animateFloat(
        initialValue = 0f,
        targetValue = LOOP,
        animationSpec = infiniteRepeatable(tween((LOOP * 1000).toInt(), easing = LinearEasing)),
        label = "phrase",
    )
    Canvas(modifier.background(Color(0xFF161310))) {
        drawHarp(style, time)
    }
}

private fun DrawScope.drawHarp(style: DesignStyle, time: Float) {
    val barTop = size.height * 0.36f
    val barBot = size.height * 0.64f
    val barLeft = size.width * 0.04f
    val barWidth = size.width * 0.92f
    val radius = (barBot - barTop) / 2f
    when (style) {
        DesignStyle.Brass -> drawBrassBar(barLeft, barTop, barWidth, barBot - barTop, radius)
        DesignStyle.Lantern -> drawWoodBar(barLeft, barTop, barWidth, barBot - barTop, radius)
        DesignStyle.Ink -> drawPaperBar(barLeft, barTop, barWidth, barBot - barTop, radius)
        DesignStyle.Stage -> drawStageBar(barLeft, barTop, barWidth, barBot - barTop, radius)
        DesignStyle.Glass -> drawGlassBar(barLeft, barTop, barWidth, barBot - barTop, radius)
    }
    drawLabels(style, barTop, barBot)
    for (hole in 0 until 10) {
        drawIdle(style, holeX(hole), blowY(), false)
        drawIdle(style, holeX(hole), drawY(), true)
    }
    for (note in PHRASE) {
        val phase = phaseOf(note, time) ?: continue
        drawNote(style, note, phase, time)
    }
}

private fun DrawScope.drawBrassBar(left: Float, top: Float, width: Float, height: Float, radius: Float) {
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF8A5A2C), Color(0xFFF0D7A2), Color(0xFF6B431C)),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.35f),
        topLeft = Offset(left + height * 0.35f, top + height * 0.12f),
        size = Size(width - height * 0.7f, height * 0.22f),
        cornerRadius = CornerRadius(height, height),
    )
}

private fun DrawScope.drawWoodBar(left: Float, top: Float, width: Float, height: Float, radius: Float) {
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xFF4A3424), Color(0xFF2A1C12), Color(0xFF1A120C)),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = Color(0xFFE7B56A).copy(alpha = 0.18f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 2f),
    )
}

private fun DrawScope.drawPaperBar(left: Float, top: Float, width: Float, height: Float, radius: Float) {
    drawRoundRect(
        color = Color(0xFFF4EFE4),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = Color(0xFF8C7358),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 1.5f),
    )
}

private fun DrawScope.drawStageBar(left: Float, top: Float, width: Float, height: Float, radius: Float) {
    drawRoundRect(
        color = Color(0xFF101014),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = Color(0xFFD9D3C8),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 1.5f),
    )
}

private fun DrawScope.drawGlassBar(left: Float, top: Float, width: Float, height: Float, radius: Float) {
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.08f)),
            startY = top,
            endY = top + height,
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.55f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 1.5f),
    )
}

private fun DrawScope.drawLabels(style: DesignStyle, barTop: Float, barBot: Float) {
    val ink = when (style) {
        DesignStyle.Ink -> android.graphics.Color.parseColor("#3A2A1C")
        DesignStyle.Brass -> android.graphics.Color.parseColor("#2A180C")
        else -> android.graphics.Color.WHITE
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        textAlign = android.graphics.Paint.Align.CENTER
        textSize = (barBot - barTop) * 0.42f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val fm = paint.fontMetrics
    val y = (barTop + barBot) / 2f - (fm.ascent + fm.descent) / 2f
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawText("C", size.width * 0.09f, y, paint)
    for (hole in 0 until 10) {
        val label = if (hole < 9) (hole + 1).toString() else "0"
        canvas.drawText(label, holeX(hole), y, paint)
    }
}

private fun DrawScope.drawIdle(style: DesignStyle, x: Float, y: Float, drawHole: Boolean) {
    val radius = holeRadius() * 0.55f
    val color = when (style) {
        DesignStyle.Ink -> Color(0xFF3A2A1C).copy(alpha = 0.28f)
        DesignStyle.Brass -> Color(0xFF2A180C).copy(alpha = 0.35f)
        else -> (if (drawHole) Draw else Blow).copy(alpha = 0.16f)
    }
    if (style == DesignStyle.Brass || style == DesignStyle.Ink || style == DesignStyle.Lantern) {
        drawCircle(Color.Black.copy(alpha = if (style == DesignStyle.Ink) 0.08f else 0.35f), radius, Offset(x, y))
    }
    drawCircle(color, radius * 0.72f, Offset(x, y))
}

private fun DrawScope.drawNote(style: DesignStyle, note: PhraseNote, phase: NotePhase, time: Float) {
    val y = if (note.blow) blowY() else drawY()
    val color = if (note.blow) Blow else Draw
    val radius = holeRadius()
    val left = holeX(note.from)
    val right = holeX(note.to)
    val center = Offset((left + right) / 2f, y)
    val enter = easeOut(phase.enter)
    val exiting = phase.exit > 0f
    val bodyAlpha = if (exiting) (1f - phase.exit) else enter
    val scale = when {
        exiting && style == DesignStyle.Ink -> 1f + phase.exit * 0.15f
        exiting -> 1f
        style == DesignStyle.Ink -> 1.18f - 0.18f * enter
        else -> 0.2f + 0.8f * enter
    }
    val flicker = if (style == DesignStyle.Lantern && !exiting) {
        0.82f + 0.18f * sin(time * 16f + note.from).toFloat()
    } else {
        1f
    }
    if (note.from == note.to) {
        drawLit(style, center, radius * scale, color, bodyAlpha * flicker)
    } else {
        val width = (right - left) + radius * 2f * scale
        val height = radius * 2f * scale
        drawCapsule(style, Offset(center.x - width / 2f, y - height / 2f), Size(width, height), color, bodyAlpha * flicker)
    }
    if (exiting) {
        val origins = (note.from..note.to).map { Offset(holeX(it), y) }
        when (style) {
            DesignStyle.Brass -> shatter(origins, radius, color, phase.exit)
            DesignStyle.Lantern -> rise(origins, radius, color, phase.exit)
            DesignStyle.Ink -> splatter(origins, radius, color, phase.exit)
            DesignStyle.Stage -> burst(center, radius + (right - left) / 2f, color, phase.exit)
            DesignStyle.Glass -> drops(center, radius, color, phase.exit)
        }
    }
}

private fun DrawScope.drawLit(style: DesignStyle, center: Offset, radius: Float, color: Color, alpha: Float) {
    if (alpha <= 0.01f) return
    when (style) {
        DesignStyle.Stage, DesignStyle.Lantern -> {
            drawCircle(color.copy(alpha = 0.28f * alpha), radius * 1.9f, center)
            drawCircle(color.copy(alpha = alpha), radius, center)
            drawCircle(Color.White.copy(alpha = 0.7f * alpha), radius * 0.28f, center + Offset(-radius * 0.2f, -radius * 0.25f))
        }
        DesignStyle.Glass -> {
            drawCircle(color.copy(alpha = 0.35f * alpha), radius * 1.15f, center)
            drawCircle(color.copy(alpha = 0.9f * alpha), radius, center)
            drawCircle(Color.White.copy(alpha = 0.8f * alpha), radius * 0.22f, center + Offset(-radius * 0.28f, -radius * 0.3f))
        }
        DesignStyle.Ink -> {
            drawCircle(color.copy(alpha = 0.25f * alpha), radius * 1.25f, center)
            drawCircle(color.copy(alpha = alpha), radius, center)
        }
        DesignStyle.Brass -> {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = alpha), color.copy(alpha = alpha), color.copy(alpha = 0.2f * alpha)),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

private fun DrawScope.drawCapsule(style: DesignStyle, topLeft: Offset, size: Size, color: Color, alpha: Float) {
    if (alpha <= 0.01f) return
    val radius = CornerRadius(size.height / 2f, size.height / 2f)
    if (style == DesignStyle.Stage || style == DesignStyle.Lantern) {
        drawRoundRect(color.copy(alpha = 0.25f * alpha), topLeft - Offset(6f, 6f), Size(size.width + 12f, size.height + 12f), radius)
    }
    drawRoundRect(color.copy(alpha = alpha), topLeft, size, radius)
    if (style == DesignStyle.Brass || style == DesignStyle.Glass) {
        drawRoundRect(
            Color.White.copy(alpha = 0.35f * alpha),
            topLeft + Offset(size.height * 0.25f, size.height * 0.18f),
            Size(size.width * 0.45f, size.height * 0.22f),
            CornerRadius(size.height, size.height),
        )
    }
}

private fun DrawScope.shatter(origins: List<Offset>, radius: Float, color: Color, exit: Float) {
    origins.forEachIndexed { hole, origin ->
        for (i in 0 until 7) {
            val angle = (i / 7f) * 2f * PI.toFloat() + hole
            val dist = easeOut(exit) * radius * 3.4f
            val spot = origin + Offset(cos(angle) * dist, sin(angle) * dist)
            drawCircle(color.copy(alpha = 1f - exit), radius * 0.28f * (1f - exit), spot)
        }
    }
}

private fun DrawScope.rise(origins: List<Offset>, radius: Float, color: Color, exit: Float) {
    origins.forEachIndexed { hole, origin ->
        for (i in 0 until 5) {
            val drift = (i - 2) * radius * 0.42f
            val up = exit * exit * radius * 5.5f
            val spot = Offset(origin.x + drift + sin(hole.toFloat() + i) * 4f, origin.y - up)
            drawCircle(Color(0xFFFFE7A8).copy(alpha = (1f - exit) * 0.95f), radius * 0.22f * (1f - exit * 0.6f), spot)
            drawCircle(color.copy(alpha = (1f - exit) * 0.45f), radius * 0.45f * (1f - exit), spot)
        }
    }
}

private fun DrawScope.splatter(origins: List<Offset>, radius: Float, color: Color, exit: Float) {
    origins.forEachIndexed { hole, origin ->
        for (i in 0 until 8) {
            val angle = (i / 8f) * 2f * PI.toFloat() + hole * 0.4f
            val dist = exit * radius * 2.4f
            val drop = exit * exit * radius * 3.6f
            val spot = origin + Offset(cos(angle) * dist, sin(angle) * dist * 0.6f + drop)
            drawCircle(color.copy(alpha = 1f - exit), radius * (0.12f + (i % 3) * 0.06f) * (1f - exit * 0.4f), spot)
        }
    }
}

private fun DrawScope.burst(center: Offset, radius: Float, color: Color, exit: Float) {
    val ring = radius * (1f + easeOut(exit) * 2.2f)
    drawCircle(
        color = color.copy(alpha = (1f - exit) * 0.9f),
        radius = ring,
        center = center,
        style = Stroke(width = radius * 0.22f * (1f - exit)),
    )
    for (i in 0 until 10) {
        val angle = (i / 10f) * 2f * PI.toFloat()
        val dist = easeOut(exit) * radius * 2.6f
        drawCircle(color.copy(alpha = 1f - exit), radius * 0.16f * (1f - exit), center + Offset(cos(angle) * dist, sin(angle) * dist))
    }
}

private fun DrawScope.drops(center: Offset, radius: Float, color: Color, exit: Float) {
    val dirs = floatArrayOf(-1.15f, -0.2f, 0.55f, 1.2f)
    for (i in dirs.indices) {
        val dx = dirs[i] * easeOut(exit) * radius * 2.3f
        val dy = ((i % 2) - 0.5f) * exit * radius * 1.4f
        val r = radius * (0.42f - exit * 0.18f)
        drawCircle(color.copy(alpha = 0.9f * (1f - exit)), r, center + Offset(dx, dy))
        drawCircle(Color.White.copy(alpha = 0.7f * (1f - exit)), r * 0.25f, center + Offset(dx - r * 0.2f, dy - r * 0.25f))
    }
}

private fun phaseOf(note: PhraseNote, time: Float): NotePhase? {
    val now = time % LOOP
    if (now in note.t0..note.t1) {
        return NotePhase(enter = ((now - note.t0) / ENTER).coerceIn(0f, 1f), exit = 0f)
    }
    val since = now - note.t1
    if (since in 0f..EXIT) return NotePhase(enter = 1f, exit = since / EXIT)
    return null
}

private fun DrawScope.holeX(index: Int): Float {
    val left = size.width * 0.16f
    val right = size.width * 0.94f
    return left + (right - left) * index / 9f
}

private fun DrawScope.blowY() = size.height * 0.2f
private fun DrawScope.drawY() = size.height * 0.8f
private fun DrawScope.holeRadius() = size.height * 0.075f

private fun easeOut(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x) * (1f - x)
}
