package com.sapphire.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sapphire.app.R
import com.sapphire.app.ui.design.SectionEyebrow
import com.sapphire.app.ui.theme.LocalSapphirePalette
import com.sapphire.app.ui.theme.SapphireMono
import com.sapphire.domain.model.SourceKind
import com.sapphire.domain.review.ReviewEdit
import com.sapphire.domain.review.ReviewFeed
import com.sapphire.domain.review.ReviewFolder
import com.sapphire.domain.review.ReviewModel

/**
 * PRD §3.1 Review & Approve wizard. Editorial taxonomy on a research grid: each folder
 * renders as a serif-headed panel with keyword pills, toggled feed rows, and inline inject
 * inputs. Approve commits via the ViewModel and routes to the feed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    onApproved: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val palette = LocalSapphirePalette.current

    LaunchedEffect(state) {
        if (state is OnboardingUiState.Committed) onApproved()
    }

    Scaffold(
        containerColor = palette.Ink,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("REVIEW", style = SapphireMono.Label, color = palette.Accent, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Curated taxonomy",
                            style = MaterialTheme.typography.titleSmall,
                            color = palette.OnInkMuted,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = palette.OnInkMuted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.Ink,
                    titleContentColor = palette.OnInk,
                ),
            )
        },
    ) { padding ->
        when (val s = state) {
            is OnboardingUiState.Review -> ReviewBody(
                model = s.model,
                onEdit = viewModel::applyEdit,
                onApprove = viewModel::approve,
                modifier = Modifier.padding(padding),
            )
            is OnboardingUiState.Committing -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = palette.Accent)
                    Spacer(Modifier.height(8.dp))
                    Text("BUILDING FEED…", style = SapphireMono.Label, color = palette.OnInkMuted)
                }
            }
            else -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nothing to review.", color = palette.OnInkMuted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewBody(
    model: ReviewModel,
    onEdit: (ReviewEdit) -> Unit,
    onApprove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalSapphirePalette.current
    Column(modifier.fillMaxSize().background(palette.Ink)) {
        Text(
            "Delete folders, toggle feeds, or add your own sources. An empty folder still gets the AI search agent.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.OnInkMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionEyebrow("TOPIC · ${model.topicPhrase.uppercase()}")
            }
            items(model.folders, key = { it.id }) { folder ->
                FolderPanel(folder, onEdit)
            }
        }
        ApproveBar(onApprove, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ApproveBar(onApprove: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalSapphirePalette.current
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(palette.Accent)
            .clickable(onClick = onApprove),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "APPROVE & BUILD FEED",
            style = SapphireMono.Label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderPanel(folder: ReviewFolder, onEdit: (ReviewEdit) -> Unit) {
    val palette = LocalSapphirePalette.current
    var newKeyword by remember { mutableStateOf("") }
    var newFeedUrl by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.InkElevated)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionEyebrow("FOLDER", modifier = Modifier.weight(1f))
            IconButton(
                onClick = { onEdit(ReviewEdit.DeleteFolder(folder.id)) },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete folder", tint = palette.OnInkFaint, modifier = Modifier.size(16.dp))
            }
        }
        EditableLabel(
            text = folder.name,
            onTextChange = { onEdit(ReviewEdit.RenameFolder(folder.id, it)) },
            eyebrow = null,
            style = MaterialTheme.typography.headlineSmall,
        )

        // Feeds
        if (folder.feeds.isEmpty()) {
            Text(
                stringResource(R.string.review_empty_feeds),
                style = SapphireMono.Body,
                color = palette.OnInkFaint,
            )
        } else {
            folder.feeds.forEach { feed -> FeedRow(feed, onEdit) }
        }

        // Keywords
        if (folder.keywords.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                folder.keywords.forEach { kw ->
                    KeywordPill(
                        text = kw.text,
                        userAdded = kw.userAdded,
                        onRemove = { onEdit(ReviewEdit.RemoveKeyword(kw.id)) },
                    )
                }
            }
        }

        InlineInput(
            value = newKeyword,
            onValueChange = { newKeyword = it },
            placeholder = stringResource(R.string.review_add_keyword_hint),
            onSubmit = {
                if (newKeyword.isNotBlank()) {
                    onEdit(ReviewEdit.AddKeyword(folder.id, newKeyword))
                    newKeyword = ""
                    keyboard?.hide()
                }
            },
            submitIcon = Icons.Filled.Add,
        )
        InlineInput(
            value = newFeedUrl,
            onValueChange = { newFeedUrl = it },
            placeholder = stringResource(R.string.review_add_feed_hint),
            onSubmit = {
                if (newFeedUrl.isNotBlank()) {
                    onEdit(
                        ReviewEdit.AddManualFeed(
                            folderId = folder.id,
                            url = newFeedUrl.trim(),
                            title = newFeedUrl.trim(),
                            kind = SourceKind.RSS,
                        ),
                    )
                    newFeedUrl = ""
                    keyboard?.hide()
                }
            },
            submitIcon = Icons.Outlined.RssFeed,
        )
    }
}

@Composable
private fun EditableLabel(
    text: String,
    onTextChange: (String) -> Unit,
    eyebrow: String?,
    style: androidx.compose.ui.text.TextStyle,
) {
    val palette = LocalSapphirePalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (eyebrow != null) {
            Text(
                eyebrow,
                style = SapphireMono.Label,
                color = palette.OnInkFaint,
            )
        }
        TextField(
            value = text,
            onValueChange = onTextChange,
            textStyle = style.copy(color = palette.OnInk),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = palette.Accent,
                unfocusedIndicatorColor = palette.InkStroke,
                cursorColor = palette.Accent,
            ),
        )
    }
}

@Composable
private fun KeywordPill(text: String, userAdded: Boolean, onRemove: () -> Unit) {
    val palette = LocalSapphirePalette.current
    val accent = userAdded
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(
                1.dp,
                if (accent) palette.Accent.copy(alpha = 0.5f) else palette.InkStrokeStrong,
                RoundedCornerShape(4.dp),
            )
            .background(if (accent) palette.Accent.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text,
            style = SapphireMono.Label,
            color = if (accent) palette.AccentBright else palette.OnInkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Outlined.Close,
            contentDescription = "Remove keyword",
            tint = if (accent) palette.AccentBright.copy(alpha = 0.7f) else palette.OnInkFaint,
            modifier = Modifier
                .size(12.dp)
                .clickable(onClick = onRemove),
        )
    }
}

@Composable
private fun FeedRow(feed: ReviewFeed, onEdit: (ReviewEdit) -> Unit) {
    val palette = LocalSapphirePalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (feed.enabled) palette.InkRaised.copy(alpha = 0.4f) else Color.Transparent)
            .border(1.dp, palette.InkStroke, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ToggleBox(
            checked = feed.enabled,
            onToggle = { onEdit(ReviewEdit.ToggleFeed(feed.id, it)) },
        )
        Column(Modifier.weight(1f)) {
            Text(
                feed.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (feed.enabled) palette.OnInk else palette.OnInkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                feed.url,
                style = SapphireMono.Body,
                color = palette.OnInkFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ToggleBox(checked: Boolean, onToggle: (Boolean) -> Unit) {
    val palette = LocalSapphirePalette.current
    Box(
        Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) palette.Accent else Color.Transparent)
            .border(
                1.dp,
                if (checked) palette.Accent else palette.InkStrokeStrong,
                RoundedCornerShape(4.dp),
            )
            .clickable { onToggle(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", color = Color.White, style = SapphireMono.Label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InlineInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSubmit: () -> Unit,
    submitIcon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val palette = LocalSapphirePalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(placeholder, style = MaterialTheme.typography.bodySmall, color = palette.OnInkFaint)
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.OnInk),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            modifier = Modifier.weight(1f),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = palette.InkRaised,
                unfocusedContainerColor = palette.InkRaised,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = palette.Accent,
            ),
        )
        IconButton(onClick = onSubmit, modifier = Modifier.size(32.dp)) {
            Icon(submitIcon, contentDescription = null, tint = palette.Accent, modifier = Modifier.size(18.dp))
        }
    }
}
