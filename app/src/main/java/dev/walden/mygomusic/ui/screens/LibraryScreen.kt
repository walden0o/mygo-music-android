package dev.walden.mygomusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.walden.mygomusic.ui.Load
import dev.walden.mygomusic.ui.MyGoState
import dev.walden.mygomusic.ui.Route
import dev.walden.mygomusic.ui.components.MediaCard
import dev.walden.mygomusic.ui.components.NavigateRow
import dev.walden.mygomusic.ui.components.PersonAvatar
import dev.walden.mygomusic.ui.components.SkeletonRow
import dev.walden.mygomusic.ui.readyOrNull
import dev.walden.mygomusic.ui.theme.LocalMyGoExtras

@Composable
fun LibraryScreen(state: MyGoState) {
    LaunchedEffect(Unit) { state.loadLibrary() }
    val extras = LocalMyGoExtras.current
    val library = state.library
    val playlists = library.readyOrNull().orEmpty()

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "音乐库",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
            )
        }
        item { AccountCard(state) }
        item {
            Column {
                if (state.can("liked")) {
                    NavigateRow(
                        title = "喜欢的音乐",
                        subtitle = "你点过心的每一首",
                        leadingIcon = Icons.Rounded.Favorite,
                        onClick = { state.push(Route.Liked) },
                    )
                }
                if (state.can("recent")) {
                    NavigateRow(
                        title = "最近播放",
                        subtitle = "接着上次的地方听",
                        leadingIcon = Icons.Rounded.History,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = { state.push(Route.Recent) },
                    )
                }
                if (state.can("playlists")) {
                    NavigateRow(
                        title = "我的歌单",
                        subtitle = if (playlists.isEmpty()) null else "${playlists.size} 个歌单",
                        leadingIcon = Icons.AutoMirrored.Rounded.QueueMusic,
                        iconTint = MaterialTheme.colorScheme.secondary,
                        onClick = { state.push(Route.Playlists) },
                    )
                }
                if (state.can("shows")) {
                    NavigateRow(
                        title = "播客",
                        subtitle = "订阅的节目与电台",
                        leadingIcon = Icons.Rounded.Mic,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        onClick = { state.push(Route.Shows) },
                    )
                }
            }
        }
        if (state.can("playlists") && playlists.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "我的歌单",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { state.push(Route.Playlists) }) { Text("全部") }
                }
            }
            when (library) {
                is Load.Loading, is Load.Idle -> item { SkeletonRow() }
                is Load.Failed -> item {
                    Text(
                        (library as Load.Failed).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }

                is Load.Ready -> if (playlists.isNotEmpty()) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(playlists, key = { it.id }) { playlist ->
                                MediaCard(
                                    title = playlist.name,
                                    subtitle = "${playlist.trackCount} 首",
                                    imageUrl = playlist.imageUrl,
                                    onClick = { state.openPlaylist(playlist) },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            HorizontalHairline()
        }
        item {
            Column(Modifier.padding(vertical = 6.dp)) {
                NavigateRow(
                    title = "插件设置",
                    subtitle = state.manifest?.name,
                    leadingIcon = Icons.Rounded.Settings,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { state.manifest?.let { state.openPluginSettings(it) } },
                )
                NavigateRow(
                    title = "切换插件",
                    subtitle = "已安装 ${state.catalog.size} 个",
                    leadingIcon = Icons.Rounded.SwapHoriz,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = { state.openPlugins() },
                )
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun HorizontalHairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(1.dp)
            .background(LocalMyGoExtras.current.hairline),
    )
}

@Composable
private fun AccountCard(state: MyGoState) {
    val account = state.account
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (account != null) {
                PersonAvatar(account.imageUrl, size = 52.dp)
            } else {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = LocalMyGoExtras.current.faint,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account?.name ?: "未登录",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (account != null) state.manifest?.name ?: "" else "登录后同步你的收藏与歌单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (account == null && state.can("signIn")) {
                TextButton(onClick = { state.startSignIn() }) { Text("登录") }
            } else if (account != null) {
                TextButton(onClick = { state.openAccount() }) { Text("账户") }
            }
        }
    }
}
