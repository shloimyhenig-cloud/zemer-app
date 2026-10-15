package com.jtech.zemer.viewmodels

import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jtech.zemer.R
import com.jtech.zemer.db.MusicDatabase
import com.jtech.zemer.playback.DownloadUtil
import com.jtech.zemer.playback.DownloadedMp3Converter
import com.jtech.zemer.recommendation.AlbumRecommendation
import com.jtech.zemer.recommendation.CollectionCandidateGatherer
import com.jtech.zemer.recommendation.CollectionDownloadPlanner
import com.jtech.zemer.recommendation.CollectionPickerSource
import com.jtech.zemer.recommendation.CollectionPreferences
import com.jtech.zemer.recommendation.CollectionSeeds
import com.jtech.zemer.recommendation.GatherStage
import com.jtech.zemer.recommendation.PickKind
import com.jtech.zemer.recommendation.PickOption
import com.jtech.zemer.recommendation.ZemerCollectionCatalog
import com.jtech.zemer.search.ZemerSearchRepository
import com.jtech.zemer.search.zemerSearchOptions
import com.jtech.zemer.utils.MediaStoreHelper
import com.jtech.zemer.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Year
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

enum class CollectionStep(val pickKind: PickKind?) {
    ARTISTS(PickKind.ARTIST),
    ALBUMS(PickKind.ALBUM),
    SONGS(PickKind.SONG),
    REVIEW(null),
}

data class CollectionDownloadSummary(
    val albums: Int,
    val failedAlbums: Int,
    val queuedSongs: Int,
    val convertedSongs: Int,
    val alreadyMp3: Int,
)

data class CollectionBuilderUiState(
    val step: CollectionStep = CollectionStep.ARTISTS,
    val query: String = "",
    val options: List<PickOption> = emptyList(),
    val optionsAreSuggestions: Boolean = true,
    val isLoadingOptions: Boolean = false,
    val pickedArtists: List<PickOption.Artist> = emptyList(),
    val pickedAlbums: List<PickOption.Album> = emptyList(),
    val pickedSongs: List<PickOption.Song> = emptyList(),
    val preferences: CollectionPreferences = CollectionPreferences(),
    val gatherStage: GatherStage? = null,
    val recommendations: List<AlbumRecommendation> = emptyList(),
    val checkedAlbumIds: Set<String> = emptySet(),
    val downloadProgress: Pair<Int, Int>? = null,
    val downloadSummary: CollectionDownloadSummary? = null,
    @StringRes val message: Int? = null,
) {
    val isGenerating: Boolean get() = gatherStage != null
    val isDownloading: Boolean get() = downloadProgress != null
    val pickCount: Int get() = pickedArtists.size + pickedAlbums.size + pickedSongs.size

    fun isPicked(option: PickOption): Boolean = when (option) {
        is PickOption.Artist -> pickedArtists.any { it.id == option.id }
        is PickOption.Album -> pickedAlbums.any { it.id == option.id }
        is PickOption.Song -> pickedSongs.any { it.id == option.id }
    }

    fun pickedFor(step: CollectionStep): List<PickOption> = when (step) {
        CollectionStep.ARTISTS -> pickedArtists
        CollectionStep.ALBUMS -> pickedAlbums
        CollectionStep.SONGS -> pickedSongs
        CollectionStep.REVIEW -> emptyList()
    }
}

/**
 * Build My Collection: pick singers → albums → songs, get 60-100 recommended complete albums, review,
 * and download the checked ones as MP3. Recommendation runs entirely on-device over Zemer's catalog.
 */
