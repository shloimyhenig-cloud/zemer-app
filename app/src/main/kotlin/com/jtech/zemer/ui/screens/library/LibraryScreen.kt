package com.jtech.zemer.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.jtech.zemer.R
import com.jtech.zemer.constants.BlockVideosKey
import com.jtech.zemer.constants.BlockPodcastsKey
import com.jtech.zemer.constants.ChipSortTypeKey
import com.jtech.zemer.constants.LibraryFilter
import com.jtech.zemer.ui.component.ChipsRow
import com.jtech.zemer.ui.component.ChipsRowBottomPadding
import com.jtech.zemer.ui.component.ChipsRowTopPadding
import com.jtech.zemer.ui.screens.collection.COLLECTION_BUILDER_ROUTE
import com.jtech.zemer.utils.rememberEnumPreference
import com.jtech.zemer.utils.rememberPreference

@Composable
fun LibraryScreen(navController: NavController) {
    var filterType by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
    val (blockVideos, _) = rememberPreference(BlockVideosKey, false)
    val (blockPodcasts, _) = rememberPreference(BlockPodcastsKey, false)

    val availableFilters = buildList {
        add(LibraryFilter.SONGS)
        if (!blockVideos) add(LibraryFilter.VIDEOS)
        add(LibraryFilter.ARTISTS)
        add(LibraryFilter.ALBUMS)
        add(LibraryFilter.PLAYLISTS)
        if (!blockPodcasts) add(LibraryFilter.PODCASTS)
        add(LibraryFilter.LIBRARY)
    }

    val filterContent = @Composable {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = ChipsRowTopPadding, bottom = ChipsRowBottomPadding),
        ) {
            ChipsRow(
                chips =
                availableFilters.associateWith { filter ->
                    when (filter) {
                        LibraryFilter.PLAYLISTS -> stringResource(R.string.filter_playlists)
                        LibraryFilter.SONGS -> stringResource(R.string.filter_songs)
                        LibraryFilter.VIDEOS -> stringResource(R.string.videos)
                        LibraryFilter.ALBUMS -> stringResource(R.string.filter_albums)
                        LibraryFilter.ARTISTS -> stringResource(R.string.filter_artists)
                        LibraryFilter.PODCASTS -> stringResource(R.string.filter_podcasts)
                        LibraryFilter.LIBRARY -> ""
                    }
                }.filterKeys { it != LibraryFilter.LIBRARY }.toList(),
                currentValue = filterType,
                onValueUpdate = {
                    filterType =
                        if (filterType == it) {
                            LibraryFilter.LIBRARY
                        } else {
                            it
                        }
                },
                modifier = Modifier.weight(1f),
            )
            // Entry to Build My Collection (recommend + bulk-download complete albums as MP3).
            FilledTonalButton(
                onClick = { navController.navigate(COLLECTION_BUILDER_ROUTE) },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.padding(end = 12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.library_add),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.collection_build_title))
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        when (filterType) {
            LibraryFilter.LIBRARY -> LibraryMixScreen(navController, filterContent)
            LibraryFilter.PLAYLISTS -> LibraryPlaylistsScreen(navController, filterContent)
            LibraryFilter.SONGS -> LibrarySongsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY })

            LibraryFilter.ALBUMS -> LibraryAlbumsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY })

            LibraryFilter.ARTISTS -> LibraryArtistsScreen(
                navController,
                { filterType = LibraryFilter.LIBRARY })

            LibraryFilter.VIDEOS -> if (!blockVideos) {
                LibraryVideosScreen(
                    navController,
                    { filterType = LibraryFilter.LIBRARY })
            } else {
                // Fallback to LIBRARY if videos are blocked
                LibraryMixScreen(navController, filterContent)
            }

            LibraryFilter.PODCASTS -> if (!blockPodcasts) {
                LibraryPodcastsScreen(
                    navController,
                    { filterType = LibraryFilter.LIBRARY })
            } else {
                // Fallback to LIBRARY if podcasts are blocked
                LibraryMixScreen(navController, filterContent)
            }
        }
    }
}
