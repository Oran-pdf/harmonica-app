package com.orangames.harmonica.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.orangames.harmonica.data.FrameMarks
import com.orangames.harmonica.data.HARP_KEYS
import com.orangames.harmonica.data.HoleMark
import com.orangames.harmonica.data.Take
import com.orangames.harmonica.data.TakeStore
import com.orangames.harmonica.data.SchemeStore
import com.orangames.harmonica.data.Timeline
import com.orangames.harmonica.HarmonicaAppHolder
import com.orangames.harmonica.data.frameAt
import com.orangames.harmonica.data.frameToMs
import com.orangames.harmonica.media.SchemePainter
import com.orangames.harmonica.media.measureHud
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditorModel(private val store: TakeStore, val id: String) : ViewModel() {
    var take by mutableStateOf<Take?>(null)
        private set
    var missing by mutableStateOf(false)
        private set
    val frames = androidx.compose.runtime.mutableStateMapOf<Int, FrameMarks>()
    var harpKey by mutableStateOf("C")
        private set
    var positionMs by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)

    var peaks by mutableStateOf(FloatArray(0))
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = store.read(id)
            if (loaded == null || !store.sourceFile(id).exists()) {
                withContext(Dispatchers.Main) { missing = true }
                return@launch
            }
            val expanded = Timeline.expand(store.readTimeline(id), loaded.fps, loaded.frameCount)
            val wave = store.peaks(id)
            withContext(Dispatchers.Main) {
                take = loaded
                harpKey = loaded.key
                frames.putAll(expanded)
                peaks = wave
            }
        }
    }

    fun toggle(frame: Int, above: Boolean, hole: Int, bend: Int) {
        val current = take ?: return
        val breath = if (above) "blow" else "draw"
        val existing = frames[frame]
        val next = if (existing == null || existing.breath != breath) {
            listOf(HoleMark(hole, bend))
        } else {
            val list = existing.holes.toMutableList()
            val index = list.indexOfFirst { it.hole == hole && it.bend == bend }
            if (index >= 0) list.removeAt(index) else list.add(HoleMark(hole, bend))
            list.sortedWith(compareBy({ it.hole }, { it.bend }))
        }
        if (next.isEmpty()) frames.remove(frame) else frames[frame] = FrameMarks(breath, next)
        persist(current)
    }

    fun cycleKey() {
        val current = take ?: return
        val index = HARP_KEYS.indexOf(harpKey).let { if (it < 0) 0 else it }
        harpKey = HARP_KEYS[(index + 1) % HARP_KEYS.size]
        persist(current)
    }

    private fun persist(current: Take) {
        val snapshot = frames.toMap()
        val key = harpKey
        viewModelScope.launch(Dispatchers.IO) {
            store.writeEdits(current.id, key, snapshot, current.fps, current.frameCount)
        }
    }
}

class EditorModelFactory(private val store: TakeStore, private val id: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorModel(store, id) as T
}

@Composable
fun EditorScreen(model: EditorModel, onBack: () -> Unit) {
    val take = model.take
    if (model.missing) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Text("This video is no longer on the phone.", color = Cream, modifier = Modifier.clickable(onClick = onBack))
        }
        return
    }
    if (take == null) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Text("Opening…", color = Muted)
        }
        return
    }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val player = remember(take.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(HarmonicaAppHolder.sourceOf(context, take.id))))
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            model.positionMs = player.currentPosition
            model.playing = player.isPlaying
            delay(32)
        }
    }

    fun seekToMs(ms: Long) {
        val clamped = ms.coerceIn(0L, take.durationMs)
        player.seekTo(clamped)
        model.positionMs = clamped
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = player
                }
            },
            update = { it.player = player },
        )
        HarmonicaOverlay(
            take = take,
            keyName = model.harpKey,
            timeSec = model.positionMs / 1000.0,
            frames = model.frames,
            onToggle = { above, hole, bend ->
                player.pause()
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                model.toggle(frameAt(model.positionMs, take.fps, take.frameCount), above, hole, bend)
            },
            onKey = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                model.cycleKey()
            },
        )
        WaveformBar(
            peaks = model.peaks,
            positionMs = model.positionMs,
            durationMs = take.durationMs,
            onSeek = { fraction ->
                player.pause()
                seekToMs((fraction * take.durationMs).toLong())
            },
        )
        Transport(
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
            playing = model.playing,
            onBack = onBack,
            onStep = { dir ->
                player.pause()
                val frame = (frameAt(model.positionMs, take.fps, take.frameCount) + dir)
                    .coerceIn(0, take.frameCount - 1)
                seekToMs(frameToMs(frame, take.fps))
            },
            onPlay = {
                if (player.isPlaying) player.pause() else player.play()
            },
        )
    }
}

