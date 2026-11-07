package dev.walden.mygomusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.walden.mygomusic.SearchResult
import dev.walden.mygomusic.ui.Load
import dev.walden.mygomusic.ui.MyGoState
import dev.walden.mygomusic.ui.SearchKind
import dev.walden.mygomusic.ui.components.EmptyState
import dev.walden.mygomusic.ui.components.ErrorState
import dev.walden.mygomusic.ui.components.NavigateRow
import dev.walden.mygomusic.ui.components.SkeletonList
import dev.walden.mygomusic.ui.components.TrackRow
import dev.walden.mygomusic.ui.readyOrNull
import dev.walden.mygomusic.ui.theme.LocalMyGoExtras

@Composable
fun SearchScreen(state: MyGoState) {
    val extras = LocalMyGoExtras.current
    val focus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (state.searchQuery.isBlank()) runCatching { focus.requestFocus() }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { state.onSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .focusRequester(focus),
            placeholder = { Text("搜索歌曲、专辑、歌手、播客") },
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = extras.faint)
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { state.clearSearch() }) {
                        Icon(Icons.Rounded.Close, contentDescription = "清空", tint = extras.faint)
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(999.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = extras.hairline,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { state.runSearch() }),
        )

        val query = state.searchQuery
        val result = state.search
        when {
            query.isBlank() -> EmptyState(
                "听点什么？",
                "搜索你喜欢的歌曲、专辑、歌手或播客",
                icon = Icons.Rounded.Search,
            )

            state.suggestions.isNotEmpty() && result !is Load.Ready && result !is Load.Loading -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(state.suggestions) { _, suggestion ->
                        NavigateRow(
                            title = suggestion,
                            leadingIcon = Icons.Rounded.Search,
                            iconTint = extras.faint,
                            onClick = {
                                state.onSearchQuery(suggestion)
                                state.runSearch()
                            },
                        )
                    }
                }
            }

            result is Load.Loading -> SkeletonList(10)

            result is Load.Failed -> ErrorState(result.message, onRetry = { state.runSearch() })

            result is Load.Ready -> Column(Modifier.fillMaxSize()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SearchKind.entries, key = { it.name }) { kind ->
                        FilterChip(
                            selected = state.searchKind == kind,
                            onClick = { state.changeSearchKind(kind) },
                            label = { Text(kind.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                SearchResults(state, result.value)
            }

            else -> Unit
        }
    }
}

@Composable
private fun SearchResults(state: MyGoState, result: SearchResult) {
    val kind = state.searchKind
    val tracks = result.tracks
    val isEmpty = when (kind) {
        SearchKind.Tracks -> result.tracks.isEmpty()
        SearchKind.Albums -> result.albums.isEmpty()
        SearchKind.Artists -> result.artists.isEmpty()
        SearchKind.Playlists -> result.playlists.isEmpty()
        SearchKind.Shows -> result.shows.isEmpty()
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (isEmpty) {
            item { EmptyState("没有找到相关${kind.label}", "换个关键词试试") }
        }
        when (kind) {
            SearchKind.Tracks -> itemsIndexed(tracks, key = { index, track -> "${track.id}-$index" }) { index, track ->
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

            SearchKind.Albums -> itemsIndexed(result.albums, key = { _, album -> "album-${album.id}" }) { _, album ->
                NavigateRow(
                    title = album.name,
                    subtitle = album.artists.joinToString(", ") { it.name },
                    imageUrl = album.imageUrl,
                    onClick = { state.openAlbum(album) },
                )
            }

            SearchKind.Artists -> itemsIndexed(result.artists, key = { _, artist -> "artist-${artist.id}" }) { _, artist ->
                NavigateRow(
                    title = artist.name,
                    subtitle = if (artist.following) "已关注" else null,
                    imageUrl = artist.imageUrl,
                    onClick = { state.openArtist(artist) },
                )
            }

            SearchKind.Playlists -> itemsIndexed(result.playlists, key = { _, playlist -> "playlist-${playlist.id}" }) { _, playlist ->
                NavigateRow(
                    title = playlist.name,
                    subtitle = playlist.owner ?: "${playlist.trackCount} 首",
                    imageUrl = playlist.imageUrl,
                    onClick = { state.openPlaylist(playlist) },
                )
            }

            SearchKind.Shows -> itemsIndexed(result.shows, key = { _, show -> "show-${show.id}" }) { _, show ->
                NavigateRow(
                    title = show.name,
                    subtitle = show.publisher ?: "${show.episodeCount} 集",
                    imageUrl = show.imageUrl,
                    onClick = { state.openShow(show) },
                )
            }
        }
        if (result.more) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.searchLoadingMore) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        TextButton(onClick = { state.searchMore() }) { Text("加载更多") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
