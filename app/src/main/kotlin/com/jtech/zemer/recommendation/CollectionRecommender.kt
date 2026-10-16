package com.jtech.zemer.recommendation

import kotlin.math.abs

/**
 * Everything the gatherer learned about one candidate album. Mutable while gathering, then scored once.
 */
class AlbumEvidence(album: CatalogAlbum) {
    var album: CatalogAlbum = album
        internal set
    var isPick = false
    var inPickedArtistDiscography = false
    var inImpliedArtistDiscography = false
    /** Distinct seeds whose related-music surfaced this album. */
    val similarToSeeds: MutableSet<String> = LinkedHashSet()
    var relatedHits = 0
    /** How many distinct seeds featured this album's artist in their related music (0 = none). */
    var relatedArtistStrength = 0
    var popularRank: Int? = null
    var chartRank: Int? = null
    var albumHistoryRank: Int? = null
    var artistHistoryRank: Int? = null
    var trackCount: Int? = null

    /** Merges a richer copy of the same album (year/thumbnail/single flag from another source). */
    fun mergeAlbum(other: CatalogAlbum) {
        album = album.copy(
            year = album.year ?: other.year,
            thumbnail = album.thumbnail ?: other.thumbnail,
            playlistId = album.playlistId ?: other.playlistId,
            artists = album.artists.ifEmpty { other.artists },
            isSingle = album.isSingle || other.isSingle,
        )
    }

    val hasTasteSignal: Boolean
        get() = isPick || inPickedArtistDiscography || inImpliedArtistDiscography ||
            similarToSeeds.isNotEmpty() || relatedArtistStrength > 0 ||
            albumHistoryRank != null || artistHistoryRank != null
}

/**
 * Scores and selects albums. Deterministic: the same evidence always yields the same list, which keeps
 * the review screen stable and the logic unit-testable.
 */
object CollectionRecommender {

    const val MIN_TRACKS_FOR_FULL_ALBUM = 4

    data class Scored(val evidence: AlbumEvidence, val rawScore: Int, val reasons: List<RecommendationReason>)

    fun recommend(
        seeds: CollectionSeeds,
        evidence: Collection<AlbumEvidence>,
        preferences: CollectionPreferences,
        downloadedAlbumIds: Set<String>,
        currentYear: Int,
    ): List<AlbumRecommendation> {
        val eraCenter = seedEraCenter(seeds)
        val eligible = evidence
            .filter { isFullAlbum(it) && it.album.id !in downloadedAlbumIds }
            .map { score(it, preferences, eraCenter, currentYear) }
            .sortedWith(
                compareByDescending<Scored> { it.rawScore }
                    .thenBy { it.evidence.popularRank ?: Int.MAX_VALUE }
                    .thenBy { it.evidence.album.title.lowercase() }
                    .thenBy { it.evidence.album.id },
            )
        // Every CD of a singer the user picked is listed (no cap, old and new) so the user can tick
        // exactly the ones they want; the target count applies to the discovery recommendations only.
        val pickedSingers = seeds.artists.filter { it.id != null }
        fun singerOf(e: AlbumEvidence): CatalogArtist? =
            pickedSingers.firstOrNull { it.id in e.album.artistIds }
        val (singerAlbums, others) = eligible.partition { it.evidence.inPickedArtistDiscography && singerOf(it.evidence) != null }
        val discovered = selectDiverse(others, seeds, preferences.clampedTarget)
        val top = (singerAlbums + discovered).maxOfOrNull { it.rawScore }?.coerceAtLeast(1) ?: 1
        fun Scored.toRecommendation() = AlbumRecommendation(
            album = this.evidence.album,
            score = (rawScore * 100 / top).coerceIn(1, 100),
            reasons = reasons,
            trackCount = this.evidence.trackCount,
            pickedSinger = singerOf(this.evidence)?.takeIf { this.evidence.inPickedArtistDiscography },
        )
        // Singers in the order they were picked; each singer's CDs newest first (unknown year last).
        val bySinger = singerAlbums.sortedWith(
            compareBy<Scored> { s -> pickedSingers.indexOfFirst { it.id in s.evidence.album.artistIds } }
                .thenByDescending { it.evidence.album.year ?: Int.MIN_VALUE }
                .thenBy { it.evidence.album.title.lowercase() },
        )
        return bySinger.map { it.toRecommendation() } + discovered.map { it.toRecommendation() }
    }

