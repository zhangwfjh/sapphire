package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.R
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.design.accentGlow
import com.sapphire.app.ui.design.grainOverlay
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono

/**
 * PRD §3.1 — phrase input → Tier-1 LLM curates taxonomy → routes to review wizard.
 *
 * Editorial hero: the cold-open surface. A serif headline poses the single question the
 * product exists to answer, the only input on screen catches the accent glow, and a
 * grain overlay warms the charcoal. Loading and Error states are first-class per the
 * PRD's "clean Error Popup Modal".
 */
@Composable
fun OnboardingScreen(
    onReviewReady: () -> Unit,
    onCommitted: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var phrase by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is OnboardingUiState.Review) onReviewReady()
        if (state is OnboardingUiState.Committed) onCommitted()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(LocalSapphirePalette.current.Ink)
            .grainOverlay(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            OnboardingHero()
            PhraseInput(
                phrase = phrase,
                onPhraseChange = { phrase = it },
                onSubmit = { viewModel.generateFeed(phrase) },
                enabled = phrase.isNotBlank() && state !is OnboardingUiState.Loading,
                loading = state is OnboardingUiState.Loading,
            )
            Spacer(Modifier.height(48.dp))
            when (val s = state) {
                is OnboardingUiState.Loading -> CuratingState()
                is OnboardingUiState.Error -> ErrorModal(
                    message = s.message,
                    onDismiss = viewModel::dismissError,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun OnboardingHero() {
    val palette = LocalSapphirePalette.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(36.dp)
                    .accentGlow(palette.Accent, alpha = 0.6f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.InkElevated)
                    .border(1.dp, palette.Accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = palette.AccentBright)
            }
            Text(
                "SAPPHIRE",
                style = SapphireMono.Label,
                color = palette.OnInkMuted,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            "What do you want\nto follow?",
            style = MaterialTheme.typography.displayLarge,
            color = palette.OnInk,
        )
        Text(
            "Type a topic. AI builds the taxonomy, sources the feeds, and curates the stream — no accounts, all on-device.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.OnInkMuted,
        )
    }
}

@Composable
private fun PhraseInput(
    phrase: String,
    onPhraseChange: (String) -> Unit,
    onSubmit: () -> Unit,
    enabled: Boolean,
    loading: Boolean,
) {
    val palette = LocalSapphirePalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionEyebrow("TOPIC")
        TextField(
            value = phrase,
            onValueChange = onPhraseChange,
            placeholder = {
                Text(
                    "Artificial Intelligence · Biohacking · Climate Tech",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.OnInkFaint,
                )
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.OnInk),
            singleLine = true,
            enabled = !loading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                if (enabled) { onSubmit(); keyboard?.hide() }
            }),
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = palette.InkElevated,
                unfocusedContainerColor = palette.InkElevated,
                disabledContainerColor = palette.InkElevated,
                focusedIndicatorColor = palette.Accent,
                unfocusedIndicatorColor = palette.InkStrokeStrong,
                cursorColor = palette.Accent,
            ),
        )
        SubmitButton(onSubmit, enabled, loading)
    }
}

@Composable
private fun SubmitButton(onSubmit: () -> Unit, enabled: Boolean, loading: Boolean) {
    val palette = LocalSapphirePalette.current
    val bg = if (enabled) palette.Accent else palette.InkRaised
    val fg = if (enabled) Color.White else palette.OnInkFaint
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled) { onSubmit() }
            .then(if (enabled) Modifier.accentGlow(palette.Accent, alpha = 0.25f) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Text(
                "CURATE MY FEED",
                style = SapphireMono.Label,
                color = fg,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CuratingState() {
    val palette = LocalSapphirePalette.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = palette.Accent)
        Text(
            "CURATING YOUR TAXONOMY…",
            style = SapphireMono.Label,
            color = palette.OnInkMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorModal(message: String, onDismiss: () -> Unit) {
    val palette = LocalSapphirePalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(palette.Accent)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    stringResource(R.string.onboarding_error_dismiss).uppercase(),
                    style = SapphireMono.Label,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        containerColor = palette.InkElevated,
        titleContentColor = palette.OnInk,
        title = {
            Text(
                stringResource(R.string.onboarding_error_title),
                style = MaterialTheme.typography.headlineSmall,
                color = palette.OnInk,
            )
        },
        text = {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.OnInkMuted,
            )
        },
    )
}