@HiltViewModel
class CollectionBuilderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val repository: ZemerSearchRepository,
    private val downloadUtil: DownloadUtil,
    private val converter: DownloadedMp3Converter,
) : ViewModel() {

    private val _state = MutableStateFlow(CollectionBuilderUiState())
    val state: StateFlow<CollectionBuilderUiState> = _state.asStateFlow()

    private val pickerSource = CollectionPickerSource(repository, database)
    private var catalog: ZemerCollectionCatalog? = null
    private var optionsJob: Job? = null
    private var generateJob: Job? = null

    init {
        loadOptions()
    }

    // ---- Picker steps -----------------------------------------------------------------------------

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        loadOptions(debounceMs = SEARCH_DEBOUNCE_MS)
    }

    fun togglePick(option: PickOption) {
        _state.update { s ->
            when (option) {
                is PickOption.Artist -> s.copy(pickedArtists = s.pickedArtists.toggled(option))
                is PickOption.Album -> s.copy(pickedAlbums = s.pickedAlbums.toggled(option))
                is PickOption.Song -> s.copy(pickedSongs = s.pickedSongs.toggled(option))
            }.copy(recommendations = emptyList(), checkedAlbumIds = emptySet(), downloadSummary = null)
        }
    }

    fun goTo(step: CollectionStep) {
        if (step == _state.value.step) return
        _state.update { it.copy(step = step, query = "", options = emptyList(), message = null) }
        if (step == CollectionStep.REVIEW) {
            if (_state.value.recommendations.isEmpty()) generate()
        } else {
            loadOptions()
        }
    }

    fun next() {
        val s = _state.value
        val nextStep = CollectionStep.entries.getOrNull(s.step.ordinal + 1) ?: return
        if (nextStep == CollectionStep.REVIEW && s.pickCount == 0) {
            _state.update { it.copy(message = R.string.collection_pick_something_first) }
            return
        }
        goTo(nextStep)
    }

    /** Back inside the flow; false when already on the first step (the screen then pops). */
    fun back(): Boolean {
        val previous = CollectionStep.entries.getOrNull(_state.value.step.ordinal - 1) ?: return false
        if (_state.value.isGenerating) generateJob?.cancel()
        goTo(previous)
        return true
    }

    private fun loadOptions(debounceMs: Long = 0) {
        val kind = _state.value.step.pickKind ?: return
        optionsJob?.cancel()
        optionsJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val s = _state.value
            val query = s.query.trim()
            _state.update { it.copy(isLoadingOptions = true, message = null) }
            try {
                val options = zemerSearchOptions(context)
                val result = if (query.isEmpty()) {
                    pickerSource.suggestions(kind, s.pickedArtists.map { it.seed }, s.pickedAlbums.map { it.seed }, options)
                } else {
                    pickerSource.search(kind, query, options)
                }
                _state.update {
                    it.copy(
                        options = result,
                        optionsAreSuggestions = query.isEmpty(),
                        isLoadingOptions = false,
                        message = if (result.isEmpty() && query.isNotEmpty()) R.string.collection_no_results else null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Collection picker load failed")
                _state.update { it.copy(isLoadingOptions = false, message = R.string.collection_load_failed) }
            }
        }
    }

    // ---- Recommendations --------------------------------------------------------------------------

    fun setPreferClassics(value: Boolean) = updatePreferences { it.copy(preferClassics = value) }

    fun setPreferCollections(value: Boolean) = updatePreferences { it.copy(preferCollections = value) }

    fun setTargetCount(value: Int) = updatePreferences { it.copy(targetCount = value) }

    private fun updatePreferences(change: (CollectionPreferences) -> CollectionPreferences) {
        _state.update { it.copy(preferences = change(it.preferences)) }
    }

    fun generate() {
        val s = _state.value
        if (s.pickCount == 0) {
            _state.update { it.copy(message = R.string.collection_pick_something_first) }
            return
        }
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    gatherStage = GatherStage.YOUR_ARTISTS,
                    recommendations = emptyList(),
                    checkedAlbumIds = emptySet(),
                    downloadSummary = null,
                    message = null,
                )
            }
            try {
                val zemerCatalog = ZemerCollectionCatalog(
                    repository = repository,
                    database = database,
                    mediaStoreHelper = MediaStoreHelper(context),
                    options = zemerSearchOptions(context),
                )
                catalog = zemerCatalog
                val seeds = CollectionSeeds(
                    artists = s.pickedArtists.map { it.seed },
                    albums = s.pickedAlbums.map { it.seed },
                    songs = s.pickedSongs.map { it.seed },
                )
                val result = CollectionCandidateGatherer(zemerCatalog).recommend(
                    seeds = seeds,
                    preferences = s.preferences,
                    currentYear = Year.now().value,
                    onStage = { stage -> _state.update { it.copy(gatherStage = stage) } },
                )
                _state.update {
                    it.copy(
                        gatherStage = null,
                        recommendations = result,
                        checkedAlbumIds = result.mapTo(LinkedHashSet()) { r -> r.album.id },
                        message = if (result.isEmpty()) R.string.collection_no_recommendations else null,
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(gatherStage = null) }
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Collection recommendations failed")
                reportException(e, "CollectionBuilder.generate")
                _state.update { it.copy(gatherStage = null, message = R.string.collection_load_failed) }
            }
        }
    }

    fun toggleAlbum(albumId: String) {
        _state.update { s ->
            val checked = s.checkedAlbumIds.toMutableSet()
            if (!checked.add(albumId)) checked.remove(albumId)
            s.copy(checkedAlbumIds = checked)
        }
    }

    fun setAllChecked(checked: Boolean) {
        _state.update { s ->
            s.copy(checkedAlbumIds = if (checked) s.recommendations.mapTo(LinkedHashSet()) { it.album.id } else emptySet())
        }
    }

    // ---- Download ---------------------------------------------------------------------------------

    fun downloadSelected() {
        val s = _state.value
        val zemerCatalog = catalog ?: return
        val albums = s.recommendations.filter { it.album.id in s.checkedAlbumIds }.map { it.album }
        if (albums.isEmpty() || s.isDownloading) return
        viewModelScope.launch {
            _state.update { it.copy(downloadProgress = 0 to albums.size, downloadSummary = null, message = null) }
            try {
                val planner = CollectionDownloadPlanner(database, zemerCatalog, downloadUtil, converter)
                val result = planner.downloadAll(albums) { done, total ->
                    _state.update { it.copy(downloadProgress = done to total) }
                }
                _state.update {
                    it.copy(
                        downloadProgress = null,
                        downloadSummary = CollectionDownloadSummary(
                            albums = result.albums,
                            failedAlbums = result.failedAlbums,
                            queuedSongs = result.queuedSongs,
                            convertedSongs = result.convertedSongs,
                            alreadyMp3 = result.alreadyMp3,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Collection download planning failed")
                reportException(e, "CollectionBuilder.download")
                _state.update { it.copy(downloadProgress = null, message = R.string.collection_download_failed) }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun <T : PickOption> List<T>.toggled(option: T): List<T> =
        if (any { it.id == option.id }) filterNot { it.id == option.id } else this + option

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
