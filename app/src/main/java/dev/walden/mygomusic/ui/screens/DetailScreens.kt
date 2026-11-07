package dev.walden.mygomusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.walden.mygomusic.Album
import dev.walden.mygomusic.Playlist
import dev.walden.mygomusic.Track
import dev.walden.mygomusic.ui.Load
import dev.walden.mygomusic.ui.MyGoState
import dev.walden.mygomusic.ui.Route
import dev.walden.mygomusic.ui.components.ActionPill
import dev.walden.mygomusic.ui.components.CoverImage
import dev.walden.mygomusic.ui.components.EmptyState
import dev.walden.mygomusic.ui.components.ErrorState
import dev.walden.mygomusic.ui.components.NavigateRow
import dev.walden.mygomusic.ui.components.PlayFab
import dev.walden.mygomusic.ui.components.SkeletonList
import dev.walden.mygomusic.ui.components.TrackRow
import dev.walden.mygomusic.ui.readyOrNull
import dev.walden.mygomusic.util.formatDuration

/** Dispatches the page on top of the stack. */
@Composable
fun RouteScreen(state: MyGoState, route: Route) {
    when (route) {
        is Route.PlaylistDetail -> PlaylistDetailScreen(state, route)
        is Route.AlbumDetail -> AlbumDetailScreen(state, route)
        is Route.ArtistDetail -> ArtistDetailScreen(state, route)
        is Route.ShowDetail -> ShowDetailScreen(state, route)
        is Route.Page -> BrowsePageScreen(state, route.path)
        Route.Liked -> LikedScreen(state)
        Route.Recent -> RecentScreen(state)
        Route.Playlists -> PlaylistsScreen(state)
        Route.Shows -> ShowsScreen(state)
    }
}

// ------------------------------------------------------------------- hero

@Composable
private fun DetailHero(
    imageUrl: String?,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(16.dp),
    title: String,
    subtitle: String?,
    meta: String?,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(230.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.25f),
                                MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    ),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                CoverImage(
                    imageUrl,
                    Modifier.size(118.dp),
                    shape = shape,
                    iconSize = 30.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!meta.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = actions,
            )
        }
    }
}

// --------------------------------------------------------------- playlist