@Composable
private fun HarmonicaOverlay(
    take: Take,
    keyName: String,
    timeSec: Double,
    frames: Map<Int, FrameMarks>,
    onToggle: (Boolean, Int, Int) -> Unit,
    onKey: () -> Unit,
) {
    val layout = remember(take.width) { measureHud(take.width) }
    val look = SchemeStore.selected
    val events = Timeline.fromFrames(keyName, frames, take.fps, take.frameCount).events
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val fit = min(viewW / take.width, viewH / take.height)
        val fittedW = take.width * fit
        val fittedH = take.height * fit
        val left = (viewW - fittedW) / 2f
        val top = (viewH - fittedH) / 2f
        val hudScale = fittedW / take.width
        val hudW = layout.width * hudScale
        val hudH = layout.height * hudScale
        val hudLeft = left + (fittedW - hudW) / 2f
        val limit = top + fittedH - hudH - 8f
        val hudTop = if (look.id == "simple") {
            top + (fittedH - hudH) / 2f
        } else {
            (top + fittedH * 0.18f).coerceIn(top + 8f, maxOf(top + 8f, limit))
        }
        Canvas(
            modifier = Modifier
                .offset { IntOffset(hudLeft.roundToInt(), hudTop.roundToInt()) }
                .size(
                    width = with(LocalDensity.current) { hudW.toDp() },
                    height = with(LocalDensity.current) { hudH.toDp() },
                )
                .pointerInput(layout, hudScale) {
                    detectTapGestures { offset ->
                        val x = offset.x / hudScale
                        val y = offset.y / hudScale
                        val keyLeft = layout.keyCx - layout.cellW * 0.38f
                        val keyRight = layout.keyCx + layout.cellW * 0.38f
                        if (y in layout.barTop..layout.barBottom && x in keyLeft..keyRight) {
                            onKey()
                            return@detectTapGestures
                        }
                        val hit = layout.squares.minByOrNull { sq ->
                            val dx = x - sq.cx
                            val dy = y - sq.cy
                            dx * dx + dy * dy
                        } ?: return@detectTapGestures
                        val reachX = layout.cellW * 0.62f
                        val reachY = (layout.sq + layout.gap) * 0.62f
                        if (abs(x - hit.cx) <= reachX && abs(y - hit.cy) <= reachY) {
                            onToggle(hit.above, hit.hole, hit.bend)
                        }
                    }
                },
        ) {
            val drawn = size.width / layout.width
            scale(drawn, drawn, Offset.Zero) {
                drawIntoCanvas { canvas ->
                    SchemePainter.draw(
                        canvas.nativeCanvas,
                        layout,
                        0f,
                        0f,
                        keyName,
                        timeSec,
                        events,
                        look,
                        animate = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun WaveformBar(
    peaks: FloatArray,
    positionMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .background(Ink.copy(alpha = 0.45f))
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun fraction(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    onSeek(fraction(down.position.x))
                    drag(down.id) { change ->
                        onSeek(fraction(change.position.x))
                        change.consume()
                    }
                }
            },
    ) {
        val mid = size.height * 0.62f
        val count = peaks.size.coerceAtLeast(1)
        val slot = size.width / count
        for (i in peaks.indices) {
            val amp = peaks[i].coerceIn(0.04f, 1f)
            val h = amp * size.height * 0.55f
            drawLine(
                color = Cream.copy(alpha = 0.85f),
                start = Offset(slot * i + slot / 2f, mid - h / 2f),
                end = Offset(slot * i + slot / 2f, mid + h / 2f),
                strokeWidth = slot * 0.45f,
            )
        }
        val fraction = if (durationMs <= 0L) 0f else positionMs.toFloat() / durationMs
        val x = fraction.coerceIn(0f, 1f) * size.width
        drawLine(Amber, Offset(x, 16f), Offset(x, size.height), strokeWidth = 2f)
        val arrow = Path().apply {
            moveTo(x, 2f)
            lineTo(x - 8f, 16f)
            lineTo(x + 8f, 16f)
            close()
        }
        drawPath(arrow, Amber)
    }
}

@Composable
private fun Transport(
    modifier: Modifier,
    playing: Boolean,
    onBack: () -> Unit,
    onStep: (Int) -> Unit,
    onPlay: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Ink.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackButton(onClick = onBack)
        FrameStep(Icons.Filled.KeyboardArrowLeft, "Previous frame") { onStep(-1) }
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Cream)
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Ink,
                modifier = Modifier.size(28.dp),
            )
        }
        FrameStep(Icons.Filled.KeyboardArrowRight, "Next frame") { onStep(1) }
    }
}

@Composable
private fun FrameStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Cream, modifier = Modifier.size(28.dp))
    }
}
