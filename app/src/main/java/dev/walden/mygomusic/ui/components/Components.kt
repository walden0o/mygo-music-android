package dev.walden.mygomusic.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.walden.mygomusic.Album
import dev.walden.mygomusic.Artist
import dev.walden.mygomusic.Track
import dev.walden.mygomusic.ui.MyGoState
import dev.walden.mygomusic.ui.theme.LocalMyGoExtras
import dev.walden.mygomusic.util.formatDuration

// --------------------------------------------------------------- foundation

/** Artwork with a stable placeholder, so lists never jump while images load. */
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    contentDescription: String? = null,
    iconSize: Dp = 20.dp,
) {
    val extras = LocalMyGoExtras.current
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = extras.faint,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** A section title with the optional "more" affordance MyGo puts on the right. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onMore != null) {
            TextButton(onClick = onMore) {
                Text("更多", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.MusicNote,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = LocalMyGoExtras.current.faint,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
fun ErrorState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** Shimmering placeholder used for section rows while the plugin thinks. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier, cards: Int = 4) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(start = 20.dp, top = 22.dp)
                .width(120.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(base.copy(alpha = alpha)),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(cards) {
                Column {
                    Box(
                        Modifier
                            .size(width = 118.dp, height = 118.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(base.copy(alpha = alpha)),
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .width(90.dp)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(base.copy(alpha = alpha)),
                    )
                }
            }
        }
    }
}

@Composable
fun SkeletonList(rows: Int = 8) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val transition = rememberInfiniteTransition(label = "skeleton-list")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Column(Modifier.fillMaxWidth()) {
        repeat(rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(base.copy(alpha = alpha)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.42f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(base.copy(alpha = alpha)),
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.26f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(base.copy(alpha = alpha)),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------- tracks

/** Equalizer bars marking the row that is playing right now. */
@Composable
fun PlayingBars(color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "bars")
    val heights = listOf(0.35f, 0.9f, 0.55f)
    Row(
        Modifier.size(width = 14.dp, height = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heights.forEachIndexed { index, target ->
            val scale by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = target,
                animationSpec = infiniteRepeatable(
                    tween(420 + index * 130, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            Box(
                Modifier
                    .width(2.5.dp)
                    .height((14 * scale).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

/**
 * MyGo's track row, rebuilt for touch: optional artwork or index, title over
 * artist, duration, and an overflow menu with like / album / artist actions.
 */
@Composable
fun TrackRow(
    track: Track,
    state: MyGoState,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showCover: Boolean = false,
    isCurrent: Boolean = false,
    onPlay: () -> Unit,
    onOpenAlbum: (Album) -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
) {
    val extras = LocalMyGoExtras.current
    val liked = state.isLiked(track.id)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            showCover -> CoverImage(
                track.album.imageUrl,
                Modifier.size(44.dp),
                shape = RoundedCornerShape(8.dp),
            )

            else -> Box(
                Modifier.width(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent) {
                    PlayingBars()
                } else {
                    Text(
                        text = if (index != null) "${index + 1}" else "•",
                        style = MaterialTheme.typography.labelMedium,
                        color = extras.faint,
                    )
                }
            }
        }
        Spacer(Modifier.width(if (showCover) 12.dp else 4.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.name.ifBlank { "未知歌曲" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artists.isNotEmpty() || track.album.name.isNotBlank()) {
                Text(
                    buildString {
                        if (track.artists.isNotEmpty()) append(track.artistNames())
                        if (track.album.name.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(track.album.name)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (track.duration > 0) {
            Text(
                formatDuration(track.duration),
                style = MaterialTheme.typography.labelSmall,
                color = extras.faint,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多",
                    tint = extras.faint,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (liked) "取消喜欢" else "喜欢") },
                    onClick = {
                        menuOpen = false
                        state.toggleLike(track)
                    },
                )
                if (track.album.id.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text("查看专辑") },
                        onClick = {
                            menuOpen = false
                            onOpenAlbum(track.album)
                        },
                    )
                }
                if (track.artists.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("查看歌手") },
                        onClick = {
                            menuOpen = false
                            onOpenArtist(track.artists.first())
                        },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------- cards

/** Artwork tile used in carousels and grids: album, playlist, show, station. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    width: Dp = 138.dp,
    shape: Shape = RoundedCornerShape(12.dp),
    onClick: () -> Unit,
) {
    Column(
        modifier
            .width(width)
            .clickable(onClick = onClick),
    ) {
        CoverImage(
            imageUrl,
            Modifier
                .fillMaxWidth()
                .height(width),
            shape = shape,
            iconSize = 28.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            title.ifBlank { "未命名" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ArtistCard(
    artist: Artist,
    modifier: Modifier = Modifier,
    width: Dp = 108.dp,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .width(width)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverImage(
            artist.imageUrl,
            Modifier.size(width),
            shape = CircleShape,
            iconSize = 28.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            artist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A list row that leads somewhere: MyGo's `links` sections and library rows. */
@Composable
fun NavigateRow(
    title: String,
    subtitle: String? = null,
    imageUrl: String? = null,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leadingIcon != null -> Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(leadingIcon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            }

            else -> CoverImage(imageUrl, Modifier.size(44.dp), shape = RoundedCornerShape(8.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Compact icon + label button used for header actions. */
@Composable
fun ActionPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Big accent play button used in page headers. */
@Composable
fun PlayFab(onClick: () -> Unit, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.PlayArrow) {
    Box(
        modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = "播放", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
    }
}

@Composable
fun PersonAvatar(url: String?, size: Dp = 32.dp, onClick: (() -> Unit)? = null) {
    val extras = LocalMyGoExtras.current
    val modifier = if (onClick != null) {
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick)
    } else {
        Modifier.size(size).clip(CircleShape)
    }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = extras.faint, modifier = Modifier.size(size * 0.6f))
        }
    }
}

/** Album / show tile with a leading square cover used in dense lists. */
