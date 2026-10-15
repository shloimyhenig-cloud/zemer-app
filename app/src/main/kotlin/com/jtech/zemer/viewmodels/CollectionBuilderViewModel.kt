package com.jtech.zemer.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jtech.zemer.db.MusicDatabase
import com.jtech.zemer.db.entities.Song
import com.jtech.zemer.recommendation.AlbumRecommendationCandidate
import com.jtech.zemer.recommendation.CollectionRecommendationRanker
import com.jtech.zemer.recommendation.CollectionSeed
import com.jtech.zemer.search.ZemerSearchRepository
import com.jtech.zemer.search.zemerSearchOptions
import com.metrolist.innertube.YouTube.SearchFilter
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


enum class CollectionPickKind { ARTIST, ALBUM, SONG }

data class CollectionRecommendationUi(
    val album: AlbumItem,
    val score: Int,
    val reasons: List<String>,
)

data class CollectionBuilderUiState(
    val searchQuery: String = "",
    val pickKind: CollectionPickKind = CollectionPickKind.ARTIST,
    val searchResults: List<YTItem> = emptyList(),
    val selectedArtists: List<ArtistItem> = emptyList(),
    val selectedAlbums: List<AlbumItem> = emptyList(),
    val selectedSongs: List<SongItem> = emptyList(),
    val preferClassics: Boolean = true,
    val preferCollections: Boolean = true,
    val recommendations: List<CollectionRecommendationUi> = emptyList(),
    val isSearching: Boolean = false,
    val isGenerating: Boolean = false,
    val isPreparingDownloads: Boolean = false,
    val error: String? = null,
) {
    val selectionCount: Int
        get() = selectedArtists.size + selectedAlbums.size + selectedSongs.size
}

