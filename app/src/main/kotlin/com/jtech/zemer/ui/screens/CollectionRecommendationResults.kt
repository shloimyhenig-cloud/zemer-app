package com.jtech.zemer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jtech.zemer.recommendation.RankedAlbumRecommendation

/** Review step shown after ranking. Album metadata/art is supplied by the catalog adapter. */
data class CollectionAlbumResult(
    val albumId: String,
    val title: String,
    val artist: String,
    val score: Int,
    val reasons: List<String>,
)

fun RankedAlbumRecommendation.toCollectionResult(
    title: String,
    artist: String,
): CollectionAlbumResult = CollectionAlbumResult(
    albumId = candidate.albumId,
    title = title,
    artist = artist,
    score = score,
    reasons = reasons,
)

@Composable
fun CollectionRecommendationResults(
    albums: List<CollectionAlbumResult>,
    onOpenAlbum: (String) -> Unit,
    onDownloadSelected: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(albums) {
        mutableStateOf(albums.map { it.albumId }.toSet())
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Recommended full albums",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text("${selected.size} of ${albums.size} selected")

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(albums, key = { it.albumId }) { album ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAlbum(album.albumId) },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = album.albumId in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + album.albumId else selected - album.albumId
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(album.title, fontWeight = FontWeight.Bold)
                            Text(album.artist)
                            if (album.reasons.isNotEmpty()) {
                                Text(
                                    album.reasons.joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text("${album.score} pts")
                    }
                }
            }
        }

        Button(
            onClick = { onDownloadSelected(selected) },
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Download ${selected.size} Selected Albums")
        }
    }
}
