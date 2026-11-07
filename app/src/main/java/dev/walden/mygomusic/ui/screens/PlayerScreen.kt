package dev.walden.mygomusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.walden.mygomusic.ui.Load
import dev.walden.mygomusic.ui.MyGoState
import dev.walden.mygomusic.ui.components.CoverImage
import dev.walden.mygomusic.ui.components.EmptyState
import dev.walden.mygomusic.ui.readyOrNull
import dev.walden.mygomusic.ui.theme.LocalMyGoExtras
import dev.walden.mygomusic.util.formatDuration

/**
 * Full-screen now playing: artwork-driven backdrop, artwork or lyrics in the
 * middle, transport at the bottom — the phone reading of MyGo's stage view.
 */
@Composable
fun PlayerScreen(state: MyGoState) {
    val track = state.currentTrack ?: return
    val extras = LocalMyGoExtras.current
    val tab = state.playerTab
    LaunchedEffect(tab) { if (tab == 1) state.loadLyrics() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (!track.album.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = track.album.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.35f,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                                    MaterialTheme.colorScheme.background.copy(alpha = 0.82f),
                                    MaterialTheme.colorScheme.background,
                                )
                            )
                        ),
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                // top bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { state.closePlayer() }) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "收起",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Text(
                        "正在播放",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { state.openQueue() }) {
                        Icon(
                            Icons.Rounded.QueueMusic,
                            contentDescription = "播放队列",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                PlayerTabs(state, tab)

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (tab == 0) {
                        CoverPage(state)
                    } else {
                        LyricsView(state)
                    }
                }

                SeekBar(state)
                Transport(state)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PlayerTabs(state: MyGoState, tab: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        listOf("封面", "歌词").forEachIndexed { index, label ->
            val selected = tab == index
            Surface(
                onClick = { state.selectPlayerTab(index) },
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun CoverPage(state: MyGoState) {
    val track = state.currentTrack ?: return
    Column(
        Modifier
            .fillMaxSize()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CoverImage(
            track.album.imageUrl,
            Modifier
                .fillMaxWidth(0.86f)
                .aspectRatio(1f),
            shape = RoundedCornerShape(22.dp),
            iconSize = 44.dp,
        )
        Spacer(Modifier.height(26.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.name.ifBlank { "未知歌曲" },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    track.artistNames().ifBlank { "未知歌手" } +
                        if (track.album.name.isNotBlank()) " · ${track.album.name}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { state.toggleLike(track) }) {
                Icon(
                    if (state.currentLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (state.currentLiked) "取消喜欢" else "喜欢",
                    tint = if (state.currentLiked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeekBar(state: MyGoState) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val position = if (dragging) dragValue else state.positionMs.toFloat()
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = position.coerceIn(0f, duration.toFloat()),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                state.seekTo(dragValue.toLong())
                dragging = false
            },
            valueRange = 0f..duration.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = LocalMyGoExtras.current.hairline,
            ),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                formatDuration(if (dragging) dragValue.toLong() else state.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Transport(state: MyGoState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(
            onClick = {
                if (state.queue.isNotEmpty()) state.playQueue(state.queue.shuffled(), 0)
            },
        ) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = "随机播放",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { state.previous() }, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = "上一首",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(36.dp),
            )
        }
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = { state.togglePlay() }) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "暂停" else "播放",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        IconButton(onClick = { state.next() }, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = "下一首",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = { state.openQueue() }) {
            Icon(
                Icons.Rounded.QueueMusic,
                contentDescription = "队列",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LyricsView(state: MyGoState) {
    val lyrics = state.lyrics
    when (lyrics) {
        is Load.Ready -> {
            val lines = lyrics.value.lines
            val plain = if (lines.isEmpty()) {
                dev.walden.mygomusic.util.plainLyricLines(lyrics.value)
            } else emptyList()

            if (lines.isNotEmpty()) {
                val current = lines.indexOfLast { it.start <= state.positionMs }
                val listState = rememberLazyListState()
                LaunchedEffect(current) {
                    if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    itemsIndexed(lines) { index, line ->
                        val active = index == current
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                line.displayText(),
                                fontSize = if (active) 19.sp else 15.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center,
                            )
                            if (!line.translation.isNullOrBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    line.translation,
                                    fontSize = if (active) 14.sp else 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            } else if (plain.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(plain) { _, line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                EmptyState("这首歌暂无歌词")
            }
        }

        is Load.Failed -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                lyrics.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = {
                state.retryLyrics()
            }) { Text("重试") }
        }

        else -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "正在加载歌词…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
