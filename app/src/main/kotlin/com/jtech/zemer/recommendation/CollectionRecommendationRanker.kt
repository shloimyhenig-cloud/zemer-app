package com.jtech.zemer.recommendation

/**
 * Pure, deterministic album ranker for the Build My Collection feature.
 *
 * The UI can feed this candidates from Zemer search/home/artist pages. Keeping the scorer free of
 * Android dependencies makes it easy to test and tune without changing playback or download code.
 */
data class CollectionSeed(
    val artistIds: Set<String> = emptySet(),
    val albumIds: Set<String> = emptySet(),
    val songIds: Set<String> = emptySet(),
)

data class AlbumRecommendationCandidate(
    val albumId: String,
    val artistIds: Set<String>,
    val relatedToAlbumIds: Set<String> = emptySet(),
    val relatedToSongIds: Set<String> = emptySet(),
    val popularityRank: Int? = null,
    val releaseYear: Int? = null,
    val isCompilation: Boolean = false,
    val isDownloaded: Boolean = false,
)

data class RankedAlbumRecommendation(
    val candidate: AlbumRecommendationCandidate,
    val score: Int,
    val reasons: List<String>,
)

object CollectionRecommendationRanker {
    fun rank(
        seeds: CollectionSeed,
        candidates: List<AlbumRecommendationCandidate>,
        limit: Int = 100,
    ): List<RankedAlbumRecommendation> = candidates
        .asSequence()
        .filterNot { it.albumId in seeds.albumIds || it.isDownloaded }
        .distinctBy { it.albumId }
        .map { candidate -> score(seeds, candidate) }
        .sortedWith(
            compareByDescending<RankedAlbumRecommendation> { it.score }
                .thenBy { it.candidate.popularityRank ?: Int.MAX_VALUE }
                .thenBy { it.candidate.albumId },
        )
        .take(limit.coerceAtLeast(0))
        .toList()

    private fun score(
        seeds: CollectionSeed,
        candidate: AlbumRecommendationCandidate,
    ): RankedAlbumRecommendation {
        var score = 0
        val reasons = mutableListOf<String>()

        val artistMatches = candidate.artistIds.intersect(seeds.artistIds).size
        if (artistMatches > 0) {
            score += 50 + ((artistMatches - 1) * 10)
            reasons += "favorite artist"
        }

        val albumMatches = candidate.relatedToAlbumIds.intersect(seeds.albumIds).size
        if (albumMatches > 0) {
            score += 40 + ((albumMatches - 1) * 8)
            reasons += "similar to a favorite album"
        }

        val songMatches = candidate.relatedToSongIds.intersect(seeds.songIds).size
        if (songMatches > 0) {
            score += 25 + ((songMatches - 1) * 5)
            reasons += "related to favorite songs"
        }

        candidate.popularityRank?.let { rank ->
            val popularityPoints = when {
                rank <= 10 -> 24
                rank <= 25 -> 18
                rank <= 50 -> 12
                rank <= 100 -> 6
                else -> 0
            }
            if (popularityPoints > 0) {
                score += popularityPoints
                reasons += "popular on Zemer"
            }
        }

        // Small preference for established catalog material, matching the collection-builder goal of
        // discovering complete classic CDs rather than only the newest releases.
        candidate.releaseYear?.let { year ->
            if (year in 1990..2019) {
                score += 8
                reasons += "established catalog"
            }
        }

        if (candidate.isCompilation) {
            score += 6
            reasons += "collection album"
        }

        return RankedAlbumRecommendation(candidate, score, reasons.distinct())
    }
}
