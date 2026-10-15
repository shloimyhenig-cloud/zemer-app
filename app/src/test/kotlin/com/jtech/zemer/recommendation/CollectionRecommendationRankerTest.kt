package com.jtech.zemer.recommendation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionRecommendationRankerTest {
    @Test
    fun seedAlbumsAreExcluded() {
        val seed = CollectionSeed(albumIds = setOf("seed"))
        val ranked = CollectionRecommendationRanker.rank(
            seeds = seed,
            candidates = listOf(
                candidate("seed"),
                candidate("other", popularityRank = 1),
            ),
        )

        assertEquals(listOf("other"), ranked.map { it.candidate.albumId })
    }

    @Test
    fun favoriteArtistOutranksPopularityOnly() {
        val seed = CollectionSeed(artistIds = setOf("artist-a"))
        val ranked = CollectionRecommendationRanker.rank(
            seeds = seed,
            candidates = listOf(
                candidate("popular", artistIds = setOf("artist-b"), popularityRank = 1),
                candidate("favorite-artist", artistIds = setOf("artist-a"), popularityRank = 80),
            ),
        )

        assertEquals("favorite-artist", ranked.first().candidate.albumId)
        assertTrue("favorite artist" in ranked.first().reasons)
    }

    @Test
    fun radioRelationsBoostAlbumAndSongMatches() {
        val seed = CollectionSeed(
            albumIds = setOf("album-seed"),
            songIds = setOf("song-seed"),
        )
        val related = candidate(
            id = "related",
            relatedAlbums = setOf("album-seed"),
            relatedSongs = setOf("song-seed"),
        )
        val unrelated = candidate("unrelated", popularityRank = 1)

        val ranked = CollectionRecommendationRanker.rank(seed, listOf(unrelated, related))

        assertEquals("related", ranked.first().candidate.albumId)
        assertTrue("similar to a favorite album" in ranked.first().reasons)
        assertTrue("related to favorite songs" in ranked.first().reasons)
    }

    @Test
    fun classicAndCompilationBonusesAreApplied() {
        val seed = CollectionSeed()
        val plain = candidate("plain")
        val classicCollection = candidate(
            id = "classic-collection",
            year = 2005,
            isCompilation = true,
        )

        val ranked = CollectionRecommendationRanker.rank(seed, listOf(plain, classicCollection))

        assertEquals("classic-collection", ranked.first().candidate.albumId)
        assertTrue("established catalog" in ranked.first().reasons)
        assertTrue("collection album" in ranked.first().reasons)
    }

    @Test
    fun downloadedAlbumsAreExcludedAndOrderIsDeterministic() {
        val seed = CollectionSeed()
        val candidates = listOf(
            candidate("b", popularityRank = 20),
            candidate("downloaded", popularityRank = 1, isDownloaded = true),
            candidate("a", popularityRank = 20),
        )

        val ranked = CollectionRecommendationRanker.rank(seed, candidates)

        assertFalse(ranked.any { it.candidate.albumId == "downloaded" })
        assertEquals(listOf("a", "b"), ranked.map { it.candidate.albumId })
    }

    private fun candidate(
        id: String,
        artistIds: Set<String> = emptySet(),
        relatedAlbums: Set<String> = emptySet(),
        relatedSongs: Set<String> = emptySet(),
        popularityRank: Int? = null,
        year: Int? = null,
        isCompilation: Boolean = false,
        isDownloaded: Boolean = false,
    ) = AlbumRecommendationCandidate(
        albumId = id,
        artistIds = artistIds,
        relatedToAlbumIds = relatedAlbums,
        relatedToSongIds = relatedSongs,
        popularityRank = popularityRank,
        releaseYear = year,
        isCompilation = isCompilation,
        isDownloaded = isDownloaded,
    )
}
