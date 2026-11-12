package dev.walden.mygomusic.ui.sheets

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.walden.mygomusic.PluginManifest
import dev.walden.mygomusic.SettingDef
import dev.walden.mygomusic.ui.MyGoState
import dev.walden.mygomusic.ui.Sheet
import dev.walden.mygomusic.ui.SignInState
import dev.walden.mygomusic.ui.components.CoverImage
import dev.walden.mygomusic.ui.components.EmptyState
import dev.walden.mygomusic.ui.components.PersonAvatar
import dev.walden.mygomusic.ui.theme.LocalMyGoExtras
import dev.walden.mygomusic.ui.theme.ThemeMode

@Composable
private fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- plugins

@Composable
fun PluginsSheet(state: MyGoState) {
    // Two bundled NetEase variants both call themselves "网易云音乐" but only one
    // still speaks the current API. Disambiguate colliding names with the id so
    // the user can actually tell the rows apart.
    val duplicated = state.catalog.groupBy { it.name }.filterValues { it.size > 1 }.keys
    var showInstall by remember { mutableStateOf(false) }
    var installInput by remember { mutableStateOf("") }
    var uninstallTarget by remember { mutableStateOf<PluginManifest?>(null) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { state.installPluginFromZip(it) }
    }
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("插件", "共 " + state.catalog.size + " 个 · 点选切换音源")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { showInstall = true }, enabled = !state.pluginBusy) {
                Text("安装插件")
            }
        }
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(state.catalog, key = { it.id }) { plugin ->
                val active = state.manifest?.id == plugin.id
                val installed = plugin.dir != null
                val displayName =
                    if (plugin.name in duplicated) plugin.name + " · " + plugin.id else plugin.name
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { state.activate(plugin) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                plugin.name.take(1),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val note = buildString {
                                append("v" + plugin.version.ifBlank { "—" })
                                if (installed) append(" · 已安装")
                                plugin.origin?.type?.let { append(" · " + it) }
                                if (plugin.partial) append(" · 部分功能受限")
                            }
                            Text(
                                note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "使用中",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (installed) {
                        Row(
                            Modifier.padding(start = 74.dp, end = 20.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (plugin.origin?.type == "github") {
                                TextButton(
                                    onClick = { state.updatePlugin(plugin) },
                                    enabled = !state.pluginBusy,
                                ) { Text("更新") }
                            }
                            TextButton(
                                onClick = { uninstallTarget = plugin },
                                enabled = !state.pluginBusy,
                            ) { Text("卸载") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showInstall) {
        AlertDialog(
            onDismissRequest = { showInstall = false },
            title = { Text("安装插件") },
            text = {
                Column {
                    Text("支持 GitHub 仓库/地址，或本地 zip 插件包。")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = installInput,
                        onValueChange = { installInput = it },
                        singleLine = true,
                        enabled = !state.pluginBusy,
                        placeholder = { Text("owner/repo 或 GitHub 地址") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.pluginBusy) {
                        Spacer(Modifier.height(8.dp))
                        Text("正在下载/解析…", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            showInstall = false
                            zipPicker.launch(arrayOf("*/*"))
                        },
                        enabled = !state.pluginBusy,
                    ) { Text("从 zip 安装") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.installPluginFromGitHub(installInput)
                        showInstall = false
                    },
                    enabled = installInput.isNotBlank() && !state.pluginBusy,
                ) { Text("安装") }
            },
            dismissButton = {
                TextButton(onClick = { showInstall = false }) { Text("取消") }
            },
        )
    }

    state.pendingInstall?.let { pending ->
        val m = pending.pkg.manifest
        AlertDialog(
            onDismissRequest = { state.cancelPluginInstall() },
            title = { Text(if (pending.replacing) "更新插件" else "安装插件") },
            text = {
                Column {
                    Text(m.name + if (m.version.isBlank()) "" else " v" + m.version)
                    val originText = m.origin?.repo ?: m.origin?.label
                    if (!originText.isNullOrBlank()) Text("来源：" + originText)
                    if (m.networkHosts.isNotEmpty()) Text("网络权限：" + m.networkHosts.joinToString(", "))
                    if (m.processPrograms.isNotEmpty()) {
                        Text("进程权限：" + m.processPrograms.joinToString(", "))
                        Text("Android 端不支持 host.spawn，相关功能可能受限。")
                    }
                    if (m.playback != "stream") Text("播放方式：" + m.playback + "（部分功能受限）")
                    if (m.partialReasons.isNotEmpty()) Text("受限：" + m.partialReasons.joinToString("、"))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { state.confirmPluginInstall() },
                    enabled = !state.pluginBusy,
                ) { Text(if (pending.replacing) "更新" else "安装") }
            },
            dismissButton = {
                TextButton(onClick = { state.cancelPluginInstall() }) { Text("取消") }
            },
        )
    }

    uninstallTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载插件") },
            text = { Text("卸载 " + target.name + "？插件数据会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    state.uninstallPlugin(target)
                    uninstallTarget = null
                }) { Text("卸载") }
            },
            dismissButton = {
                TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
            },
        )
    }
}

// --------------------------------------------------------------- settings

@Composable
fun SettingsSheet(state: MyGoState) {
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("设置")
        Text(
            "外观",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
        )
        ThemeMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { state.setTheme(mode) }
                    .padding(horizontal = 24.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (mode) {
                        ThemeMode.System -> "跟随系统"
                        ThemeMode.Light -> "浅色"
                        ThemeMode.Dark -> "深色"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (state.themeMode == mode) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("动态取色", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "取系统壁纸的配色",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.dynamicColor, onCheckedChange = { state.updateDynamicColor(it) })
            }
        }
        HorizontalDivider(
            color = LocalMyGoExtras.current.hairline,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        )
        // L16a: after POST_NOTIFICATIONS is denied, offer a recovery entry that
        // opens the app's notification settings instead of nagging on launch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state.notificationPermissionDenied) {
            val context = LocalContext.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        runCatching { context.startActivity(intent) }
                            .onFailure { state.toast("无法打开系统设置") }
                    }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("通知权限已关闭 · 去设置", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "开启后才能显示播放通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { state.manifest?.let { state.openPluginSettings(it) } }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("插件设置", style = MaterialTheme.typography.bodyLarge)
                Text(
                    state.manifest?.name ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { state.openPlugins() }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Text("切换插件", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "MyGo Music for Android · 0.1.0",
            style = MaterialTheme.typography.labelSmall,
            color = LocalMyGoExtras.current.faint,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

// ---------------------------------------------------------------- account

@Composable
fun AccountSheet(state: MyGoState) {
    val account = state.account
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        if (account != null) {
            PersonAvatar(account.imageUrl, size = 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(account.name, style = MaterialTheme.typography.titleLarge)
            Text(
                state.manifest?.name ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            if (state.can("signOut")) {
                TextButton(onClick = { state.signOut() }) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("退出登录")
                }
            }
        } else {
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = LocalMyGoExtras.current.faint,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text("未登录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.can("signIn")) "登录后可以同步收藏、歌单与推荐"
                else "此插件不支持在应用内登录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.can("signIn")) {
                Spacer(Modifier.height(18.dp))
                Button(onClick = { state.startSignIn() }) { Text("登录") }
            }
        }
    }
}

// -------------------------------------------------------- plugin settings

@Composable
fun PluginSettingsSheet(state: MyGoState, manifest: PluginManifest) {
    val initial = remember(manifest.id) { state.pluginSettings(manifest) }
    val values = remember(manifest.id) { mutableStateMapOf<String, String>().apply { putAll(initial) } }
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("${manifest.name} 设置", "保存后立即生效")
        if (manifest.settings.isEmpty()) {
            EmptyState("此插件没有可配置项")
        } else {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(manifest.settings, key = { it.key }) { def ->
                    SettingRow(def, values[def.key].orEmpty()) { values[def.key] = it }
                }
                item {
                    Button(
                        onClick = { state.savePluginSettings(manifest, values.toMap()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    ) { Text("保存") }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingRow(def: SettingDef, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
        Text(def.label.ifBlank { def.key }, style = MaterialTheme.typography.bodyLarge)
        if (!def.help.isNullOrBlank()) {
            Text(
                def.help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (def.type == "select" && def.options.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            val currentLabel = def.options.firstOrNull { it.first == value }?.second
                ?: def.options.firstOrNull()?.second ?: ""
            Box {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open = true },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(currentLabel, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                    }
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    def.options.forEach { (optionValue, optionLabel) ->
                        DropdownMenuItem(
                            text = { Text(optionLabel.ifBlank { optionValue }) },
                            onClick = {
                                open = false
                                onChange(optionValue)
                            },
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                placeholder = { Text(def.help ?: "") },
            )
        }
    }
}

// ------------------------------------------------------------------ queue

@Composable
fun QueueSheet(state: MyGoState) {
    val queue = state.queue
    Column(Modifier.fillMaxWidth()) {
        SheetTitle("播放队列", if (queue.isEmpty()) null else "共 ${queue.size} 首")
        if (queue.isEmpty()) {
            EmptyState("队列是空的")
        } else {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(queue, key = { index, track -> "q-${track.id}-$index" }) { index, track ->
                    val current = state.currentTrack?.id == track.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { state.playAt(index) }
                            .padding(horizontal = 20.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(
                            track.album.imageUrl,
                            Modifier.size(42.dp),
                            shape = RoundedCornerShape(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                track.name.ifBlank { "未知歌曲" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                track.artistNames(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ----------------------------------------------------------------- sign in

@Composable
fun SignInSheet(state: MyGoState, signIn: SignInState) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetTitle("登录", "用 ${state.manifest?.name ?: "插件"} 扫码登录")
        if (signIn.qr != null) {
            Box(
                Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(androidx.compose.ui.graphics.Color.White)
                    .padding(10.dp),
            ) {
                Image(
                    bitmap = signIn.qr.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Box(
                Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            signIn.error ?: signIn.text ?: "正在获取二维码…",
            style = MaterialTheme.typography.bodyMedium,
            color = if (signIn.error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = { state.dismissSignIn() }) { Text("取消") }
    }
}