    /** A complete CD: not a single/EP, and (when the track list is known) at least a few songs. */
    fun isFullAlbum(evidence: AlbumEvidence): Boolean =
        !evidence.album.isSingle && (evidence.trackCount ?: MIN_TRACKS_FOR_FULL_ALBUM) >= MIN_TRACKS_FOR_FULL_ALBUM

    fun score(
        evidence: AlbumEvidence,
        preferences: CollectionPreferences,
        eraCenter: Int?,
        currentYear: Int,
    ): Scored {
        var score = 0
        val reasons = mutableListOf<RecommendationReason>()
        fun add(points: Int, reason: RecommendationReason?) {
            score += points
            if (reason != null && points > 0 && reason !in reasons) reasons += reason
        }

        if (evidence.isPick) add(PICK_POINTS, RecommendationReason.YOUR_PICK)
        when {
            evidence.inPickedArtistDiscography -> add(PICKED_ARTIST_POINTS, RecommendationReason.FAVORITE_ARTIST)
            evidence.inImpliedArtistDiscography -> add(IMPLIED_ARTIST_POINTS, RecommendationReason.ARTIST_FROM_YOUR_PICKS)
        }
        if (evidence.similarToSeeds.isNotEmpty()) {
            val seedPoints = minOf(evidence.similarToSeeds.size, 3) * SIMILAR_POINTS_PER_SEED
            val extraHits = (evidence.relatedHits - evidence.similarToSeeds.size).coerceIn(0, 4) * 2
            add(seedPoints + extraHits, RecommendationReason.SIMILAR_TO_YOUR_PICKS)
        }
        if (evidence.relatedArtistStrength > 0 && !evidence.inPickedArtistDiscography) {
            add(minOf(18, evidence.relatedArtistStrength * 6), RecommendationReason.RELATED_ARTIST)
        }
        evidence.albumHistoryRank?.let { add(maxOf(4, 16 - it), RecommendationReason.YOU_LISTEN_TO_IT) }
        evidence.artistHistoryRank?.let { add(maxOf(2, 10 - it), RecommendationReason.YOU_LISTEN_TO_IT) }
        evidence.popularRank?.let { add(rankPoints(it, 12, 9, 6, 3), RecommendationReason.POPULAR_ON_ZEMER) }
        evidence.chartRank?.let { add(rankPoints(it, 10, 7, 4, 2), RecommendationReason.CHART) }

        val year = evidence.album.year
        if (preferences.preferClassics && year != null) {
            when {
                year <= currentYear - CLASSIC_AGE_YEARS -> add(CLASSIC_POINTS, RecommendationReason.CLASSIC)
                year >= currentYear - 1 -> score -= NEW_RELEASE_PENALTY
            }
        }
        if (year != null && eraCenter != null && abs(year - eraCenter) <= ERA_WINDOW_YEARS) {
            add(ERA_POINTS, RecommendationReason.MATCHES_YOUR_ERA)
        }
        if (preferences.preferCollections && CollectionTitles.looksLikeCollection(evidence.album)) {
            add(COLLECTION_POINTS, RecommendationReason.COLLECTION)
        }
        // Popularity alone is a weak reason to fill someone's library: keep such albums below every
        // album that actually matches the user's taste.
        if (!evidence.hasTasteSignal) score -= NO_TASTE_PENALTY

        return Scored(evidence, score, reasons.sortedBy { it.ordinal })
    }

