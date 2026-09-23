package com.orangames.harmonica.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min

data class MediaInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Double,
)

fun probeVideo(path: String): MediaInfo {
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(path)
    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
    var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    if (rotation == 90 || rotation == 270) {
        val swap = width
        width = height
        height = swap
    }
    retriever.release()
    var fps = 30.0
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(path)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                fps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                break
            }
        }
    } catch (_: Exception) {
        fps = 30.0
    } finally {
        extractor.release()
    }
    if (fps < 1.0) fps = 30.0
    if (width <= 0) width = 1080
    if (height <= 0) height = 1920
    return MediaInfo(duration, width, height, fps)
}

fun probeDurationMs(path: String): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(path)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    } finally {
        retriever.release()
    }
}

fun extractPeaks(path: String, buckets: Int = 96): FloatArray {
    val peaks = FloatArray(buckets)
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
        extractor.setDataSource(path)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                track = i
                format = candidate
                break
            }
        }
        if (track < 0 || format == null) return peaks
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return peaks
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            probeDurationMs(path) * 1000L
        }
        if (durationUs <= 0L) return peaks
        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex) ?: continue
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
            if (outIndex >= 0) {
                val output = decoder.getOutputBuffer(outIndex)
                if (output != null && info.size > 1) {
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    output.order(ByteOrder.LITTLE_ENDIAN)
                    val samples = output.asShortBuffer()
                    var maxAmp = 0
                    val count = min(samples.remaining(), info.size / 2)
                    for (n in 0 until count) {
                        val amp = abs(samples.get().toInt())
                        if (amp > maxAmp) maxAmp = amp
                    }
                    val bucket = ((info.presentationTimeUs.toDouble() / durationUs) * buckets)
                        .toInt()
                        .coerceIn(0, buckets - 1)
                    val norm = maxAmp / 32768f
                    if (norm > peaks[bucket]) peaks[bucket] = norm
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                outputDone = true
            }
        }
    } catch (_: Exception) {
        return peaks
    } finally {
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        extractor.release()
    }
    return peaks
}

fun File.writePeaks(peaks: FloatArray) {
    writeText(peaks.joinToString(",") { "%.4f".format(it) })
}

fun File.readPeaks(): FloatArray? {
    if (!exists()) return null
    val parts = readText().split(",").mapNotNull { it.toFloatOrNull() }
    if (parts.isEmpty()) return null
    return parts.toFloatArray()
}

fun extractWav(source: File, dest: File) {
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
        extractor.setDataSource(source.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                track = i
                format = candidate
                break
            }
        }
        if (track < 0 || format == null) error("That recording has no sound")
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("That recording has no sound")
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            1
        }
        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val pcm = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex) ?: continue
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> if (inputDone) outputDone = true
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val changed = decoder.outputFormat
                    if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                    }
                }
                else -> if (outIndex >= 0) {
                    val output = decoder.getOutputBuffer(outIndex)
                    if (output != null && info.size > 1) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size - (info.size % 2))
                        output.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = output.asShortBuffer()
                        val frameCount = shorts.remaining() / channels.coerceAtLeast(1)
                        val mono = ByteBuffer.allocate(frameCount * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (frame in 0 until frameCount) {
                            var sum = 0
                            for (channel in 0 until channels) sum += shorts.get().toInt()
                            mono.putShort((sum / channels).toShort())
                        }
                        pcm.write(mono.array())
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        writeWav(dest, sampleRate, pcm.toByteArray())
    } finally {
        try {
            decoder?.stop()
        } catch (_: Exception) {
        }
        try {
            decoder?.release()
        } catch (_: Exception) {
        }
        extractor.release()
    }
}

private fun writeWav(dest: File, sampleRate: Int, pcm: ByteArray) {
    val dataSize = pcm.size - (pcm.size % 2)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(36 + dataSize)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(sampleRate)
    header.putInt(sampleRate * 2)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray())
    header.putInt(dataSize)
    dest.outputStream().use { out ->
        out.write(header.array())
        out.write(pcm, 0, dataSize)
    }
}
