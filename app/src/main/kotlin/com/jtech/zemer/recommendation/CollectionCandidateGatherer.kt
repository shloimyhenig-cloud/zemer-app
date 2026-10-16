package com.jtech.zemer.recommendation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The read side of the recommender: Zemer's own, already content-filtered catalog. Implemented over
 * the Zemer server + local DB in the app ([ZemerCollectionCatalog]) and by fakes in tests. Every call
 * may fail; the gatherer treats a failure as "no signal from that source", never as a fatal error.
 */
interface CollectionCatalog {
    suspend fun artistDiscography(artistId: String): ArtistDiscography?

    /** Related music for a seed ("song" or "album" kind), as Zemer radio picks it. */
    suspend fun relatedSongs(kind: String, seedId: String): List<CatalogSong>

    /** Telemetry-ranked popular albums, most popular first. */
    suspend fun popularAlbums(): List<CatalogAlbum>

    /** Chart positions (Top 50 and similar server charts) rolled up to albums. */
    suspend fun chartAlbums(): List<RankedAlbum>

    suspend fun albumDetails(album: CatalogAlbum): AlbumDetails?

    suspend fun listeningHistory(): ListeningHistory

    /** Albums whose every track is already downloaded on this device. */
    suspend fun fullyDownloadedAlbumIds(): Set<String>
}

/** Coarse progress for the UI while gathering (each step is a localized label there). */
enum class GatherStage { YOUR_ARTISTS, SIMILAR_MUSIC, RELATED_ARTISTS, POPULAR, CHECKING_ALBUMS, RANKING }

/**
 * Collects candidate albums from every signal Zemer has, then hands them to [CollectionRecommender].
 *
 * Order of work:
 *  1. Discographies of the picked artists and of the artists on the picked albums/songs.
 *  2. Related music (Zemer radio) for every picked song and album: the albums those tracks come from,
 *     and which artists keep showing up next to the user's picks.
 *  3. Discographies of the strongest of those related artists.
 *  4. Popularity, charts and on-device listening history as tie-breakers / extra evidence.
 *  5. The best-scoring candidates are opened to confirm they are full albums (track count).
 */
