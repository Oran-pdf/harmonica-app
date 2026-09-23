package com.orangames.harmonica.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.orangames.harmonica.data.TakeStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CaptureMode { Choose, Video, Audio }

@Composable
fun RecordScreen(store: TakeStore, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(CaptureMode.Choose) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<CaptureMode?>(null) }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val wanted = pending
        pending = null
        if (wanted != null && granted.values.all { it }) mode = wanted
        else if (wanted != null) error = "Allow the microphone to record."
    }

    fun ask(next: CaptureMode) {
        error = null
        val needed = if (next == CaptureMode.Video) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) mode = next
        else {
            pending = next
            permissions.launch(missing.toTypedArray())
        }
    }

    fun finishFile(block: suspend () -> Unit) {
        scope.launch {
            busy = "Drawing the harmonica…"
            try {
                block()
                onDone()
            } catch (exception: Exception) {
                busy = null
                error = exception.message ?: "Could not save that recording"
                mode = CaptureMode.Choose
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        when (mode) {
            CaptureMode.Choose -> ChooseCapture(
                error = error,
                onVideo = { ask(CaptureMode.Video) },
                onAudio = { ask(CaptureMode.Audio) },
                onBack = onBack,
            )
            CaptureMode.Video -> VideoCapturePane(
                enabled = busy == null,
                onCancel = { mode = CaptureMode.Choose },
                onFile = { file -> finishFile { store.importRecordedVideo(file) } },
            )
            CaptureMode.Audio -> AudioCapturePane(
                enabled = busy == null,
                onCancel = { mode = CaptureMode.Choose },
                onFile = { file -> finishFile { store.importRecordedAudio(file) } },
            )
        }
        if (busy != null) {
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
                Text(busy ?: "", color = Cream)
            }
        }
    }
}

@Composable
private fun ChooseCapture(
    error: String?,
    onVideo: () -> Unit,
    onAudio: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Record", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Video keeps the picture. Audio becomes a video with a quiet background.",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(28.dp))
        ChoiceCard("Video", "Camera and sound") { onVideo() }
        Spacer(Modifier.height(12.dp))
        ChoiceCard("Audio only", "Sound, then a still picture") { onAudio() }
        if (error != null) {
            Spacer(Modifier.height(16.dp))
            Text(error, color = Amber)
        }
        Spacer(Modifier.height(28.dp))
        Text("Back", color = Muted, modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp))
    }
}

@Composable
private fun ChoiceCard(title: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBrown)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(detail, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VideoCapturePane(enabled: Boolean, onCancel: () -> Unit, onFile: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var keep by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD)),
                )
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)
            capture = videoCapture
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    videoCapture,
                )
            } catch (_: Exception) {
                onCancel()
            }
        }, executor)
        onDispose {
            keep = false
            recording?.stop()
            try {
                future.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        RecordButton(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
            active = recording != null,
            enabled = enabled,
            onClick = {
                val video = capture ?: return@RecordButton
                if (recording != null) {
                    recording?.stop()
                    recording = null
                    return@RecordButton
                }
                val file = File(context.cacheDir, "take-${System.currentTimeMillis()}.mp4")
                val options = FileOutputOptions.Builder(file).build()
                recording = video.output.prepareRecording(context, options)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            recording = null
                            if (keep && !event.hasError()) onFile(file) else file.delete()
                        }
                    }
            },
        )
        Text(
            "Back",
            color = Cream,
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp).clickable {
                keep = false
                val active = recording
                recording = null
                active?.stop()
                onCancel()
            },
        )
    }
}

@Composable
private fun AudioCapturePane(enabled: Boolean, onCancel: () -> Unit, onFile: (File) -> Unit) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            recorder?.release()
        }
    }

    LaunchedEffect(recorder) {
        while (recorder != null) {
            elapsed = System.currentTimeMillis() - startedAt
            delay(200)
        }
    }

    Column(
        Modifier.fillMaxSize().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (recorder == null) "Ready" else formatElapsed(elapsed),
            style = androidx.compose.material3.MaterialTheme.typography.headlineLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text("Audio only", color = Muted)
        Spacer(Modifier.height(36.dp))
        RecordButton(active = recorder != null, enabled = enabled) {
            if (recorder != null) {
                val active = recorder
                recorder = null
                val file = active?.let {
                    try {
                        it.stop()
                    } catch (_: Exception) {
                    }
                    it.release()
                    File(context.cacheDir, "audio-latest.m4a")
                }
                if (file != null && file.exists()) onFile(file)
                return@RecordButton
            }
            val file = File(context.cacheDir, "audio-latest.m4a")
            if (file.exists()) file.delete()
            val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(128_000)
            next.setAudioSamplingRate(44_100)
            next.setOutputFile(file.absolutePath)
            next.prepare()
            next.start()
            startedAt = System.currentTimeMillis()
            recorder = next
        }
        Spacer(Modifier.height(28.dp))
        Text("Back", color = Muted, modifier = Modifier.clickable {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            recorder?.release()
            recorder = null
            onCancel()
        })
    }
}

@Composable
private fun RecordButton(
    modifier: Modifier = Modifier,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(78.dp)
            .clip(CircleShape)
            .background(if (active) Cream else RecordRed)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (active) 28.dp else 54.dp)
                .clip(if (active) RoundedCornerShape(6.dp) else CircleShape)
                .background(if (active) RecordRed else Cream.copy(alpha = 0.0f)),
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
