package com.jtech.zemer.ui.screens.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.jtech.zemer.LocalPlayerAwareWindowInsets
import com.jtech.zemer.R
import com.jtech.zemer.recommendation.CollectionPreferences
import com.jtech.zemer.ui.component.AppBarTitle
import com.jtech.zemer.ui.component.BackTopAppBar
import com.jtech.zemer.ui.component.EmptyPlaceholder
import com.jtech.zemer.ui.component.SliderPreference
import com.jtech.zemer.ui.component.SwitchPreference
import com.jtech.zemer.ui.component.ZemerLoadingIndicator
import com.jtech.zemer.viewmodels.CollectionBuilderUiState
import com.jtech.zemer.viewmodels.CollectionBuilderViewModel
import com.jtech.zemer.viewmodels.CollectionStep
import kotlin.math.roundToInt

/** Nav route of the Build My Collection flow (no arguments). */
const val COLLECTION_BUILDER_ROUTE = "collection_builder"

/**
 * Build My Collection: Pick singers → Pick albums → Pick songs → Recommended albums (review, uncheck)
 * → Download selected albums as MP3. One screen, one scroll container per step, a fixed action bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionBuilderScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: CollectionBuilderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    BackHandler(enabled = state.step != CollectionStep.ARTISTS) { viewModel.back() }

    val insets = LocalPlayerAwareWindowInsets.current
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = insets.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding(),
                bottom = insets.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding() + ActionBarHeight,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "stepper") { StepHeader(state) }
            if (state.step == CollectionStep.REVIEW) reviewContent(state, viewModel) else pickerContent(state, viewModel)
        }

        ActionBar(
            state = state,
            onBack = { viewModel.back() },
            onNext = viewModel::next,
            onDownload = viewModel::downloadSelected,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(insets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        )

        BackTopAppBar(
            title = { AppBarTitle(stringResource(R.string.collection_build_title)) },
            navController = navController,
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
private fun StepHeader(state: CollectionBuilderUiState) {
    val titleRes = when (state.step) {
        CollectionStep.ARTISTS -> R.string.collection_step_artists
        CollectionStep.ALBUMS -> R.string.collection_step_albums
        CollectionStep.SONGS -> R.string.collection_step_songs
        CollectionStep.REVIEW -> R.string.collection_step_review
    }
    val hintRes = when (state.step) {
        CollectionStep.ARTISTS -> R.string.collection_step_hint_artists
        CollectionStep.ALBUMS -> R.string.collection_step_hint_albums
        CollectionStep.SONGS -> R.string.collection_step_hint_songs
        CollectionStep.REVIEW -> null
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        LinearProgressIndicator(
            progress = { (state.step.ordinal + 1f) / CollectionStep.entries.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
        hintRes?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun LazyListScope.pickerContent(state: CollectionBuilderUiState, viewModel: CollectionBuilderViewModel) {
    val picked = state.pickedFor(state.step)
    item(key = "search") {
        val labelRes = when (state.step) {
            CollectionStep.ALBUMS -> R.string.collection_search_albums
            CollectionStep.SONGS -> R.string.collection_search_songs
            else -> R.string.collection_search_artists
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            label = { Text(stringResource(labelRes)) },
            leadingIcon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
    if (picked.isNotEmpty()) {
        item(key = "picked") {
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    stringResource(R.string.collection_picked_count, picked.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                PickedChips(picked, onRemove = viewModel::togglePick, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
    item(key = "options_title") {
        Text(
            stringResource(if (state.optionsAreSuggestions) R.string.collection_suggestions else R.string.collection_results),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    state.message?.let { messageRes ->
        item(key = "message") { MessageText(stringResource(messageRes)) }
    }
    if (state.isLoadingOptions && state.options.isEmpty()) {
        item(key = "loading") { CenteredLoading() }
    }
    items(state.options, key = { "${state.step}_${it.id}" }) { option ->
        PickOptionRow(option = option, picked = state.isPicked(option), onToggle = { viewModel.togglePick(option) })
    }
}

private fun LazyListScope.reviewContent(state: CollectionBuilderUiState, viewModel: CollectionBuilderViewModel) {
    item(key = "prefs") {
        Column {
            SwitchPreference(
                title = { Text(stringResource(R.string.collection_prefer_classics)) },
                checked = state.preferences.preferClassics,
                onCheckedChange = viewModel::setPreferClassics,
                isEnabled = !state.isGenerating && !state.isDownloading,
            )
            SwitchPreference(
                title = { Text(stringResource(R.string.collection_prefer_collections)) },
                checked = state.preferences.preferCollections,
                onCheckedChange = viewModel::setPreferCollections,
                isEnabled = !state.isGenerating && !state.isDownloading,
            )
            SliderPreference(
                title = { Text(stringResource(R.string.collection_target_count, state.preferences.clampedTarget)) },
                value = state.preferences.clampedTarget.toFloat(),
                onValueChange = { viewModel.setTargetCount(it.roundToInt()) },
                isEnabled = !state.isGenerating && !state.isDownloading,
                valueRange = CollectionPreferences.MIN_TARGET.toFloat()..CollectionPreferences.MAX_TARGET.toFloat(),
                steps = (CollectionPreferences.MAX_TARGET - CollectionPreferences.MIN_TARGET) / 5 - 1,
                resetValue = CollectionPreferences.DEFAULT_TARGET.toFloat(),
                dialogTitle = { stringResource(R.string.collection_target_count, state.preferences.clampedTarget) },
                valueLabel = { stringResource(R.string.collection_target_count, it.roundToInt()) },
            )
            OutlinedButton(
                onClick = viewModel::generate,
                enabled = !state.isGenerating && !state.isDownloading,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.collection_regenerate)) }
        }
    }

    state.gatherStage?.let { stage ->
        item(key = "generating") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                ZemerLoadingIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(stage.labelRes()), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    state.message?.let { messageRes -> item(key = "message") { MessageText(stringResource(messageRes)) } }
    state.downloadProgress?.let { (done, total) ->
        item(key = "download_progress") {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.collection_preparing, done, total), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                Text(
                    stringResource(R.string.collection_keep_open),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    state.downloadSummary?.let { summary ->
        item(key = "download_summary") {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.collection_download_started, summary.queuedSongs, summary.albums - summary.failedAlbums),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (summary.convertedSongs > 0) MessageLine(stringResource(R.string.collection_download_converting, summary.convertedSongs))
                if (summary.alreadyMp3 > 0) MessageLine(stringResource(R.string.collection_download_already, summary.alreadyMp3))
                if (summary.failedAlbums > 0) MessageLine(stringResource(R.string.collection_download_failed_albums, summary.failedAlbums))
            }
        }
    }

    if (state.recommendations.isNotEmpty()) {
        item(key = "select_bar") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(R.string.collection_checked_count, state.checkedAlbumIds.size, state.recommendations.size),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.setAllChecked(true) }) { Text(stringResource(R.string.collection_select_all)) }
                TextButton(onClick = { viewModel.setAllChecked(false) }) { Text(stringResource(R.string.collection_select_none)) }
            }
        }
        items(state.recommendations, key = { "rec_${it.album.id}" }) { recommendation ->
            RecommendationRow(
                recommendation = recommendation,
                checked = recommendation.album.id in state.checkedAlbumIds,
                onToggle = { viewModel.toggleAlbum(recommendation.album.id) },
            )
        }
    } else if (!state.isGenerating && state.message == null) {
        item(key = "empty") {
            EmptyPlaceholder(icon = R.drawable.album, text = stringResource(R.string.collection_no_recommendations))
        }
    }
}

@Composable
private fun ActionBar(
    state: CollectionBuilderUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(ActionBarHeight).padding(horizontal = 16.dp),
        ) {
            if (state.step != CollectionStep.ARTISTS) {
                OutlinedButton(onClick = onBack, enabled = !state.isDownloading) {
                    Text(stringResource(R.string.collection_back))
                }
            }
            Spacer(Modifier.weight(1f))
            when (state.step) {
                CollectionStep.SONGS -> Button(onClick = onNext, enabled = state.pickCount > 0) {
                    Text(stringResource(R.string.collection_generate))
                }
                CollectionStep.REVIEW -> Button(
                    onClick = onDownload,
                    enabled = state.checkedAlbumIds.isNotEmpty() && !state.isGenerating && !state.isDownloading,
                ) {
                    Icon(painterResource(R.drawable.download), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.collection_download_selected))
                }
                else -> Button(onClick = onNext) {
                    Text(stringResource(R.string.collection_next))
                    Spacer(Modifier.size(4.dp))
                    Icon(painterResource(R.drawable.arrow_forward), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun MessageLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CenteredLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { ZemerLoadingIndicator() }
}

private val ActionBarHeight = 72.dp