@HiltViewModel
class CollectionBuilderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val zemerRepository: ZemerSearchRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CollectionBuilderUiState())
    val uiState: StateFlow<CollectionBuilderUiState> = _uiState.asStateFlow()

    fun setQuery(value: String) {
        _uiState.value = _uiState.value.copy(searchQuery = value)
    }

    fun setPickKind(kind: CollectionPickKind) {
        _uiState.value = _uiState.value.copy(pickKind = kind, searchResults = emptyList(), error = null)
    }

    fun setPreferClassics(value: Boolean) {
        _uiState.value = _uiState.value.copy(preferClassics = value, recommendations = emptyList())
    }

    fun setPreferCollections(value: Boolean) {
        _uiState.value = _uiState.value.copy(preferCollections = value, recommendations = emptyList())
    }

    fun clearRecommendations() {
        _uiState.value = _uiState.value.copy(recommendations = emptyList(), error = null)
    }

    fun search() {
        val state = _uiState.value
        val query = state.searchQuery.trim()
        if (query.isBlank()) {
            _uiState.value = state.copy(error = "Type a singer, album, or song name first.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            val filter = when (_uiState.value.pickKind) {
                CollectionPickKind.ARTIST -> SearchFilter.FILTER_ARTIST
                CollectionPickKind.ALBUM -> SearchFilter.FILTER_ALBUM
                CollectionPickKind.SONG -> SearchFilter.FILTER_SONG
            }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    zemerRepository.filtered(query, filter, zemerSearchOptions(context)).items
                        .filter { item ->
                            when (_uiState.value.pickKind) {
                                CollectionPickKind.ARTIST -> item is ArtistItem
                                CollectionPickKind.ALBUM -> item is AlbumItem
                                CollectionPickKind.SONG -> item is SongItem && !item.isVideo && !item.isEpisode
                            }
                        }
                        .take(30)
                }
            }
            result.onSuccess { found ->
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchResults = found,
                    error = if (found.isEmpty()) "No matching results found." else null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    error = error.message ?: "Search failed. Please try again.",
                )
            }
        }
    }

    fun toggleSelection(item: YTItem) {
        val state = _uiState.value
        _uiState.value = when (item) {
            is ArtistItem -> state.copy(
                selectedArtists = state.selectedArtists.toggleById(item),
                recommendations = emptyList(),
            )
            is AlbumItem -> state.copy(
                selectedAlbums = state.selectedAlbums.toggleById(item),
                recommendations = emptyList(),
            )
            is SongItem -> state.copy(
                selectedSongs = state.selectedSongs.toggleById(item),
                recommendations = emptyList(),
            )
            else -> state
        }
    }

    fun generateRecommendations() {
        val initial = _uiState.value
        if (initial.selectionCount == 0) {
            _uiState.value = initial.copy(error = "Choose at least one favorite first.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGenerating = true, error = null)
            val result = runCatching {
                withContext(Dispatchers.IO) { buildRecommendations(_uiState.value) }
            }
            result.onSuccess { recommendations ->
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    recommendations = recommendations,
                    error = if (recommendations.isEmpty()) {
                        "No album recommendations were found for these picks."
                    } else null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    error = error.message ?: "Could not build recommendations.",
                )
            }
        }
    }

    private suspend fun buildRecommendations(state: CollectionBuilderUiState): List<CollectionRecommendationUi> {
        val options = zemerSearchOptions(context)
        val selectedArtistIds = buildSet {
            addAll(state.selectedArtists.map { it.id })
            state.selectedAlbums.forEach { album -> album.artists.orEmpty().mapNotNullTo(this) { it.id } }
            state.selectedSongs.forEach { song -> song.artists.mapNotNullTo(this) { it.id } }
        }
        val seedAlbumIds = state.selectedAlbums.map { it.id }.toSet()
        val seedSongIds = state.selectedSongs.map { it.id }.toSet()
        val selectedAlbumArtistIds = state.selectedAlbums.associate { album ->
            album.id to album.artists.orEmpty().mapNotNull { it.id }.toSet()
        }
        val selectedSongArtistIds = state.selectedSongs.associate { song ->
            song.id to song.artists.mapNotNull { it.id }.toSet()
        }

        val candidateAlbums = linkedMapOf<String, AlbumItem>()
        val popularityRank = mutableMapOf<String, Int>()
        val radioAlbumRelations = mutableMapOf<String, MutableSet<String>>()
        val radioSongRelations = mutableMapOf<String, MutableSet<String>>()

        runCatching { zemerRepository.homeRows(options) }.getOrNull()?.albums.orEmpty()
            .forEachIndexed { index, album ->
                candidateAlbums.putIfAbsent(album.id, album)
                popularityRank[album.id] = index + 1
            }

        selectedArtistIds.take(12).forEach { artistId ->
            runCatching { zemerRepository.artist(artistId, options) }.getOrNull()
                ?.sections
                .orEmpty()
                .flatMap { it.items }
                .filterIsInstance<AlbumItem>()
                .forEach { candidateAlbums.putIfAbsent(it.id, it) }
        }

        // Radio is Zemer's existing related-content signal. Turn the albums referenced by related
        // songs into full album candidates so song/album seeds influence more than raw popularity.
        state.selectedSongs.take(8).forEach { seedSong ->
            val related = runCatching { zemerRepository.radio("song", seedSong.id, options) }
                .getOrNull()?.songs.orEmpty()
            addAlbumsFromRelatedSongs(
                relatedSongs = related,
                seedId = seedSong.id,
                relationMap = radioSongRelations,
                candidateAlbums = candidateAlbums,
                options = options,
            )
        }
        state.selectedAlbums.take(8).forEach { seedAlbum ->
            val related = runCatching { zemerRepository.radio("album", seedAlbum.id, options) }
                .getOrNull()?.songs.orEmpty()
            addAlbumsFromRelatedSongs(
                relatedSongs = related,
                seedId = seedAlbum.id,
                relationMap = radioAlbumRelations,
                candidateAlbums = candidateAlbums,
                options = options,
            )
        }

        val seed = CollectionSeed(
            artistIds = selectedArtistIds,
            albumIds = seedAlbumIds,
            songIds = seedSongIds,
        )

        val candidateModels = candidateAlbums.values.map { album ->
            val artistIds = album.artists.orEmpty().mapNotNull { it.id }.toSet()
            val artistRelatedAlbums = selectedAlbumArtistIds
                .filterValues { ids -> ids.isNotEmpty() && artistIds.intersect(ids).isNotEmpty() }
                .keys
            val artistRelatedSongs = selectedSongArtistIds
                .filterValues { ids -> ids.isNotEmpty() && artistIds.intersect(ids).isNotEmpty() }
                .keys
            AlbumRecommendationCandidate(
                albumId = album.id,
                artistIds = artistIds,
                relatedToAlbumIds = artistRelatedAlbums + radioAlbumRelations[album.id].orEmpty(),
                relatedToSongIds = artistRelatedSongs + radioSongRelations[album.id].orEmpty(),
                popularityRank = popularityRank[album.id],
                releaseYear = album.year.takeIf { state.preferClassics },
                isCompilation = state.preferCollections && album.looksLikeCollection(),
            )
        }

        return CollectionRecommendationRanker.rank(seed, candidateModels, limit = 100)
            .mapNotNull { ranked ->
                candidateAlbums[ranked.candidate.albumId]?.let { album ->
                    CollectionRecommendationUi(album, ranked.score, ranked.reasons)
                }
            }
    }

    private suspend fun addAlbumsFromRelatedSongs(
        relatedSongs: List<SongItem>,
        seedId: String,
        relationMap: MutableMap<String, MutableSet<String>>,
        candidateAlbums: MutableMap<String, AlbumItem>,
        options: com.jtech.zemer.search.ZemerSearchOptions,
    ) {
        relatedSongs.mapNotNull { it.album?.id }
            .distinct()
            .take(16)
            .forEach { albumId ->
                relationMap.getOrPut(albumId) { mutableSetOf() }.add(seedId)
                if (albumId !in candidateAlbums) {
                    runCatching { zemerRepository.album(albumId, null, options) }
                        .getOrNull()
                        ?.album
                        ?.let { candidateAlbums[albumId] = it }
                }
            }
    }

    fun prepareSongsForDownload(
        albumIds: Set<String>,
        onReady: (List<Song>) -> Unit,
    ) {
        if (albumIds.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPreparingDownloads = true, error = null)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val options = zemerSearchOptions(context)
                    val albumsById = _uiState.value.recommendations.associateBy { it.album.id }
                    albumIds.flatMap { albumId ->
                        var songs = database.albumWithSongs(albumId).first()?.songs.orEmpty()
                        if (songs.isEmpty()) {
                            val album = albumsById[albumId]?.album
                            if (album != null) {
                                zemerRepository.album(album.id, album.playlistId, options)?.let { page ->
                                    database.transaction { insert(page) }
                                }
                                // MusicDatabase.transaction dispatches to Room's transaction executor.
                                // Wait briefly for that insert rather than turning a fast first tap into
                                // an empty bulk-download request.
                                repeat(24) {
                                    if (songs.isNotEmpty()) return@repeat
                                    delay(75)
                                    songs = database.albumWithSongs(albumId).first()?.songs.orEmpty()
                                }
                            }
                        }
                        songs
                    }.distinctBy { it.id }
                }
            }
            result.onSuccess { songs ->
                _uiState.value = _uiState.value.copy(
                    isPreparingDownloads = false,
                    error = if (songs.isEmpty()) "No downloadable songs were found." else null,
                )
                onReady(songs)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isPreparingDownloads = false,
                    error = error.message ?: "Could not prepare the selected albums for download.",
                )
            }
        }
    }
}

private fun <T : YTItem> List<T>.toggleById(item: T): List<T> =
    if (any { it.id == item.id }) filterNot { it.id == item.id } else this + item

private fun AlbumItem.looksLikeCollection(): Boolean {
    val normalized = title.lowercase()
    return listOf(
        "collection",
        "best of",
        "greatest",
        "hits",
        "anthology",
        "vol.",
        "volume",
        "those were the days",
    ).any(normalized::contains)
}
