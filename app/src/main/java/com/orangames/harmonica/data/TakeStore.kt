package com.orangames.harmonica.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.orangames.harmonica.media.Detector
import com.orangames.harmonica.media.Gallery
import com.orangames.harmonica.media.VideoExport
import com.orangames.harmonica.media.extractPeaks
import com.orangames.harmonica.media.extractWav
import com.orangames.harmonica.media.probeDurationMs
import com.orangames.harmonica.media.probeVideo
import com.orangames.harmonica.media.readPeaks
import com.orangames.harmonica.media.writePeaks
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class TakeStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exportMutex = Mutex()
    private val dirty = ConcurrentHashMap.newKeySet<String>()
    private val jobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes = changesFlow.asSharedFlow()
    var lastError: String? = null
        private set

    private val root: File
        get() = File(context.filesDir, "takes").apply { mkdirs() }

    fun refreshPending() {
        scope.launch(Dispatchers.IO) {
            for (take in readAll(keepMissing = true)) {
                val folder = folder(take.id)
                val timeline = File(folder, "timeline.json")
                val needs = take.overlayUri == null ||
                    (timeline.exists() && timeline.lastModified() > take.exportStamp + 500)
                if (needs) requestExport(take.id)
            }
        }
    }

    suspend fun list(): List<Take> = withContext(Dispatchers.IO) {
        readAll(keepMissing = false)
    }

    suspend fun read(id: String): Take? = withContext(Dispatchers.IO) {
        val folder = folder(id)
        if (!folder.exists()) return@withContext null
        readMeta(folder)
    }

    fun sourceFile(id: String): File = File(folder(id), "source.mp4")

    suspend fun importVideo(uri: Uri): String = withContext(Dispatchers.IO) {
        val id = newId()
        val folder = folder(id)
        folder.mkdirs()
        val source = File(folder, "source.mp4")
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) error("Could not read that video")
            source.outputStream().use { input.copyTo(it) }
        }
        adopt(id, folder, source)
    }

    suspend fun importRecordedVideo(file: File): String = withContext(Dispatchers.IO) {
        val id = newId()
        val folder = folder(id)
        folder.mkdirs()
        val source = File(folder, "source.mp4")
        file.copyTo(source, overwrite = true)
        file.delete()
        adopt(id, folder, source)
    }

    suspend fun importRecordedAudio(file: File): String = withContext(Dispatchers.IO) {
        val durationMs = probeDurationMs(file.absolutePath)
        if (durationMs < 300L) {
            file.delete()
            error("That recording was too short")
        }
        val id = newId()
        val folder = folder(id)
        folder.mkdirs()
        val source = File(folder, "source.mp4")
        VideoExport.audioToPortrait(context, file, source, durationMs * 1000L)
        file.delete()
        adopt(id, folder, source)
    }

    fun readTimeline(id: String): TimelineDoc {
        val file = File(folder(id), "timeline.json")
        if (!file.exists()) return TimelineDoc("C", emptyList())
        return decodeTimeline(file.readText())
    }

    fun peaks(id: String): FloatArray {
        val file = File(folder(id), "peaks.txt")
        file.readPeaks()?.let { return it }
        val extracted = extractPeaks(sourceFile(id).absolutePath)
        file.writePeaks(extracted)
        return extracted
    }

    fun writeEdits(id: String, key: String, frames: Map<Int, FrameMarks>, fps: Double, frameCount: Int) {
        val doc = Timeline.fromFrames(key, frames, fps, frameCount)
        synchronized(this) {
            val folder = folder(id)
            File(folder, "timeline.json").writeText(encodeTimeline(doc))
            val meta = readMeta(folder) ?: return
            writeMeta(folder, meta.copy(key = key))
        }
        requestExport(id)
    }

    fun requestExport(id: String) {
        dirty.add(id)
        val current = jobs[id]
        if (current != null && current.isActive) return
        jobs[id] = scope.launch {
            delay(700)
            exportMutex.withLock {
                while (dirty.remove(id)) {
                    try {
                        exportOne(id)
                        lastError = null
                    } catch (error: Exception) {
                        Log.e(TAG, "export failed", error)
                        lastError = error.message ?: "Could not save the video"
                        break
                    }
                }
            }
            changesFlow.tryEmit(Unit)
        }
    }

    fun thumbnail(uri: String?): Bitmap? = Gallery.thumbnail(context, uri)

    private suspend fun adopt(id: String, folder: File, source: File): String {
        val info = probeVideo(source.absolutePath)
        if (info.durationMs < 200L) {
            folder.deleteRecursively()
            error("Could not read that recording")
        }
        val title = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        val detected = detectTimeline(source)
        File(folder, "timeline.json").writeText(detected)
        writeMeta(
            folder,
            Take(
                id = id,
                title = title,
                createdAt = System.currentTimeMillis(),
                key = "C",
                fps = info.fps,
                durationMs = info.durationMs,
                width = info.width,
                height = info.height,
                overlayUri = null,
                exportStamp = 0L,
            ),
        )
        val peaks = extractPeaks(source.absolutePath)
        File(folder, "peaks.txt").writePeaks(peaks)
        changesFlow.tryEmit(Unit)
        requestExport(id)
        return id
    }

    private fun detectTimeline(source: File): String {
        val wav = File(source.parentFile, "detect.wav")
        return try {
            extractWav(source, wav)
            Detector.notesJson(wav.absolutePath, "C")
        } catch (error: Exception) {
            Log.e(TAG, "detect", error)
            lastError = "The holes could not be detected. You can mark them by hand."
            encodeTimeline(TimelineDoc("C", emptyList()))
        } finally {
            wav.delete()
        }
    }

    private suspend fun exportOne(id: String) {
        val folder = folder(id)
        val meta = synchronized(this) { readMeta(folder) } ?: return
        val source = File(folder, "source.mp4")
        if (!source.exists()) return
        val timeline = synchronized(this) { readTimeline(id).let { it.copy(key = meta.key) } }
        val stamped = timeline.copy(key = meta.key)
        val output = File(folder, "export-tmp.mp4")
        VideoExport.burnOverlay(
            context = context,
            source = source,
            output = output,
            videoW = meta.width,
            videoH = meta.height,
            fps = meta.fps,
            key = meta.key,
            timeline = stamped,
        )
        val uri = Gallery.publish(context, output, meta.title, meta.overlayUri)
        output.delete()
        synchronized(this) {
            val fresh = readMeta(folder) ?: meta
            writeMeta(
                folder,
                fresh.copy(
                    overlayUri = uri.toString(),
                    exportStamp = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun readAll(keepMissing: Boolean): List<Take> {
        val dirs = root.listFiles() ?: return emptyList()
        val takes = ArrayList<Take>()
        for (folder in dirs) {
            if (!folder.isDirectory) continue
            val meta = readMeta(folder) ?: continue
            if (meta.overlayUri == null) {
                takes.add(meta)
                continue
            }
            if (Gallery.exists(context, meta.overlayUri)) {
                takes.add(meta)
            } else if (!keepMissing) {
                folder.deleteRecursively()
            }
        }
        return takes.sortedByDescending { it.createdAt }
    }

    private fun folder(id: String) = File(root, id)

    private fun newId(): String = System.currentTimeMillis().toString(36) +
        Integer.toString((Math.random() * 1_000_000).toInt(), 36)

    private fun readMeta(folder: File): Take? {
        val file = File(folder, "meta.json")
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            Take(
                id = json.getString("id"),
                title = json.getString("title"),
                createdAt = json.getLong("createdAt"),
                key = json.optString("key", "C"),
                fps = json.getDouble("fps"),
                durationMs = json.getLong("durationMs"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                overlayUri = json.optString("overlayUri").ifBlank { null },
                exportStamp = json.optLong("exportStamp"),
            )
        } catch (error: Exception) {
            Log.e(TAG, "bad meta", error)
            null
        }
    }

    private fun writeMeta(folder: File, take: Take) {
        val json = JSONObject()
        json.put("id", take.id)
        json.put("title", take.title)
        json.put("createdAt", take.createdAt)
        json.put("key", take.key)
        json.put("fps", take.fps)
        json.put("durationMs", take.durationMs)
        json.put("width", take.width)
        json.put("height", take.height)
        json.put("overlayUri", take.overlayUri ?: "")
        json.put("exportStamp", take.exportStamp)
        File(folder, "meta.json").writeText(json.toString(2))
    }

    private fun encodeTimeline(doc: TimelineDoc): String {
        val events = JSONArray()
        for (event in doc.events) {
            val holes = JSONArray()
            for (hole in event.holes) {
                holes.put(JSONObject().put("hole", hole.hole).put("bend", hole.bend))
            }
            events.put(
                JSONObject()
                    .put("t0", event.t0)
                    .put("t1", event.t1)
                    .put("breath", event.breath)
                    .put("holes", holes),
            )
        }
        return JSONObject().put("key", doc.key).put("events", events).toString(2)
    }

    private fun decodeTimeline(text: String): TimelineDoc {
        val json = JSONObject(text)
        val eventsJson = json.optJSONArray("events") ?: JSONArray()
        val events = ArrayList<NoteEvent>(eventsJson.length())
        for (i in 0 until eventsJson.length()) {
            val item = eventsJson.getJSONObject(i)
            val holesJson = item.optJSONArray("holes") ?: JSONArray()
            val holes = ArrayList<HoleMark>(holesJson.length())
            for (h in 0 until holesJson.length()) {
                val hole = holesJson.getJSONObject(h)
                holes.add(HoleMark(hole.getInt("hole"), hole.optInt("bend")))
            }
            events.add(
                NoteEvent(
                    t0 = item.getDouble("t0"),
                    t1 = item.getDouble("t1"),
                    breath = item.getString("breath"),
                    holes = holes,
                ),
            )
        }
        return TimelineDoc(json.optString("key", "C"), events)
    }

    companion object {
        private const val TAG = "Harmonica"
    }
}
