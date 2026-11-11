package dev.walden.mygomusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import dev.walden.mygomusic.Section
import dev.walden.mygomusic.Track
import dev.walden.mygomusic.ui.Load
import dev.walden.mygomusic.ui.Route
import dev.walden.mygomusic.ui.MyGoState
import dev.walden.mygomusic.ui.components.CoverImage
import dev.walden.mygomusic.ui.components.EmptyState
import dev.walden.mygomusic.ui.components.ErrorState
import dev.walden.mygomusic.ui.components.MediaCard
import dev.walden.mygomusic.ui.components.ArtistCard
import dev.walden.mygomusic.ui.components.NavigateRow
import dev.walden.mygomusic.ui.components.PlayFab
import dev.walden.mygomusic.ui.components.SectionHeader
import dev.walden.mygomusic.ui.components.SkeletonRow
import dev.walden.mygomusic.ui.components.TrackRow
import dev.walden.mygomusic.ui.readyOrNull
import dev.walden.mygomusic.ui.theme.LocalMyGoExtras

// ------------------------------------------------------------------- home

@Composable
fun HomeScreen(state: MyGoState) {
    LaunchedEffect(Unit) { state.loadHome() }
    val home = state.home
    val ready = home.readyOrNull()
    // C7: capture once; pluginError is a mutable state and can be cleared
    // between the null check and the composition of the error row.
    val pluginError = state.pluginError

    LazyColumn(Modifier.fillMaxSize()) {
        if (pluginError != null && ready == null) {
            item { Spacer(Modifier.height(60.dp)) }
            item {
                ErrorState(
                    pluginError,
                    onRetry = { state.manifest?.let { state.activate(it) } },
                )
            }
        } else if (ready != null) {
            item { HomeHeader(state, ready.title, ready.subtitle, ready.imageUrl, ready.description, ready.sections.firstOrNull()?.tracks) }
            item { ShortcutChips(state) }
            if (ready.sections.isEmpty()) {
                item {
                    EmptyState(
                        "这个音源暂时没有推荐内容",
                        "登录后能看到每日推荐；也可以在「浏览」里逛逛，或换一个音源",
                        icon = Icons.Rounded.MusicNote,
                    )
                }
            } else {
                browseSections(state, ready.sections)
            }
        } else if (home is Load.Loading || home is Load.Idle) {
            item { Spacer(Modifier.height(56.dp)) }
            repeat(3) { item { SkeletonRow() } }
        } else {
            item {
                ErrorState(
                    (home as? Load.Failed)?.message ?: "加载失败",
                    onRetry = { state.loadHome(force = true) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HomeHeader(
    state: MyGoState,
    title: String?,
    subtitle: String?,
    imageUrl: String?,
    description: String?,
    firstTracks: List<Track>?,
) {
    val heading = title?.takeIf { it.isNotBlank() } ?: "为你推荐"
    Column(Modifier.fillMaxWidth()) {
        Text(
            "MYGO MUSIC",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    heading,
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (firstTracks != null && firstTracks.isNotEmpty()) {
                PlayFab(onClick = { state.playQueue(firstTracks, 0) })
            }
        }
        if (!description.isNullOrBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

/** The little shortcut rail MyGo keeps on the home page. */
@Composable
private fun ShortcutChips(state: MyGoState) {
    val chips = buildList {
        if (state.can("liked")) add(Triple("喜欢的音乐", Icons.Rounded.Favorite, Route.Liked))
        if (state.can("recent")) add(Triple("最近播放", Icons.Rounded.History, Route.Recent))
        if (state.can("playlists")) add(Triple("我的歌单", Icons.AutoMirrored.Rounded.QueueMusic, Route.Playlists))
        if (state.can("shows")) add(Triple("播客", Icons.Rounded.Mic, Route.Shows))
    }
    if (chips.isEmpty()) return
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(chips) { (label, icon, route) ->
            Surface(
                onClick = { state.push(route) },
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(17.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

// ----------------------------------------------------------------- browse

@Composable
fun BrowseRouteScreen(state: MyGoState) {
    val navPages = state.manifest?.navigation.orEmpty().mapNotNull { nav ->
        when {
            !nav.page.isNullOrBlank() -> Triple(nav.title ?: nav.page, Route.Page(nav.page!!, nav.title ?: "页面"), true)
            nav.builtin == "liked" -> Triple("喜欢的音乐", Route.Liked, true)
            else -> null
        }
    }
    val canBrowse = state.can("browse")
    LaunchedEffect(canBrowse, navPages.size) { if (canBrowse) state.loadPage("") }
    val page = state.browseRootState()
    val ready = page.readyOrNull()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "浏览",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
            )
        }
        if (navPages.isNotEmpty()) {
            itemsIndexed(navPages, key = { index, item -> "$index-${item.second}" }) { _, (title, route, _) ->
                NavigateRow(
                    title = title,
                    leadingIcon = Icons.Rounded.Album,
                    onClick = { state.push(route) },
                )
            }
        }
        when {
            ready != null && ready.sections.isNotEmpty() -> browseSections(state, ready.sections)
            ready != null && ready.sections.isEmpty() && navPages.isEmpty() ->
                item { EmptyState("这个音源没有可浏览的页面", "试试搜索，或换一个音源") }

            page is Load.Loading && navPages.isEmpty() -> repeat(3) { item { SkeletonRow() } }
            page is Load.Failed && navPages.isEmpty() -> item {
                ErrorState(page.message, onRetry = { state.loadPage("") })
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/** Plugins without a `browse` export still declare navigation pages — list them. */
@Composable
private fun NavEntriesScreen(state: MyGoState) {
    val pages = state.manifest?.navigation?.filter { !it.page.isNullOrBlank() }.orEmpty()
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "浏览",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
            )
        }
        if (pages.isEmpty()) {
            item { EmptyState("这个音源没有可浏览的页面", "试试搜索，或换一个音源") }
        } else {
            itemsIndexed(pages, key = { index, nav -> "$index-${nav.page ?: nav.title ?: ""}" }) { _, nav ->
                NavigateRow(
                    title = nav.title ?: nav.page ?: "页面",
                    leadingIcon = Icons.Rounded.Album,
                    onClick = { state.push(Route.Page(nav.page ?: "", nav.title ?: "页面")) },
                )
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/** A `browse` payload page, reached through the `links` of another page. */
@Composable
fun BrowsePageScreen(state: MyGoState, path: String) {
    LaunchedEffect(path) { state.loadPage(path) }
    val page = state.pages[path]
    val ready = page?.readyOrNull()
    LazyColumn(Modifier.fillMaxSize()) {
        if (ready != null) {
            if (!ready.imageUrl.isNullOrBlank()) {
                item { PageHero(state, ready.title, ready.subtitle, ready.description, ready.imageUrl) }
            } else if (!ready.title.isNullOrBlank()) {
                item { SectionHeader(ready.title!!, subtitle = ready.subtitle) }
            }
            if (ready.sections.isEmpty()) {
                item { EmptyState("这里暂时是空的") }
            } else {
                browseSections(state, ready.sections)
            }
        } else if (page == null || page is Load.Loading) {
            repeat(3) { item { SkeletonRow() } }
        } else {
            item {
                ErrorState(
                    (page as? Load.Failed)?.message ?: "加载失败",
                    onRetry = { state.loadPage(path) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PageHero(
    state: MyGoState,
    title: String?,
    subtitle: String?,
    description: String?,
    imageUrl: String,
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.65f), MaterialTheme.colorScheme.background)
                        )
                    ),
            )
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                title?.takeIf { it.isNotBlank() } ?: "",
                style = MaterialTheme.typography.headlineMedium,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// --------------------------------------------------------------- sections

/** Renders every non-empty section of a browse payload, MyGo-style. */
private fun LazyListScope.browseSections(
    state: MyGoState,
    sections: List<Section>,
) {
    sections.forEachIndexed { sectionIndex, section ->
        val title = section.title?.takeIf { it.isNotBlank() }
        val hasContent = section.tracks.isNotEmpty() || section.albums.isNotEmpty() ||
            section.artists.isNotEmpty() || section.playlists.isNotEmpty() ||
            section.shows.isNotEmpty() || section.stations.isNotEmpty() ||
            section.links.isNotEmpty()
        if (!hasContent) {
            if (!section.empty.isNullOrBlank()) {
                item("empty-${sectionIndex}") {
                    SectionHeader(title ?: "")
                    EmptyState(section.empty!!)
                }
            }
            return@forEachIndexed
        }

        if (title != null) {
            item("title-${sectionIndex}") {
                SectionHeader(
                    title,
                    subtitle = section.subtitle,
                    onMore = section.morePage?.let { page -> { state.push(Route.Page(page, section.pageTitle ?: title)) } },
                )
            }
        }

        if (section.tracks.isNotEmpty()) {
            item("tracks-${sectionIndex}") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(section.tracks, key = { index, track -> "${track.id}-$index" }) { index, track ->
                        MediaCard(
                            title = track.name.ifBlank { "未知歌曲" },
                            subtitle = track.artistNames(),
                            imageUrl = track.album.imageUrl,
                            onClick = { state.playQueue(section.tracks, index) },
                        )
                    }
                }
            }
        }

        if (section.albums.isNotEmpty()) {
            item("albums-${sectionIndex}") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(section.albums, key = { "album-${it.id}" }) { album ->
                        MediaCard(
                            title = album.name,
                            subtitle = album.artists.joinToString(", ") { it.name },
                            imageUrl = album.imageUrl,
                            onClick = { state.openAlbum(album) },
                        )
                    }
                }
            }
        }

        if (section.artists.isNotEmpty()) {
            item("artists-${sectionIndex}") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(section.artists, key = { "artist-${it.id}" }) { artist ->
                        ArtistCard(artist, onClick = { state.openArtist(artist) })
                    }
                }
            }
        }

        if (section.playlists.isNotEmpty()) {
            item("playlists-${sectionIndex}") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(section.playlists, key = { "playlist-${it.id}" }) { playlist ->
                        MediaCard(
                            title = playlist.name,
                            subtitle = playlist.owner ?: "${playlist.trackCount} 首",
                            imageUrl = playlist.imageUrl,
                            onClick = { state.openPlaylist(playlist) },
                        )
                    }
                }
            }
        }

        if (section.shows.isNotEmpty()) {
            item("shows-${sectionIndex}") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(section.shows, key = { "show-${it.id}" }) { show ->
                        MediaCard(
                            title = show.name,
                            subtitle = show.publisher ?: "${show.episodeCount} 集",
                            imageUrl = show.imageUrl,
                            width = 130.dp,
                            onClick = { state.openShow(show) },
                        )
                    }
                }
            }
        }

        if (section.stations.isNotEmpty()) {
            item("stations-${sectionIndex}") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(section.stations, key = { "station-${it.id}" }) { station ->
                        MediaCard(
                            title = station.name,
                            subtitle = station.description ?: "电台",
                            imageUrl = station.imageUrl,
                            onClick = { state.playStation(station.id, station.name) },
                        )
                    }
                }
            }
        }

        if (section.links.isNotEmpty()) {
            item("links-${sectionIndex}") {
                Column {
                    section.links.forEach { link ->
                        NavigateRow(
                            title = link.title,
                            subtitle = link.subtitle,
                            imageUrl = link.imageUrl,
                            onClick = { state.openPage(link) },
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------- simple track lists

/** A page that is just a list of tracks: liked, recent, playlist. */
@Composable
fun TrackListBody(
    state: MyGoState,
    tracks: List<Track>,
    showCover: Boolean = true,
    onPlay: (Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(tracks, key = { index, track -> "${track.id}-$index" }) { index, track ->
            TrackRow(
                track = track,
                state = state,
                index = index,
                showCover = showCover,
                isCurrent = state.currentTrack?.id == track.id,
                onPlay = { onPlay(index) },
                onOpenAlbum = { state.openAlbum(it) },
                onOpenArtist = { state.openArtist(it) },
            )
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/** Header used on the library-style list pages. */
@Composable
fun ListPageHeader(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onPlayAll: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!imageUrl.isNullOrBlank()) {
            CoverImage(
                imageUrl,
                Modifier
                    .width(92.dp)
                    .height(92.dp)
                    .clip(RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                iconSize = 26.dp,
            )
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onPlayAll != null) {
            Spacer(Modifier.width(10.dp))
            PlayFab(onClick = onPlayAll)
        }
    }
}