    /** The median release year of the picked albums (the clearest signal of the era the user loves). */
    fun seedEraCenter(seeds: CollectionSeeds): Int? {
        val years = seeds.albums.mapNotNull { it.year }.sorted()
        if (years.isEmpty()) return null
        return years[years.size / 2]
    }

    /**
     * Greedy pick in score order with a per-artist cap, so one prolific singer can't fill the whole
     * list. Picked artists get the most room, artists from the picked albums/songs a bit less, and
     * everyone else a few albums each. If the caps leave the list short, a second pass fills it from
     * the remaining taste-matching albums.
     */
    fun selectDiverse(sorted: List<Scored>, seeds: CollectionSeeds, target: Int): List<Scored> {
        val picked = seeds.pickedArtistIds
        val implied = seeds.impliedArtistIds
        val perArtist = HashMap<String, Int>()
        val chosen = ArrayList<Scored>(target)
        val chosenIds = HashSet<String>()
        for (candidate in sorted) {
            if (chosen.size >= target) break
            val key = artistKey(candidate.evidence.album)
            val cap = when {
                candidate.evidence.isPick -> Int.MAX_VALUE
                key in picked -> PICKED_ARTIST_CAP
                key in implied -> IMPLIED_ARTIST_CAP
                else -> OTHER_ARTIST_CAP
            }
            val count = perArtist[key] ?: 0
            if (count >= cap) continue
            perArtist[key] = count + 1
            chosen += candidate
            chosenIds += candidate.evidence.album.id
        }
        if (chosen.size < target) {
            sorted.asSequence()
                .filter { it.evidence.album.id !in chosenIds && it.evidence.hasTasteSignal }
                .take(target - chosen.size)
                .forEach { chosen += it }
        }
        return chosen.sortedWith(
            compareByDescending<Scored> { it.rawScore }.thenBy { it.evidence.album.title.lowercase() },
        )
    }

    private fun artistKey(album: CatalogAlbum): String =
        album.artists.firstOrNull()?.let { it.id ?: it.name.lowercase() } ?: album.id

    private fun rankPoints(rank: Int, top10: Int, top25: Int, top50: Int, rest: Int): Int = when {
        rank <= 10 -> top10
        rank <= 25 -> top25
        rank <= 50 -> top50
        else -> rest
    }

    private const val PICK_POINTS = 100
    private const val PICKED_ARTIST_POINTS = 38
    private const val IMPLIED_ARTIST_POINTS = 26
    private const val SIMILAR_POINTS_PER_SEED = 12
    private const val CLASSIC_POINTS = 8
    private const val CLASSIC_AGE_YEARS = 3
    private const val NEW_RELEASE_PENALTY = 8
    private const val ERA_POINTS = 6
    private const val ERA_WINDOW_YEARS = 6
    private const val COLLECTION_POINTS = 7
    private const val NO_TASTE_PENALTY = 10
    private const val PICKED_ARTIST_CAP = 12
    private const val IMPLIED_ARTIST_CAP = 8
    private const val OTHER_ARTIST_CAP = 4
}

/** Recognizes collections/compilations by title or by a "various artists" credit. */
object CollectionTitles {
    private val TITLE_MARKERS = listOf(
        "collection", "best of", "the best", "greatest", "hits", "anthology", "classics", "gold",
        "compilation", "those were the days", "vol.", "vol ", "volume", "the very best",
        "אוסף", "המיטב", "להיטים", "מחרוזת", "נוסטלגיה", "קלאסיק",
    )
    private val VARIOUS_ARTISTS = listOf("various artists", "various", "אמנים שונים", "זמרים שונים")

    fun looksLikeCollection(album: CatalogAlbum): Boolean {
        val title = album.title.lowercase()
        if (TITLE_MARKERS.any(title::contains)) return true
        return album.artists.any { artist -> artist.name.trim().lowercase() in VARIOUS_ARTISTS }
    }
}
