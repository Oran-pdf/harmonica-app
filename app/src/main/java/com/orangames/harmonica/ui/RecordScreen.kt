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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.orangames.harmonica.data.TakeStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class CaptureBridge {
    var start: (() -> Unit)? = null
    var stop: (() -> Unit)? = null
    var abandon: (() -> Unit)? = null
    var recording by mutableStateOf(false)
}

@Composable
fun RecordScreen(store: TakeStore, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val bridge = remember { CaptureBridge() }
    var videoOn by remember { mutableStateOf(true) }
    var permitted by remember { mutableStateOf(hasCapturePermission(context)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        permitted = granted[Manifest.permission.CAMERA] == true &&
            granted[Manifest.permission.RECORD_AUDIO] == true
        if (!permitted) error = "Allow the camera and microphone to record."
    }

    LaunchedEffect(Unit) {
        if (!permitted) {
            permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    LaunchedEffect(bridge.recording) {
        if (!bridge.recording) {
            elapsed = 0L
            return@LaunchedEffect
        }
        val started = System.currentTimeMillis()
        while (bridge.recording) {
            elapsed = System.currentTimeMillis() - started
            delay(200)
        }
    }

    fun finishFile(block: suspend () -> Unit) {
        scope.launch {
            busy = "Listening for the holes…"
            try {
                block()
                onDone()
            } catch (exception: Exception) {
                busy = null
                error = exception.message ?: "Could not save that recording"
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (permitted && videoOn) {
            VideoCapturePane(
                bridge = bridge,
                onFile = { file -> finishFile { store.importRecordedVideo(file) } },
                onFailed = {
                    error = "The camera did not open."
                    videoOn = false
                },
            )
        } else if (permitted) {
            AudioCapturePane(
                bridge = bridge,
                onFile = { file -> finishFile { store.importRecordedAudio(file) } },
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.VideocamOff, contentDescription = null, tint = Muted, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Picture is off", color = Muted)
                }
            }
        }

        if (bridge.recording) {
            Text(
                formatElapsed(elapsed),
                color = Cream,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        BackButton(
            onClick = {
                if (bridge.recording) bridge.abandon?.invoke()
                onBack()
            },
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
        )

        if (error != null && !permitted) {
            Text(
                error ?: "",
                color = Amber,
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Ink.copy(alpha = 0.62f))
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            val videoEnabled = permitted && busy == null && !bridge.recording
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (videoOn) CardBrown else Amber)
                        .clickable(enabled = videoEnabled) { videoOn = !videoOn },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (videoOn) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                        contentDescription = if (videoOn) "Turn video off" else "Turn video on",
                        tint = if (videoOn) Cream else Ink,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (videoOn) "Video off" else "Video on",
                    color = Cream,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RecordButton(
                    active = bridge.recording,
                    enabled = permitted && busy == null,
                    onClick = {
                        if (bridge.recording) bridge.stop?.invoke() else bridge.start?.invoke()
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (bridge.recording) "Stop" else "Record",
                    color = Cream,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        if (busy != null) {
            Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.86f)), contentAlignment = Alignment.Center) {
                Text(busy ?: "", color = Cream)
            }
        }
    }
}

private fun hasCapturePermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun VideoCapturePane(
    bridge: CaptureBridge,
    onFile: (File) -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var capture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var save by remember { mutableStateOf(false) }

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
                onFailed()
            }
        }, executor)
        onDispose {
            bridge.start = null
            bridge.stop = null
            bridge.abandon = null
            if (!save) {
                bridge.recording = false
                recording?.stop()
            }
            try {
                future.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    SideEffect {
        bridge.start = start@{
            val video = capture ?: return@start
            if (recording != null) return@start
            val file = File(context.cacheDir, "take-${System.currentTimeMillis()}.mp4")
            val options = FileOutputOptions.Builder(file).build()
            save = false
            recording = video.output.prepareRecording(context, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        recording = null
                        bridge.recording = false
                        if (save && !event.hasError()) onFile(file) else file.delete()
                    }
                }
            bridge.recording = true
        }
        bridge.stop = {
            save = true
            recording?.stop()
        }
        bridge.abandon = {
            save = false
            bridge.recording = false
            recording?.stop()
            recording = null
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun AudioCapturePane(bridge: CaptureBridge, onFile: (File) -> Unit) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var handedOff by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            bridge.start = null
            bridge.stop = null
            bridge.abandon = null
            bridge.recording = false
            val active = recorder
            recorder = null
            try {
                active?.stop()
            } catch (_: Exception) {
            }
            active?.release()
            if (!handedOff) File(context.cacheDir, "audio-latest.m4a").delete()
        }
    }

    SideEffect {
        bridge.start = start@{
            if (recorder != null) return@start
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
            recorder = next
            bridge.recording = true
        }
        bridge.stop = stop@{
            val active = recorder ?: return@stop
            recorder = null
            handedOff = true
            bridge.recording = false
            try {
                active.stop()
            } catch (_: Exception) {
            }
            active.release()
            val file = File(context.cacheDir, "audio-latest.m4a")
            if (file.exists()) onFile(file)
        }
        bridge.abandon = {
            val active = recorder
            recorder = null
            bridge.recording = false
            try {
                active?.stop()
            } catch (_: Exception) {
            }
            active?.release()
            File(context.cacheDir, "audio-latest.m4a").delete()
        }
    }

    Box(Modifier.fillMaxSize().background(Ink))
}

@Composable
private fun RecordButton(active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(if (active) Cream else RecordRed)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (active) 30.dp else 58.dp)
                .clip(if (active) RoundedCornerShape(7.dp) else CircleShape)
                .background(if (active) RecordRed else Cream.copy(alpha = 0f)),
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
