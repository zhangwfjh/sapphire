package com.sapphire.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.settings.TranslateViewMode
import com.sapphire.domain.settings.UiPrefsStore

/**
 * Right-anchored view & preferences overlay. Slides in from the right edge.
 * Density toggle, theme indicator, translate-view toggle, and "All settings" link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RightDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    density: UiPrefsStore.FeedDensity,
    onDensityChange: (UiPrefsStore.FeedDensity) -> Unit,
    translateView: TranslateViewMode,
    onTranslateViewChange: (TranslateViewMode) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalSapphirePalette.current

    // Scrim
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(280)),
        exit = fadeOut(tween(280)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xE6000000))
                .clickable(onClick = onDismiss),
        )
    }

    // Panel
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(320)) { fullWidth -> fullWidth },
        exit = slideOutHorizontally(tween(320)) { fullWidth -> fullWidth },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.85f)
                    .background(palette.Ink)
                    .padding(top = 48.dp),
            ) {
                // Header
                Text(
                    "View & Preferences",
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.OnInk,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )

                HorizontalDivider(color = palette.InkStroke)

                // Density
                PrefSectionLabel("Density")
                SegmentedRow(
                    options = listOf("Dense" to true, "Rich" to false),
                    selected = density.isDense,
                    optionValue = { it.second },
                    optionLabel = { it.first },
                    onSelect = { onDensityChange(UiPrefsStore.FeedDensity(it)) },
                )

                HorizontalDivider(color = palette.InkStroke.copy(alpha = 0.5f))

                // Translate view
                PrefSectionLabel("Translate view")
                SegmentedRow(
                    options = listOf(
                        "双/A" to TranslateViewMode.BILINGUAL,
                        "Origin" to TranslateViewMode.ORIGIN,
                        "译" to TranslateViewMode.TRANSLATION,
                    ),
                    selected = translateView,
                    optionValue = { it.second },
                    optionLabel = { it.first },
                    onSelect = { onTranslateViewChange(it) },
                )

                HorizontalDivider(color = palette.InkStroke.copy(alpha = 0.5f))

                // All settings link
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); onOpenSettings() }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("All settings", style = MaterialTheme.typography.bodyLarge,
                            color = palette.OnInk, fontWeight = FontWeight.Medium)
                        Text("Retention, API key, data", style = MaterialTheme.typography.bodySmall,
                            color = palette.OnInkFaint)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null, tint = palette.OnInkFaint)
                }
            }
        }
    }
}

@Composable
private fun PrefSectionLabel(text: String) {
    val palette = LocalSapphirePalette.current
    Text(
        text,
        style = SapphireMono.Label,
        color = palette.OnInkFaint,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun <T> SegmentedRow(
    options: List<Pair<String, T>>,
    selected: T,
    optionValue: (Pair<String, T>) -> T,
    optionLabel: (Pair<String, T>) -> String,
    onSelect: (T) -> Unit,
) {
    val palette = LocalSapphirePalette.current
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.InkElevated)
            .padding(3.dp),
    ) {
        options.forEach { option ->
            val isSelected = optionValue(option) == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) palette.AccentDeep else Color.Transparent)
                    .clickable { onSelect(optionValue(option)) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    optionLabel(option),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) palette.OnInk else palette.OnInkFaint,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
