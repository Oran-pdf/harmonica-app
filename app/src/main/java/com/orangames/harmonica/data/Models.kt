package com.orangames.harmonica.data

import kotlin.math.roundToInt

val HARP_KEYS = listOf("C", "G", "D", "A", "E", "F", "Bb", "Eb")

data class HoleMark(val hole: Int, val bend: Int)

data class FrameMarks(val breath: String, val holes: List<HoleMark>) {
    fun lit(hole: Int, bend: Int, breath: String): Boolean {
        return this.breath == breath && holes.any { it.hole == hole && it.bend == bend }
    }
}

data class NoteEvent(
    val t0: Double,
    val t1: Double,
    val breath: String,
    val holes: List<HoleMark>,
)

data class TimelineDoc(val key: String, val events: List<NoteEvent>)

data class Take(
    val id: String,
    val title: String,
    val createdAt: Long,
    val key: String,
    val fps: Double,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val overlayUri: String?,
    val exportStamp: Long,
) {
    val frameCount: Int
        get() = ((durationMs / 1000.0) * fps).roundToInt().coerceAtLeast(1)

    val ready: Boolean
        get() = overlayUri != null
}

object Timeline {
    fun fromFrames(
        key: String,
        frames: Map<Int, FrameMarks>,
        fps: Double,
        frameCount: Int,
    ): TimelineDoc {
        val events = ArrayList<NoteEvent>()
        var i = 0
        while (i < frameCount) {
            val marks = frames[i]
            if (marks == null || marks.holes.isEmpty()) {
                i++
                continue
            }
            var j = i + 1
            while (j < frameCount && frames[j] == marks) j++
            events.add(
                NoteEvent(
                    t0 = i / fps,
                    t1 = j / fps,
                    breath = marks.breath,
                    holes = marks.holes,
                ),
            )
            i = j
        }
        return TimelineDoc(key, events)
    }

    fun expand(doc: TimelineDoc, fps: Double, frameCount: Int): Map<Int, FrameMarks> {
        val map = LinkedHashMap<Int, FrameMarks>()
        for (event in doc.events) {
            if (event.holes.isEmpty()) continue
            val start = (event.t0 * fps).roundToInt().coerceIn(0, frameCount)
            var end = (event.t1 * fps).roundToInt().coerceIn(0, frameCount)
            if (end <= start) end = (start + 1).coerceAtMost(frameCount)
            val marks = FrameMarks(
                event.breath,
                event.holes.sortedWith(compareBy({ it.hole }, { it.bend })),
            )
            for (frame in start until end) map[frame] = marks
        }
        return map
    }
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000L).toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun frameAt(positionMs: Long, fps: Double, frameCount: Int): Int {
    return ((positionMs / 1000.0) * fps).roundToInt().coerceIn(0, frameCount - 1)
}

fun frameToMs(frame: Int, fps: Double): Long {
    return (frame * 1000.0 / fps).toLong()
}