class CollectionCandidateGatherer(
    private val catalog: CollectionCatalog,
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxSeedArtists: Int = 15,
        val maxSongSeeds: Int = 12,
        val maxAlbumSeeds: Int = 8,
        val maxRelatedArtists: Int = 15,
        val verifyPoolFactor: Float = 1.6f,
        val parallelRequests: Int = 6,
    )

    suspend fun recommend(
        seeds: CollectionSeeds,
        preferences: CollectionPreferences,
        currentYear: Int,
        onStage: (GatherStage) -> Unit = {},
    ): List<AlbumRecommendation> = coroutineScope {
        require(!seeds.isEmpty) { "At least one pick is required" }
        val gate = Semaphore(limits.parallelRequests)
        suspend fun <T> limited(block: suspend () -> T): T? =
            gate.withPermit { runCatching { block() }.getOrNull() }

        val pool = LinkedHashMap<String, AlbumEvidence>()
        fun evidence(album: CatalogAlbum): AlbumEvidence =
            pool.getOrPut(album.id) { AlbumEvidence(album) }.also { it.mergeAlbum(album) }

        // The picks themselves are part of the collection.
        seeds.albums.forEach { evidence(it).isPick = true }

        // 1. Discographies of picked + implied artists.
        onStage(GatherStage.YOUR_ARTISTS)
        val picked = seeds.pickedArtistIds
        val seedArtistIds = (picked + seeds.impliedArtistIds).take(limits.maxSeedArtists)
        val discographies = seedArtistIds
            .map { id -> async { id to limited { catalog.artistDiscography(id) } } }
            .awaitAll()
        discographies.forEach { (artistId, discography) ->
            discography ?: return@forEach
            (discography.albums + discography.singles.map { it.copy(isSingle = true) }).forEach { album ->
                val e = evidence(album)
                if (artistId in picked) e.inPickedArtistDiscography = true else e.inImpliedArtistDiscography = true
            }
        }

        // 2. Related music for each picked song and album.
        onStage(GatherStage.SIMILAR_MUSIC)
        val radioSeeds = seeds.songs.take(limits.maxSongSeeds).map { "song" to it.id } +
            seeds.albums.take(limits.maxAlbumSeeds).map { "album" to it.id }
        val radios = radioSeeds
            .map { (kind, id) -> async { id to (limited { catalog.relatedSongs(kind, id) } ?: emptyList()) } }
            .awaitAll()
        val excludedRelated = picked + seeds.impliedArtistIds
        val artistSeedHits = HashMap<String, MutableSet<String>>()
        val artistNames = HashMap<String, CatalogArtist>()
        radios.forEach { (seedId, songs) ->
            songs.forEach { song ->
                song.albumId?.let { albumId ->
                    val stub = CatalogAlbum(id = albumId, title = song.albumTitle.orEmpty(), artists = song.artists)
                    val e = pool[albumId] ?: evidence(stub)
                    e.similarToSeeds += seedId
                    e.relatedHits++
                }
                song.artists.forEach { artist ->
                    val id = artist.id ?: return@forEach
                    if (id in excludedRelated) return@forEach
                    artistSeedHits.getOrPut(id) { LinkedHashSet() } += seedId
                    artistNames.putIfAbsent(id, artist)
                }
            }
        }

        // 3. Discographies of the artists that keep appearing next to the picks.
        onStage(GatherStage.RELATED_ARTISTS)
        val relatedArtists = artistSeedHits.entries
            .sortedWith(compareByDescending<Map.Entry<String, MutableSet<String>>> { it.value.size }.thenBy { it.key })
            .take(limits.maxRelatedArtists)
        val relatedStrength = relatedArtists.associate { it.key to it.value.size }
        relatedArtists
            .map { (id, _) -> async { limited { catalog.artistDiscography(id) } } }
            .awaitAll()
            .filterNotNull()
            .forEach { discography -> discography.albums.forEach { evidence(it) } }
        pool.values.forEach { e ->
            val strength = e.album.artistIds.maxOfOrNull { relatedStrength[it] ?: 0 } ?: 0
            if (strength > e.relatedArtistStrength) e.relatedArtistStrength = strength
        }

        // 4. Popularity, charts, history.
        onStage(GatherStage.POPULAR)
        val popular = async { limited { catalog.popularAlbums() } ?: emptyList() }
        val charts = async { limited { catalog.chartAlbums() } ?: emptyList() }
        val history = async { limited { catalog.listeningHistory() } ?: ListeningHistory() }
        val downloaded = async { limited { catalog.fullyDownloadedAlbumIds() } ?: emptySet() }
        popular.await().forEachIndexed { index, album -> evidence(album).popularRank = index + 1 }
        charts.await().forEach { ranked ->
            val e = evidence(ranked.album)
            e.chartRank = minOf(e.chartRank ?: Int.MAX_VALUE, ranked.rank)
        }
        val listening = history.await()
        val albumRank = listening.albumIds.withIndex().associate { it.value to it.index + 1 }
        val artistRank = listening.artistIds.withIndex().associate { it.value to it.index + 1 }
        pool.values.forEach { e ->
            e.albumHistoryRank = albumRank[e.album.id]
            e.artistHistoryRank = e.album.artistIds.mapNotNull { artistRank[it] }.minOrNull()
        }
        val downloadedIds = downloaded.await()

        // 5. Confirm the strongest candidates are complete albums (and fill in titles/years for albums
        // only known from a related track). Only the likely winners are opened, to bound network use.
        onStage(GatherStage.CHECKING_ALBUMS)
        val eraCenter = CollectionRecommender.seedEraCenter(seeds)
        // Every CD of a picked singer is listed, so all of them are checked, plus the discovery pool.
        val verifyPool = (preferences.clampedTarget * limits.verifyPoolFactor).toInt() +
            pool.values.count { it.inPickedArtistDiscography }
        pool.values
            .filter { !it.album.isSingle && it.album.id !in downloadedIds }
            .sortedByDescending { CollectionRecommender.score(it, preferences, eraCenter, currentYear).rawScore }
            .take(verifyPool)
            .map { e -> async { e to limited { catalog.albumDetails(e.album) } } }
            .awaitAll()
            .forEach { (e, details) ->
                if (details == null) {
                    // Couldn't open it: an album known only from a related track has no title to show.
                    if (e.album.title.isBlank()) e.trackCount = 0
                    return@forEach
                }
                e.mergeAlbum(details.album)
                if (e.album.title.isBlank()) e.album = e.album.copy(title = details.album.title)
                e.trackCount = details.trackCount
            }

        onStage(GatherStage.RANKING)
        CollectionRecommender.recommend(
            seeds = seeds,
            evidence = pool.values.filter { it.album.title.isNotBlank() && (it.trackCount != null || !needsVerification(it)) },
            preferences = preferences,
            downloadedAlbumIds = downloadedIds,
            currentYear = currentYear,
        )
    }

    // An album only seen through a related track was never opened (it fell outside the verify pool):
    // its completeness is unknown, so it is not offered.
    private fun needsVerification(e: AlbumEvidence): Boolean =
        !e.isPick && !e.inPickedArtistDiscography && !e.inImpliedArtistDiscography && e.relatedArtistStrength == 0 &&
            e.popularRank == null && e.chartRank == null
}
