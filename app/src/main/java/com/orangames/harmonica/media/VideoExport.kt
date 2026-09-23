package com.orangames.harmonica.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.orangames.harmonica.data.FrameMarks
import com.orangames.harmonica.data.TimelineDoc
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

object VideoExport {
    suspend fun burnOverlay(
        context: Context,
        source: File,
        output: File,
        videoW: Int,
        videoH: Int,
        fps: Double,
        key: String,
        timeline: TimelineDoc,
    ) {
        val overlay = HudFrameOverlay(videoW, videoH, fps, key, timeline)
        val effects = Effects(
            emptyList(),
            listOf<Effect>(OverlayEffect(ImmutableList.of(overlay))),
        )
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
            .setEffects(effects)
            .build()
        val composition = compositionOf(EditedMediaItemSequence(item))
        transform(context, composition, output)
    }

    suspend fun audioToPortrait(context: Context, audio: File, output: File, durationUs: Long) {
        val background = backgroundFile(context)
        val image = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(background)))
            .setDurationUs(durationUs)
            .setFrameRate(30)
            .build()
        val sound = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audio)))
            .setRemoveVideo(true)
            .build()
        val composition = compositionOf(
            EditedMediaItemSequence(image),
            EditedMediaItemSequence(sound),
        )
        transform(context, composition, output)
    }

    private fun compositionOf(vararg sequences: EditedMediaItemSequence): Composition {
        val builder = Composition.Builder(sequences[0], *sequences.drop(1).toTypedArray())
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        return builder.build()
    }

    private suspend fun transform(context: Context, composition: Composition, output: File) {
        if (output.exists()) output.delete()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context).build()
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, output.absolutePath)
            }
        }
    }

    private fun backgroundFile(context: Context): File {
        val file = File(context.filesDir, "stage-background.jpg")
        if (file.exists() && file.length() > 0L) return file
        val width = 1080
        val height = 1920
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val wash = Paint()
        wash.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            Color.parseColor("#2A2118"),
            Color.parseColor("#0C0A09"),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), wash)
        val glow = Paint()
        glow.shader = RadialGradient(
            width / 2f,
            height * 0.38f,
            width * 0.72f,
            Color.parseColor("#55C48A4A"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        return file
    }
}

private class HudFrameOverlay(
    private val videoW: Int,
    private val videoH: Int,
    private val fps: Double,
    private val key: String,
    private val timeline: TimelineDoc,
) : BitmapOverlay() {
    private val bitmap = Bitmap.createBitmap(
        videoW.coerceAtLeast(2),
        videoH.coerceAtLeast(2),
        Bitmap.Config.ARGB_8888,
    )
    private val canvas = Canvas(bitmap)
    private var cachedKey: Int = Int.MIN_VALUE
    private var cached: FrameMarks? = null

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val frame = ((presentationTimeUs / 1_000_000.0) * fps).toInt()
        if (frame != cachedKey) {
            cachedKey = frame
            cached = marksAt(frame)
            bitmap.eraseColor(Color.TRANSPARENT)
            HudPainter.drawOnFrame(canvas, bitmap.width, bitmap.height, key, cached)
        }
        return bitmap
    }

    private fun marksAt(frame: Int): FrameMarks? {
        val time = frame / fps
        val event = timeline.events.firstOrNull { time >= it.t0 && time < it.t1 } ?: return null
        return FrameMarks(event.breath, event.holes)
    }
}
