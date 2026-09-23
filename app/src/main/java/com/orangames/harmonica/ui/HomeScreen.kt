package com.orangames.harmonica.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.orangames.harmonica.data.Take
import com.orangames.harmonica.data.TakeStore
import com.orangames.harmonica.data.formatClock
import kotlinx.coroutines.launch

class LibraryModel(private val store: TakeStore) : ViewModel() {
    var takes by mutableStateOf<List<Take>>(emptyList())
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        viewModelScope.launch {
            takes = store.list()
            if (store.lastError != null) message = store.lastError
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            busy = "Listening for the holes…"
            message = null
            try {
                store.importVideo(uri)
                takes = store.list()
            } catch (error: Exception) {
                message = error.message ?: "Could not open that video"
            } finally {
                busy = null
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            takes = store.list()
        }
    }
}

@Composable
fun HomeScreen(
    model: LibraryModel,
    store: TakeStore,
    onRecord: () -> Unit,
    onDesigns: () -> Unit,
    onPlay: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.import(uri)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(store) {
        store.changes.collect { model.refresh() }
    }
    var pendingDelete by remember { mutableStateOf<Take?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 20.dp),
    ) {
        Text("Harmonica", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Play it back, or correct the holes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietAction("Upload", Icons.Filled.FileUpload, { pickVideo.launch("video/*") }, Modifier.weight(1f))
            PrimaryAction("Record", Icons.Filled.Videocam, onRecord, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        QuietAction("Designs", Icons.Filled.Palette, onDesigns, Modifier.fillMaxWidth())
        model.message?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Amber, style = MaterialTheme.typography.bodyMedium)
        }
        if (model.busy != null && model.takes.none { !it.ready }) {
            Spacer(Modifier.height(14.dp))
            StatusChip("Listening…")
        }
        Spacer(Modifier.height(22.dp))
        if (model.takes.isEmpty() && model.busy == null) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                HarmonicaMark(Modifier.size(width = 168.dp, height = 72.dp))
                Spacer(Modifier.height(18.dp))
                Text(
                    "Upload a video or record one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(model.takes, key = { it.id }) { take ->
                    TakeRow(
                        take = take,
                        store = store,
                        onPlay = { if (take.ready) onPlay(take.id) else onEdit(take.id) },
                        onEdit = { onEdit(take.id) },
                        onDelete = { pendingDelete = take },
                    )
                }
            }
        }
    }

    pendingDelete?.let { take ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this video?") },
            text = {
                Text(
                    "“${take.title}” will leave the list and the phone’s gallery.",
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        model.delete(take.id)
                        pendingDelete = null
                    },
                ) {
                    Text("Delete", color = RecordRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Keep", color = Cream)
                }
            },
            containerColor = CardBrown,
            titleContentColor = Cream,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TakeRow(
    take: Take,
    store: TakeStore,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    var thumb by remember(take.overlayUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(take.overlayUri) {
        thumb = store.thumbnail(take.overlayUri)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBrown)
            .border(1.dp, Line, shape)
            .combinedClickable(onClick = onPlay, onLongClick = onDelete)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 144.dp, height = 81.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumb
            if (take.ready && bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Ink.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Cream,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                MarkPlaceholder(Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(take.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            if (take.ready) {
                Text(
                    "${formatClock(take.durationMs)}  ·  ${take.key}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                StatusChip("Listening…")
            }
        }
        CorrectPill(onClick = onEdit)
    }
}
