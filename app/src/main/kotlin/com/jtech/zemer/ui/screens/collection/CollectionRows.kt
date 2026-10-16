package com.jtech.zemer.ui.screens.collection

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jtech.zemer.R
import com.jtech.zemer.constants.ListThumbnailSize
import com.jtech.zemer.constants.ThumbnailCornerRadius
import com.jtech.zemer.recommendation.AlbumRecommendation
import com.jtech.zemer.recommendation.GatherStage
import com.jtech.zemer.recommendation.PickOption
import com.jtech.zemer.recommendation.RecommendationReason
import com.jtech.zemer.ui.component.ItemThumbnail
import com.jtech.zemer.ui.component.ListItem
import com.jtech.zemer.utils.joinByBullet

/** A pickable singer/album/song row: tap anywhere (or the checkbox) to toggle. */
@Composable
fun PickOptionRow(option: PickOption, picked: Boolean, onToggle: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        title = option.title,
        subtitle = option.subtitle,
        thumbnailContent = {
            ItemThumbnail(
                thumbnailUrl = option.thumbnail,
                isActive = false,
                isPlaying = false,
                shape = if (option is PickOption.Artist) CircleShape else RoundedCornerShape(ThumbnailCornerRadius),
                modifier = Modifier.size(ListThumbnailSize),
            )
        },
        trailingContent = { Checkbox(checked = picked, onCheckedChange = { onToggle() }) },
    )
}

/** The current step's picks as removable chips. */
@Composable
fun PickedChips(picked: List<PickOption>, onRemove: (PickOption) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier = modifier.fillMaxWidth()) {
        items(picked, key = { it.id }) { option ->
            InputChip(
                selected = true,
                onClick = { onRemove(option) },
                label = { Text(option.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = null,
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** One recommended album: cover, title, artist/year/track count, match score, reasons, checkbox. */
@Composable
fun RecommendationRow(recommendation: AlbumRecommendation, checked: Boolean, onToggle: () -> Unit) {
    val album = recommendation.album
    val details = joinByBullet(
        album.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
        album.year?.toString(),
        recommendation.trackCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.n_song, it, it) },
    )
    val reasons = recommendation.reasons.map { stringResource(it.labelRes()) }
    Column(Modifier.clickable(onClick = onToggle)) {
        ListItem(
            title = album.title,
            subtitle = details,
            thumbnailContent = {
                ItemThumbnail(
                    thumbnailUrl = album.thumbnail,
                    isActive = false,
                    isPlaying = false,
                    shape = RoundedCornerShape(ThumbnailCornerRadius),
                    modifier = Modifier.size(ListThumbnailSize),
                )
            },
            trailingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = ReasonIndent, end = 16.dp, bottom = 8.dp),
        ) {
            Text(
                text = joinByBullet(stringResource(R.string.collection_match_percent, recommendation.score), *reasons.toTypedArray()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@StringRes
fun RecommendationReason.labelRes(): Int = when (this) {
    RecommendationReason.YOUR_PICK -> R.string.collection_reason_your_pick
    RecommendationReason.FAVORITE_ARTIST -> R.string.collection_reason_favorite_artist
    RecommendationReason.ARTIST_FROM_YOUR_PICKS -> R.string.collection_reason_artist_from_picks
    RecommendationReason.SIMILAR_TO_YOUR_PICKS -> R.string.collection_reason_similar
    RecommendationReason.RELATED_ARTIST -> R.string.collection_reason_related_artist
    RecommendationReason.YOU_LISTEN_TO_IT -> R.string.collection_reason_history
    RecommendationReason.POPULAR_ON_ZEMER -> R.string.collection_reason_popular
    RecommendationReason.CHART -> R.string.collection_reason_chart
    RecommendationReason.CLASSIC -> R.string.collection_reason_classic
    RecommendationReason.MATCHES_YOUR_ERA -> R.string.collection_reason_era
    RecommendationReason.COLLECTION -> R.string.collection_reason_collection
}

@StringRes
fun GatherStage.labelRes(): Int = when (this) {
    GatherStage.YOUR_ARTISTS -> R.string.collection_stage_your_artists
    GatherStage.SIMILAR_MUSIC -> R.string.collection_stage_similar
    GatherStage.RELATED_ARTISTS -> R.string.collection_stage_related
    GatherStage.POPULAR -> R.string.collection_stage_popular
    GatherStage.CHECKING_ALBUMS -> R.string.collection_stage_checking
    GatherStage.RANKING -> R.string.collection_stage_ranking
}

private val ReasonIndent = 76.dp
