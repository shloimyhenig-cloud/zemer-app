package com.jtech.zemer.recommendation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionRecommenderTest {

    // --- A small fake corner of the catalog -------------------------------------------------------

    private val fried = CatalogArtist("fried", "Avraham Fried")
    private val shwekey = CatalogArtist("shwekey", "Yaakov Shwekey")
    private val kempe = CatalogArtist("kempe", "Naftali Kempeh")
    private val werdyger = CatalogArtist("werdyger", "Mordechai Werdyger")
    private val newSinger = CatalogArtist("newbie", "New Singer")
    private val various = CatalogArtist(null, "Various Artists")

    private fun album(id: String, artist: CatalogArtist, year: Int?, title: String = "Album $id", single: Boolean = false) =
        CatalogAlbum(id = id, title = title, artists = listOf(artist), year = year, thumbnail = "t/$id", isSingle = single)

    private val friedAlbums = (1..20).map { album("fried$it", fried, 1985 + it) }
    private val shwekeyAlbums = (1..10).map { album("shw$it", shwekey, 1998 + it) }
    private val kempeAlbums = (1..6).map { album("kem$it", kempe, 2000 + it) }
    private val werdygerAlbums = (1..8).map { album("mw$it", werdyger, 1975 + it) }
    private val newAlbums = (1..5).map { album("new$it", newSinger, 2026) }
    private val ttwd = album("ttwd", various, 2005, title = "Those Were the Days Vol. 2")
    private val friedSingle = album("friedSingle", fried, 2024, title = "A Single", single = true)

    private inner class FakeCatalog : CollectionCatalog {
        val opened = mutableListOf<String>()
        var failArtists = setOf<String>()
        val downloaded = mutableSetOf<String>()
        val shortAlbums = mutableSetOf<String>()

        override suspend fun artistDiscography(artistId: String): ArtistDiscography? {
            if (artistId in failArtists) error("network down")
            return when (artistId) {
                "fried" -> ArtistDiscography(fried, friedAlbums, listOf(friedSingle), emptyList())
                "shwekey" -> ArtistDiscography(shwekey, shwekeyAlbums, emptyList(), emptyList())
                "kempe" -> ArtistDiscography(kempe, kempeAlbums, emptyList(), emptyList())
                "werdyger" -> ArtistDiscography(werdyger, werdygerAlbums, emptyList(), emptyList())
                "newbie" -> ArtistDiscography(newSinger, newAlbums, emptyList(), emptyList())
                else -> null
            }
        }

        override suspend fun relatedSongs(kind: String, seedId: String): List<CatalogSong> =
            // Every seed's radio leans on Werdyger and the TTWD compilation.
            listOf(
                CatalogSong("r1-$seedId", "Related 1", listOf(werdyger), "mw3", "Album mw3"),
                CatalogSong("r2-$seedId", "Related 2", listOf(werdyger), "mw5", "Album mw5"),
                CatalogSong("r3-$seedId", "Related 3", listOf(various), "ttwd", ""),
            )

        override suspend fun popularAlbums(): List<CatalogAlbum> = newAlbums + listOf(shwekeyAlbums[0])

        override suspend fun chartAlbums(): List<RankedAlbum> = listOf(RankedAlbum(newAlbums[0], 1))

        override suspend fun albumDetails(album: CatalogAlbum): AlbumDetails? {
            opened += album.id
            val full = (friedAlbums + shwekeyAlbums + kempeAlbums + werdygerAlbums + newAlbums + ttwd)
                .firstOrNull { it.id == album.id } ?: return null
            return AlbumDetails(full, trackCount = if (album.id in shortAlbums) 2 else 11)
        }

        override suspend fun listeningHistory() = ListeningHistory(artistIds = listOf("kempe"), albumIds = listOf("kem2"))

        override suspend fun fullyDownloadedAlbumIds(): Set<String> = downloaded
    }

    private val seeds = CollectionSeeds(
        artists = listOf(fried, shwekey),
        albums = listOf(friedAlbums[4], kempeAlbums[0]),
        songs = listOf(CatalogSong("song1", "Didan Notzach", listOf(fried), "fried2", "Album fried2")),
    )

    private fun run(
        catalog: FakeCatalog = FakeCatalog(),
        preferences: CollectionPreferences = CollectionPreferences(targetCount = 60),
        seeds: CollectionSeeds = this.seeds,
    ): List<AlbumRecommendation> = runBlocking {
        CollectionCandidateGatherer(catalog).recommend(seeds, preferences, currentYear = 2026)
    }

    // --- Behavior -------------------------------------------------------------------------------------

    @Test
    fun `every CD of a picked singer is listed under that singer, newest first, then the discoveries`() {
        val result = run()
        val ids = result.map { it.album.id }
        val friedIds = friedAlbums.map { it.id }
        val shwekeyIds = shwekeyAlbums.map { it.id }
        // All 20 Fried and all 10 Shwekey albums, uncapped, singers in pick order.
        assertEquals(friedIds.reversed() + shwekeyIds.reversed(), ids.take(30))
        assertTrue(result.take(30).all { it.pickedSinger != null })
        assertEquals(fried, result.first().pickedSinger)
        // The picked album by a non-picked singer still leads the discoveries.
        assertEquals("kem1", result[30].album.id)
        assertTrue(result[30].reasons.contains(RecommendationReason.YOUR_PICK))
        assertTrue(result.drop(30).none { it.pickedSinger != null })
        assertFalse("singles never offered", "friedSingle" in ids)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `favorite artists, related artists, similar albums and compilations all contribute`() {
        val byId = run().associateBy { it.album.id }
        assertTrue(byId.getValue("fried10").reasons.contains(RecommendationReason.FAVORITE_ARTIST))
        assertTrue(byId.getValue("kem3").reasons.contains(RecommendationReason.ARTIST_FROM_YOUR_PICKS))
        assertTrue(byId.getValue("mw3").reasons.contains(RecommendationReason.SIMILAR_TO_YOUR_PICKS))
        assertTrue(byId.getValue("mw7").reasons.contains(RecommendationReason.RELATED_ARTIST))
        val ttwd = byId.getValue("ttwd")
        assertTrue(ttwd.reasons.contains(RecommendationReason.COLLECTION))
        assertEquals("title filled in from the opened album", "Those Were the Days Vol. 2", ttwd.album.title)
        assertTrue(byId.getValue("kem2").reasons.contains(RecommendationReason.YOU_LISTEN_TO_IT))
    }

    @Test
    fun `classic albums beat brand-new popular releases with no taste match`() {
        val result = run()
        val position = result.withIndex().associate { it.value.album.id to it.index }
        val newest = position["new1"]
        if (newest != null) {
            // Present only as filler, below every album that matches the picks.
            assertTrue(position.getValue("mw3") < newest)
            assertTrue(position.getValue("fried10") < newest)
        }
        val classic = result.first { it.album.id == "mw3" }
        assertTrue(classic.reasons.contains(RecommendationReason.CLASSIC))
    }

    @Test
    fun `one prolific favorite cannot crowd out everyone else`() {
        // 40 Fried albums outscore everything else, but there are plenty of other candidates.
        val manyFried = (1..40).map { album("f$it", fried, 1990) }
        val others = (1..80).map { album("o$it", CatalogArtist("artist$it", "Artist $it"), 1995) }
        val scored = (manyFried.map { AlbumEvidence(it).apply { inPickedArtistDiscography = true } } +
            others.map { AlbumEvidence(it).apply { relatedArtistStrength = 1 } })
            .map { CollectionRecommender.score(it, CollectionPreferences(), null, 2026) }
            .sortedByDescending { it.rawScore }
        val chosen = CollectionRecommender.selectDiverse(scored, CollectionSeeds(artists = listOf(fried)), 60)
        assertEquals(60, chosen.size)
        assertEquals(12, chosen.count { it.evidence.album.artists.first() == fried })

        // With too few other candidates the list is still filled to target from the favorite.
        val short = CollectionRecommender.selectDiverse(scored.take(45), CollectionSeeds(artists = listOf(fried)), 60)
        assertEquals(45, short.size)
    }

    @Test
    fun `already downloaded albums and short releases are skipped`() {
        val catalog = FakeCatalog().apply {
            downloaded += "shw2"
            shortAlbums += "mw3"
        }
        val ids = run(catalog).map { it.album.id }
        assertFalse("shw2" in ids)
        assertFalse("mw3" in ids)
    }

    @Test
    fun `a failing source degrades the result instead of failing it`() {
        val catalog = FakeCatalog().apply { failArtists = setOf("fried", "shwekey") }
        val result = run(catalog)
        assertTrue(result.isNotEmpty())
        assertTrue(result.any { it.album.id == "fried5" }) // the pick survives
        assertTrue(result.none { it.pickedSinger != null }) // no discography could be read
    }

    @Test
    fun `target count is respected and clamped to the 60-100 range`() {
        assertTrue(run(preferences = CollectionPreferences(targetCount = 5)).count { it.pickedSinger == null } <= 60)
        assertEquals(60, CollectionPreferences(targetCount = 5).clampedTarget)
        assertEquals(100, CollectionPreferences(targetCount = 500).clampedTarget)
    }

    @Test
    fun `only the likely winners are opened for verification`() {
        val catalog = FakeCatalog()
        run(catalog, CollectionPreferences(targetCount = 60))
        assertTrue(catalog.opened.size <= 96 + 30) // discovery pool + every picked-singer CD
    }

    @Test
    fun `turning classics off removes the new-release penalty`() {
        val e = AlbumEvidence(newAlbums[0]).apply { popularRank = 1 }
        val withClassics = CollectionRecommender.score(e, CollectionPreferences(preferClassics = true), null, 2026)
        val without = CollectionRecommender.score(e, CollectionPreferences(preferClassics = false), null, 2026)
        assertTrue(without.rawScore > withClassics.rawScore)
    }

    @Test
    fun `era center is the median year of the picked albums`() {
        val s = CollectionSeeds(albums = listOf(album("a", fried, 1990), album("b", fried, 1995), album("c", fried, 2020)))
        assertEquals(1995, CollectionRecommender.seedEraCenter(s))
        assertEquals(null, CollectionRecommender.seedEraCenter(CollectionSeeds(artists = listOf(fried))))
    }

    @Test
    fun `collection titles are recognized in English and Hebrew`() {
        assertTrue(CollectionTitles.looksLikeCollection(album("x", fried, null, "The Best of Avraham Fried")))
        assertTrue(CollectionTitles.looksLikeCollection(album("y", fried, null, "אוסף שירי שבת")))
        assertTrue(CollectionTitles.looksLikeCollection(album("z", various, null, "Shabbos Songs")))
        assertFalse(CollectionTitles.looksLikeCollection(album("w", fried, null, "Bring Moshiach")))
    }
}
