package com.orangames.harmonica.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.orangames.harmonica.data.Take
import com.orangames.harmonica.data.TakeStore

@Composable
fun PlayerScreen(store: TakeStore, id: String, onBack: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    var take by remember { mutableStateOf<Take?>(null) }
    LaunchedEffect(id) { take = store.read(id) }
    val current = take
    if (current?.overlayUri == null) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Text(if (current == null) "Opening…" else "Drawing the harmonica…", color = Muted)
        }
        return
    }
    val player = remember(current.overlayUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(current.overlayUri)))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Ink)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    this.player = player
                }
            },
            update = { it.player = player },
        )
        BackButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
        )
        CorrectPill(
            onClick = onEdit,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp),
        )
    }
}
