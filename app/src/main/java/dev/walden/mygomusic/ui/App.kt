package dev.walden.mygomusic.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.walden.mygomusic.ui.components.CoverImage
import dev.walden.mygomusic.ui.components.PersonAvatar
import dev.walden.mygomusic.ui.screens.BrowseRouteScreen
import dev.walden.mygomusic.ui.screens.HomeScreen
import dev.walden.mygomusic.ui.screens.LibraryScreen
import dev.walden.mygomusic.ui.screens.PlayerScreen
import dev.walden.mygomusic.ui.screens.RouteScreen
import dev.walden.mygomusic.ui.screens.SearchScreen
import dev.walden.mygomusic.ui.sheets.AccountSheet
import dev.walden.mygomusic.ui.sheets.PluginSettingsSheet
import dev.walden.mygomusic.ui.sheets.PluginsSheet
import dev.walden.mygomusic.ui.sheets.QueueSheet
import dev.walden.mygomusic.ui.sheets.SettingsSheet
import dev.walden.mygomusic.ui.sheets.SignInSheet
import dev.walden.mygomusic.ui.theme.LocalMyGoExtras
import dev.walden.mygomusic.ui.theme.MyGoTheme
import kotlinx.coroutines.launch

/**
 * Root of the app: Material 3 scaffold (top bar, mini player, navigation bar)
 * with MyGo's information architecture — 首页 / 浏览 / 搜索 / 音乐库 — and a
 * full-screen now-playing surface that slides over everything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyGoApp(state: MyGoState) {
    MyGoTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current

        // Android 13+: the media notification needs a runtime permission; ask once.
        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Keep the playback mirror fresh while a queue is active.
        LaunchedEffect(state.isPlaying, state.currentTrack?.id) {
            while (true) {
                state.refreshPlayer()
                kotlinx.coroutines.delay(500)
            }
        }

        LaunchedEffect(Unit) {
            snapshotFlow { state.message }.collect { msg ->
                if (msg != null) {
                    state.consumeMessage()
                    scope.launch { snackbar.showSnackbar(msg) }
                }
            }
        }

        BackHandler { if (!state.back()) (context as? android.app.Activity)?.finish() }

        val showChromeBars = state.stack.isEmpty()

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopBar(
                    state = state,
                    detailTitle = state.stack.lastOrNull()?.title,
                )
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding()) {
                    MiniPlayer(state)
                    if (showChromeBars) MyGoNavigationBar(state)
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                if (!state.pluginReady && state.pluginError == null) {
                    PluginLoading()
                } else {
                    val route = state.stack.lastOrNull()
                    if (route != null) {
                        RouteScreen(state, route)
                    } else {
                        when (state.tab) {
                            Tab.Home -> HomeScreen(state)
                            Tab.Browse -> BrowseRouteScreen(state)
                            Tab.Search -> SearchScreen(state)
                            Tab.Library -> LibraryScreen(state)
                        }
                    }
                }
            }
        }

        // Now playing, over the scaffold.
        AnimatedVisibility(
            visible = state.playerOpen,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            PlayerScreen(state)
        }

        // Sheets: plugin switcher, settings, account, plugin config, queue.
        val sheet = state.sheet
        if (sheet != null) {
            ModalBottomSheet(
                onDismissRequest = { state.closeSheet() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                dragHandle = { BottomSheetDefaults.DragHandle() },
            ) {
                when (sheet) {
                    Sheet.Plugins -> PluginsSheet(state)
                    Sheet.Settings -> SettingsSheet(state)
                    Sheet.Account -> AccountSheet(state)
                    Sheet.Queue -> QueueSheet(state)
                    is Sheet.PluginSettings -> PluginSettingsSheet(state, sheet.manifest)
                }
            }
        }

        // Sign-in flow: a QR code the plugin hands us.
        state.signIn?.let { signIn ->
            ModalBottomSheet(
                onDismissRequest = { state.dismissSignIn() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                SignInSheet(state, signIn)
            }
        }
    }
}

// ------------------------------------------------------------------ chrome

@Composable
private fun PluginLoading() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "正在载入插件…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Slim top bar: the active plugin on the left, settings and account on the right. */
@Composable
private fun TopBar(state: MyGoState, detailTitle: String?) {
    val extras = LocalMyGoExtras.current
    Surface(
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (detailTitle != null) {
                    IconButton(onClick = { state.back() }) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        detailTitle,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { state.openPlugins() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                state.manifest?.name?.take(1) ?: "M",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            state.manifest?.name ?: "MyGo Music",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "切换插件",
                            tint = extras.faint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                IconButton(onClick = { state.openSettings() }) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(Modifier.padding(end = 4.dp)) {
                    PersonAvatar(
                        state.account?.imageUrl,
                        size = 30.dp,
                        onClick = { state.openAccount() },
                    )
                }
            }
            HorizontalDivider(color = extras.hairline)
        }
    }
}

/** The floating mini player MyGo keeps pinned above its sidebar's bottom edge. */
@Composable
private fun MiniPlayer(state: MyGoState) {
    val track = state.currentTrack ?: return
    val extras = LocalMyGoExtras.current
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable { state.openPlayer() },
    ) {
        Column {
            LinearProgressIndicator(
                progress = { progress },
                color = MaterialTheme.colorScheme.primary,
                trackColor = extras.hairline,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(
                    track.album.imageUrl,
                    Modifier.size(44.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.name.ifBlank { "未知歌曲" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artistNames().ifBlank { "未知歌手" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { state.togglePlay() }) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(onClick = { state.next() }) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "下一首",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MyGoNavigationBar(state: MyGoState) {
    val extras = LocalMyGoExtras.current
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = state.tab == Tab.Home,
            onClick = { state.selectTab(Tab.Home) },
            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
            label = { Text(Tab.Home.label) },
            colors = navColors(extras.isDark),
        )
        NavigationBarItem(
            selected = state.tab == Tab.Browse,
            onClick = { state.selectTab(Tab.Browse) },
            icon = { Icon(Icons.Rounded.Explore, contentDescription = null) },
            label = { Text(Tab.Browse.label) },
            colors = navColors(extras.isDark),
        )
        NavigationBarItem(
            selected = state.tab == Tab.Search,
            onClick = { state.selectTab(Tab.Search) },
            icon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            label = { Text(Tab.Search.label) },
            colors = navColors(extras.isDark),
        )
        NavigationBarItem(
            selected = state.tab == Tab.Library,
            onClick = { state.selectTab(Tab.Library) },
            icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
            label = { Text(Tab.Library.label) },
            colors = navColors(extras.isDark),
        )
    }
}

@Composable
private fun navColors(dark: Boolean) = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = if (dark) 0.18f else 0.12f),
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
