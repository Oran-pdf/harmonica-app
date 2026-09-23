package com.orangames.harmonica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private val Pill = RoundedCornerShape(16.dp)
private val HudBlue = Color(0xFF3AA8FF)
private val HudBrown = Color(0xFFA67C3E)
private val HudRed = Color(0xFFFF3434)
private val HudGreen = Color(0xFF46FF50)

@Composable
fun PrimaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(Pill)
            .background(Amber)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Ink, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun QuietAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(Pill)
            .background(CardBrown)
            .border(1.dp, Line, Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Cream, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Cream, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(CardBrown.copy(alpha = 0.92f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Cream,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun CorrectPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        "Correct",
        color = Amber,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CardBrown.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
fun StatusChip(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = Ink,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Amber)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
fun HarmonicaMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val barH = h * 0.42f
        val top = (h - barH) / 2f
        val left = w * 0.08f
        val barW = w * 0.84f
        drawRoundRect(
            color = HudBlue,
            topLeft = Offset(left, top),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(barH / 2f, barH / 2f),
        )
        val bandTop = top + barH * 0.42f
        drawRect(
            color = HudBrown,
            topLeft = Offset(left + barW * 0.08f, bandTop),
            size = Size(barW * 0.84f, barH * 0.18f),
        )
        val markW = barW * 0.07f
        val markH = barH * 0.16f
        for (i in 0 until 4) {
            val x = left + barW * (0.22f + i * 0.16f)
            drawRoundRect(
                color = HudRed,
                topLeft = Offset(x, top + barH * 0.16f),
                size = Size(markW, markH),
                cornerRadius = CornerRadius(markH / 2f, markH / 2f),
            )
            drawRoundRect(
                color = HudGreen,
                topLeft = Offset(x, top + barH * 0.68f),
                size = Size(markW, markH),
                cornerRadius = CornerRadius(markH / 2f, markH / 2f),
            )
        }
    }
}

@Composable
fun MarkPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(Ink), contentAlignment = Alignment.Center) {
        HarmonicaMark(Modifier.fillMaxSize().padding(10.dp))
    }
}
