package com.orangames.harmonica.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextAlign
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
            .padding(horizontal = 22.dp, vertical = 28.dp),
    ) {
        Text("Harmonica", style = androidx.compose.material3.MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Upload", Modifier.weight(1f)) { pickVideo.launch("video/*") }
            ActionButton("Record", Modifier.weight(1f)) { onRecord() }
        }
        model.message?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Amber, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(22.dp))
        if (model.takes.isEmpty() && model.busy == null) {
            Text(
                "Overlaid videos will show up here.",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
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
        if (model.busy != null) {
            Spacer(Modifier.height(18.dp))
            Text(model.busy ?: "", color = Cream)
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

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBrown)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
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
    var thumb by remember(take.overlayUri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(take.overlayUri) {
        thumb = store.thumbnail(take.overlayUri)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBrown)
            .combinedClickable(onClick = onPlay, onLongClick = onDelete)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 108.dp, height = 72.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumb
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(take.title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (take.ready) formatClock(take.durationMs) else "Drawing the harmonica…",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Correct",
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onEdit)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            color = Amber,
            textAlign = TextAlign.Center,
        )
    }
}