@Composable
fun PlaylistDetailScreen(state: MyGoState, route: Route.PlaylistDetail) {
    val load = state.playlistDetails[route.id]
    val playlist = load.readyOrNull()
    LaunchedEffect(route.id) { state.loadPlaylist(route.id) }
    when {
        playlist != null -> TrackPageScaffold(
            state = state,
            title = playlist.name,
            subtitle = playlist.owner,
            meta = listOfNotNull(
                "${playlist.tracks.size} 首",
                playlist.description?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            imageUrl = playlist.imageUrl,
            tracks = playlist.tracks,
            onPlayAll = { state.playQueue(playlist.tracks, 0) },
            onShuffle = {
                val shuffled = playlist.tracks.shuffled()
                state.playQueue(shuffled, 0)
            },
        )

        load is Load.Failed -> ErrorState(load.message, onRetry = { state.playlistDetails.remove(route.id); state.loadPlaylist(route.id) })
        else -> SkeletonList(10)
    }
}

// ------------------------------------------------------------------ album

@Composable
fun AlbumDetailScreen(state: MyGoState, route: Route.AlbumDetail) {
    val load = state.albumDetails[route.id]
    val page = load.readyOrNull()
    LaunchedEffect(route.id) { state.loadAlbum(route.id) }
    when {
        page != null -> TrackPageScaffold(
            state = state,
            title = page.album.name,
            subtitle = page.album.artists.joinToString(", ") { it.name },
            meta = listOfNotNull(
                page.album.released?.takeIf { it.isNotBlank() },
                "${page.tracks.size} 首",
            ).joinToString(" · "),
            imageUrl = page.album.imageUrl,
            tracks = page.tracks,
            onPlayAll = { state.playQueue(page.tracks, 0) },
            onShuffle = { state.playQueue(page.tracks.shuffled(), 0) },
            leadingActions = {
                if (state.can("saveAlbum") && page.album.id.isNotBlank()) {
                    ActionPill(
                        label = if (page.album.saved) "已收藏" else "收藏",
                        icon = if (page.album.saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        onClick = { state.saveAlbum(page.album) },
                    )
                }
            },
        )

        load is Load.Failed -> ErrorState(load.message, onRetry = { state.albumDetails.remove(route.id); state.loadAlbum(route.id) })
        else -> SkeletonList(10)
    }
}

// ----------------------------------------------------------------- artist

@Composable
fun ArtistDetailScreen(state: MyGoState, route: Route.ArtistDetail) {
    val load = state.artistDetails[route.id]
    val page = load.readyOrNull()
    LaunchedEffect(route.id) { state.loadArtist(route.id) }
    when {
        page != null -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                DetailHero(
                    imageUrl = page.artist.imageUrl,
                    shape = CircleShape,
                    title = page.artist.name,
                    subtitle = null,
                    meta = "${page.tracks.size} 首热门歌曲 · ${page.albums.size} 张专辑",
                ) {
                    PlayFab(onClick = { state.playQueue(page.tracks, 0) })
                    TextButton(onClick = { state.playQueue(page.tracks.shuffled(), 0) }) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("随机播放")
                    }
                }
            }
            if (page.tracks.isNotEmpty()) {
                item { SectionLabel("热门歌曲") }
                itemsIndexed(page.tracks, key = { index, track -> "hot-${track.id}-$index" }) { index, track ->
                    TrackRow(
                        track = track,
                        state = state,
                        index = index,
                        isCurrent = state.currentTrack?.id == track.id,
                        onPlay = { state.playQueue(page.tracks, index) },
                        onOpenAlbum = { state.openAlbum(it) },
                        onOpenArtist = { state.openArtist(it) },
                    )
                }
            }
            if (page.albums.isNotEmpty()) {
                item { SectionLabel("专辑") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(page.albums, key = { "ab-${it.id}" }) { album ->
                            dev.walden.mygomusic.ui.components.MediaCard(
                                title = album.name,
                                subtitle = album.released,
                                imageUrl = album.imageUrl,
                                onClick = { state.openAlbum(album) },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }

        load is Load.Failed -> ErrorState(load.message, onRetry = { state.artistDetails.remove(route.id); state.loadArtist(route.id) })
        else -> SkeletonList(10)
    }
}

// ------------------------------------------------------------------- show

@Composable
fun ShowDetailScreen(state: MyGoState, route: Route.ShowDetail) {
    val load = state.showDetails[route.id]
    val page = load.readyOrNull()
    LaunchedEffect(route.id) { state.loadShow(route.id, reset = true) }
    when {
        page != null -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                DetailHero(
                    imageUrl = page.show.imageUrl,
                    title = page.show.name,
                    subtitle = page.show.description,
                    meta = page.show.publisher,
                ) {
                    PlayFab(onClick = { state.playQueue(page.episodes, 0) })
                    if (state.can("subscribeShow")) {
                        ActionPill(
                            label = if (page.show.subscribed) "已订阅" else "订阅",
                            icon = Icons.Rounded.Favorite,
                            onClick = { state.subscribeShow(page.show) },
                        )
                    }
                }
            }
            itemsIndexed(page.episodes, key = { index, track -> "ep-${track.id}-$index" }) { index, track ->
                TrackRow(
                    track = track,
                    state = state,
                    index = index,
                    showCover = true,
                    isCurrent = state.currentTrack?.id == track.id,
                    onPlay = { state.playQueue(page.episodes, index) },
                    onOpenAlbum = { state.openAlbum(it) },
                    onOpenArtist = { state.openArtist(it) },
                )
            }
            if (page.more) {
                item {
                    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { state.loadShow(route.id) }) { Text("加载更多") }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }

        load is Load.Failed -> ErrorState(load.message, onRetry = { state.showDetails.remove(route.id); state.loadShow(route.id, reset = true) })
        else -> SkeletonList(10)
    }
}

// ------------------------------------------------------------ simple pages

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun TrackPageScaffold(
    state: MyGoState,
    title: String,
    subtitle: String?,
    meta: String?,
    imageUrl: String?,
    tracks: List<Track>,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    leadingActions: @Composable RowScope.() -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            DetailHero(imageUrl = imageUrl, title = title, subtitle = subtitle, meta = meta) {
                PlayFab(onClick = onPlayAll)
                ActionPill(label = "随机", icon = Icons.Rounded.Shuffle, onClick = onShuffle)
                leadingActions()
            }
        }
        if (tracks.isEmpty()) {
            item { EmptyState("这里还没有歌曲") }
        } else {
            itemsIndexed(tracks, key = { index, track -> "t-${track.id}-$index" }) { index, track ->
                TrackRow(
                    track = track,
                    state = state,
                    index = index,
                    isCurrent = state.currentTrack?.id == track.id,
                    onPlay = { state.playQueue(tracks, index) },
                    onOpenAlbum = { state.openAlbum(it) },
                    onOpenArtist = { state.openArtist(it) },
                )
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
fun PlaylistsScreen(state: MyGoState) {
    val load = state.library
    LaunchedEffect(Unit) { state.loadLibrary() }
    val playlists = load.readyOrNull().orEmpty()
    when {
        load is Load.Loading || load is Load.Idle -> SkeletonList(10)
        load is Load.Failed -> ErrorState(load.message, onRetry = { state.loadLibrary(force = true) })
        playlists.isEmpty() -> EmptyState("还没有歌单", "在插件里创建歌单后会出现在这里")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { playlist ->
                NavigateRow(
                    title = playlist.name,
                    subtitle = "${playlist.trackCount} 首" + (playlist.owner?.let { " · $it" } ?: ""),
                    imageUrl = playlist.imageUrl,
                    onClick = { state.openPlaylist(playlist) },
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
fun LikedScreen(state: MyGoState) {
    val load = state.liked
    LaunchedEffect(Unit) { if (load !is Load.Ready) state.loadLiked(reset = true) }
    val page = load.readyOrNull()
    when {
        page == null && (load is Load.Loading || load is Load.Idle) -> SkeletonList(10)
        load is Load.Failed -> ErrorState(load.message, onRetry = { state.loadLiked(reset = true) })
        page != null -> LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("喜欢的音乐", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${page.tracks.size} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PlayFab(onClick = { state.playQueue(page.tracks, 0) })
                }
            }
            itemsIndexed(page.tracks, key = { index, track -> "l-${track.id}-$index" }) { index, track ->
                TrackRow(
                    track = track,
                    state = state,
                    index = index,
                    isCurrent = state.currentTrack?.id == track.id,
                    onPlay = { state.playQueue(page.tracks, index) },
                    onOpenAlbum = { state.openAlbum(it) },
                    onOpenArtist = { state.openArtist(it) },
                )
            }
            if (page.more) {
                item {
                    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                        if (state.likedLoadingMore) {
                            androidx.compose.material3.CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            TextButton(onClick = { state.loadLiked() }) { Text("加载更多") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
fun RecentScreen(state: MyGoState) {
    val load = state.recent
    LaunchedEffect(Unit) { if (load !is Load.Ready) state.loadRecent() }
    val tracks = load.readyOrNull()
    when {
        tracks == null && (load is Load.Loading || load is Load.Idle) -> SkeletonList(10)
        load is Load.Failed -> ErrorState(load.message, onRetry = { state.loadRecent() })
        tracks != null -> if (tracks.isEmpty()) EmptyState("还没有播放记录")
        else LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("最近播放", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${tracks.size} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PlayFab(onClick = { state.playQueue(tracks, 0) })
                }
            }
            itemsIndexed(tracks, key = { index, track -> "r-${track.id}-$index" }) { index, track ->
                TrackRow(
                    track = track,
                    state = state,
                    index = index,
                    showCover = true,
                    isCurrent = state.currentTrack?.id == track.id,
                    onPlay = { state.playQueue(tracks, index) },
                    onOpenAlbum = { state.openAlbum(it) },
                    onOpenArtist = { state.openArtist(it) },
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
fun ShowsScreen(state: MyGoState) {
    val load = state.showList
    LaunchedEffect(Unit) { if (load !is Load.Ready) state.loadShowList() }
    val shows = load.readyOrNull()
    when {
        shows == null && (load is Load.Loading || load is Load.Idle) -> SkeletonList(10)
        load is Load.Failed -> ErrorState(load.message, onRetry = { state.loadShowList() })
        shows != null -> if (shows.isEmpty()) EmptyState("还没有订阅播客")
        else LazyColumn(Modifier.fillMaxSize()) {
            items(shows, key = { it.id }) { show ->
                NavigateRow(
                    title = show.name,
                    subtitle = show.publisher ?: "${show.episodeCount} 集",
                    imageUrl = show.imageUrl,
                    onClick = { state.openShow(show) },
                )
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}
