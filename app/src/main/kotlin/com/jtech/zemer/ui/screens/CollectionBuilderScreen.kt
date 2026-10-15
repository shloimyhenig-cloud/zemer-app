package com.jtech.zemer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.jtech.zemer.LocalDatabase
import com.jtech.zemer.LocalDownloadUtil
import com.jtech.zemer.R
import com.jtech.zemer.playback.Mp3CollectionDownloadCoordinator
import com.jtech.zemer.search.zemerAlbumRoute
import com.jtech.zemer.viewmodels.CollectionBuilderViewModel
import com.jtech.zemer.viewmodels.CollectionPickKind
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionBuilderScreen(
    navController: NavController,
    viewModel: CollectionBuilderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.collection_build_title)) }) },
    ) { padding ->
        if (state.recommendations.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = viewModel::clearRecommendations) {
                    Text(stringResource(R.string.collection_change_favorites))
                }
                Text(
                    stringResource(R.string.collection_mp3_note),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (state.isPreparingDownloads) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.collection_preparing_albums))
                    }
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                val albumFallback = stringResource(R.string.collection_album_label)
                val albumResults = state.recommendations.map { recommendation ->
                    CollectionAlbumResult(
                        albumId = recommendation.album.id,
                        title = recommendation.album.title,
                        artist = recommendation.album.artists.orEmpty().joinToString { it.name }
                            .ifBlank { albumFallback },
                        score = recommendation.score,
                        reasons = recommendation.reasons,
                    )
                }
                CollectionRecommendationResults(
                    albums = albumResults,
                    onOpenAlbum = { albumId ->
                        state.recommendations.firstOrNull { it.album.id == albumId }?.album?.let { album ->
                            navController.navigate(zemerAlbumRoute(album))
                        }
                    },
                    onDownloadSelected = { albumIds ->
                        viewModel.prepareSongsForDownload(albumIds) { songs ->
                            songs.forEach { song ->
                                Mp3CollectionDownloadCoordinator.enqueue(
                                    context = context,
                                    database = database,
                                    downloadUtil = downloadUtil,
                                    song = song,
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.collection_intro),
                    style = MaterialTheme.typography.bodyLarge,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CollectionPickKind.entries.forEach { kind ->
                        FilterChip(
                            selected = state.pickKind == kind,
                            onClick = { viewModel.setPickKind(kind) },
                            label = { Text(kind.label()) },
                        )
                    }
                }

                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setQuery,
                    label = { Text(stringResource(R.string.collection_search_zemer)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = viewModel::search,
                    enabled = !state.isSearching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isSearching) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.collection_search_kind, state.pickKind.label().lowercase()))
                    }
                }

                Text(
                    stringResource(
                        R.string.collection_selected_counts,
                        state.selectedArtists.size,
                        state.selectedAlbums.size,
                        state.selectedSongs.size,
                    ),
                    fontWeight = FontWeight.Bold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.preferClassics,
                        onCheckedChange = viewModel::setPreferClassics,
                    )
                    Text(stringResource(R.string.collection_favor_classics))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.preferCollections,
                        onCheckedChange = viewModel::setPreferCollections,
                    )
                    Text(stringResource(R.string.collection_favor_collections))
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                Text(stringResource(R.string.collection_search_results), fontWeight = FontWeight.Bold)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.searchResults, key = { "${state.pickKind}-${it.id}" }) { item ->
                        CollectionPickRow(
                            item = item,
                            selected = when (item) {
                                is ArtistItem -> state.selectedArtists.any { it.id == item.id }
                                is AlbumItem -> state.selectedAlbums.any { it.id == item.id }
                                is SongItem -> state.selectedSongs.any { it.id == item.id }
                                else -> false
                            },
                            onToggle = { viewModel.toggleSelection(item) },
                        )
                    }
                }

                Button(
                    onClick = viewModel::generateRecommendations,
                    enabled = state.selectionCount > 0 && !state.isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isGenerating) {
                        CircularProgressIndicator()
                    } else {
                        Text(stringResource(R.string.collection_generate_recommendations))
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionPickRow(
    item: YTItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Bold)
                val subtitle = when (item) {
                    is AlbumItem -> item.artists.orEmpty().joinToString { it.name } +
                        (item.year?.let { " • $it" } ?: "")
                    is SongItem -> item.artists.joinToString { it.name }
                    is ArtistItem -> stringResource(R.string.collection_artist_label)
                    else -> ""
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CollectionPickKind.label(): String = stringResource(
    when (this) {
        CollectionPickKind.ARTIST -> R.string.artists
        CollectionPickKind.ALBUM -> R.string.albums
        CollectionPickKind.SONG -> R.string.songs
    }
)
