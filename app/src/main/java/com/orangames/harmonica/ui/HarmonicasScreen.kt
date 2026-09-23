package com.orangames.harmonica.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.orangames.harmonica.data.HoleMark
import com.orangames.harmonica.data.Look
import com.orangames.harmonica.data.Looks
import com.orangames.harmonica.data.NoteEvent
import com.orangames.harmonica.data.SchemeStore
import com.orangames.harmonica.media.SchemePainter
import com.orangames.harmonica.media.measureHud

private const val LOOP = 6.4f

private val sample = listOf(
    NoteEvent(0.15, 1.15, "blow", listOf(HoleMark(4, 0))),
    NoteEvent(1.35, 2.45, "draw", listOf(HoleMark(2, 0))),
    NoteEvent(2.6, 3.85, "blow", listOf(HoleMark(5, 0), HoleMark(6, 0), HoleMark(7, 0))),
    NoteEvent(4.05, 5.15, "draw", listOf(HoleMark(6, 0))),
    NoteEvent(5.3, 5.85, "blow", listOf(HoleMark(9, 0))),
)

@Composable
fun HarmonicasScreen(onBack: () -> Unit) {
    var making by remember { mutableStateOf(false) }
    if (making) {
        MakeYourOwnScreen(onBack = { making = false }, onAdded = { making = false })
        return
    }
    val context = LocalContext.current
    val looks = SchemeStore.all()
    val selected = SchemeStore.selectedId
    var pendingDelete by remember { mutableStateOf<Look?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.width(14.dp))
            Text("Harmonicas", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Touch one to use it on new videos. Hold one you made to remove it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        QuietAction("Make your own", Icons.Filled.Tune, { making = true }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Text("Schemes", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            items(looks, key = { it.id }) { look ->
                SchemeCard(
                    look = look,
                    inUse = look.id == selected,
                    onUse = { SchemeStore.select(context, look.id) },
                    onDelete = { if (!look.builtin) pendingDelete = look },
                )
            }
        }
    }
    pendingDelete?.let { look ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${look.title}?") },
            text = { Text("It leaves your list. Simple stays available.", color = Muted) },
            confirmButton = {
                TextButton(onClick = {
                    SchemeStore.delete(context, look.id)
                    pendingDelete = null
                }) { Text("Remove", color = RecordRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep", color = Cream) }
            },
            containerColor = CardBrown,
            titleContentColor = Cream,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SchemeCard(look: Look, inUse: Boolean, onUse: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBrown)
            .border(1.dp, if (inUse) Amber else Line, shape)
            .combinedClickable(onClick = onUse, onLongClick = onDelete)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(look.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(look.blurb, style = MaterialTheme.typography.bodyMedium)
            }
            if (inUse) {
                Spacer(Modifier.width(8.dp))
                StatusChip("In use")
            }
        }
        Spacer(Modifier.height(10.dp))
        SchemeStage(look, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)))
    }
}

@Composable
private fun MakeYourOwnScreen(onBack: () -> Unit, onAdded: () -> Unit) {
    val context = LocalContext.current
    var harp by remember { mutableIntStateOf(1) }
    var lit by remember { mutableIntStateOf(1) }
    var idle by remember { mutableIntStateOf(1) }
    var motion by remember { mutableIntStateOf(1) }
    var backdrop by remember { mutableIntStateOf(-1) }
    val draft = Look("draft", "Your own", "", false, harp, lit, idle, motion, backdrop)
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.width(14.dp))
            Text("Make your own", style = MaterialTheme.typography.headlineLarge)
        }
        Spacer(Modifier.height(14.dp))
        SchemeStage(draft, Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)))
        Spacer(Modifier.height(18.dp))
        ChoiceRow("Harmonica", Looks.harps, harp) { harp = it }
        ChoiceRow("Notes on", Looks.lits, lit) { lit = it }
        ChoiceRow("Notes off", Looks.idles, idle) { idle = it }
        ChoiceRow("Motion", Looks.motions, motion) { motion = it }
        ChoiceRow("Behind it", listOf("None") + Looks.backdrops, backdrop + 1) { backdrop = it - 1 }
        Spacer(Modifier.height(18.dp))
        PrimaryAction(
            "Add to schemes",
            Icons.Filled.Add,
            {
                SchemeStore.add(context, harp, lit, idle, motion, backdrop)
                onAdded()
            },
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ChoiceRow(title: String, names: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        names.forEachIndexed { index, name ->
            val on = index == selected
            Text(
                name,
                color = if (on) Ink else Cream,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) Amber else CardBrown)
                    .border(1.dp, if (on) Amber else Line, RoundedCornerShape(14.dp))
                    .clickable { onPick(index) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SchemeStage(look: Look, modifier: Modifier) {
    val layout = remember { measureHud(720) }
    val clock = rememberInfiniteTransition(label = look.id + look.harp + look.motion)
    val time by clock.animateFloat(
        initialValue = 0f,
        targetValue = LOOP,
        animationSpec = infiniteRepeatable(tween((LOOP * 1000).toInt(), easing = LinearEasing)),
        label = "phrase",
    )
    Canvas(
        modifier
            .aspectRatio(layout.width / layout.height.toFloat())
            .background(Color(0xFF161310)),
    ) {
        val s = size.width / layout.width
        scale(s, s, Offset.Zero) {
            drawIntoCanvas { canvas ->
                SchemePainter.draw(
                    canvas.nativeCanvas,
                    layout,
                    0f,
                    0f,
                    "C",
                    time.toDouble(),
                    sample,
                    look,
                )
            }
        }
    }
}
