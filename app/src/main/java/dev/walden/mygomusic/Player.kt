package dev.walden.mygomusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Process-wide playback: one ExoPlayer, a queue of plugin tracks, stream
 * URLs resolved through the active plugin right before each track plays.
 */
object Player {
    private var exo: ExoPlayer? = null
    private lateinit var httpFactory: DefaultHttpDataSource.Factory
    private var serviceStarted = false

    var runtime: PluginRuntime? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var queue: List<Track> = emptyList()
        private set
    var index: Int = -1
        private set
    var current: Track? = null
        private set

    var onUiUpdate: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun player(context: Context): ExoPlayer {
        exo?.let { return it }
        val app = context.applicationContext
        httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android) MyGoMusic")
        val built = ExoPlayer.Builder(app)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory)
            )
            .build()
        built.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    if (index + 1 < queue.size) playAt(app, index + 1)
                }
                onUiUpdate?.invoke()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onUiUpdate?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                onError?.invoke("播放失败：${error.message ?: error.errorCodeName}")
                onUiUpdate?.invoke()
            }
        })
        exo = built
        return built
    }

    private fun ensureService(context: Context) {
        if (serviceStarted) return
        serviceStarted = true
        val intent = Intent(context, PlaybackService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun playQueue(context: Context, tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        queue = tracks
        playAt(context, startIndex.coerceIn(0, tracks.size - 1))
    }

    fun playAt(context: Context, at: Int) {
        val rt = runtime ?: run {
            onError?.invoke("没有可用的插件")
            return
        }
        if (at !in queue.indices) return
        index = at
        val track = queue[at]
        current = track
        ensureService(context)
        val p = player(context)
        onUiUpdate?.invoke()
        scope.launch {
            try {
                val quality = rt.setting("quality").ifEmpty { "exhigh" }
                val args = buildJsonObject {
                    put("id", track.id)
                    put("quality", quality)
                }.toString()
                val result = rt.call("stream", args)
                val stream = pluginJson.decodeFromString(StreamInfo.serializer(), result)
                if (stream.url.isBlank()) throw PluginCallException("unavailable", "插件没有返回播放地址")
                httpFactory.setDefaultRequestProperties(stream.headers)
                val metadata = MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artistNames())
                    .setAlbumTitle(track.album.name)
                    .apply {
                        track.album.imageUrl?.let { setArtworkUri(Uri.parse(it)) }
                    }
                    .build()
                val item = MediaItem.Builder()
                    .setUri(stream.url)
                    .setMediaMetadata(metadata)
                    .build()
                p.setMediaItem(item)
                p.prepare()
                p.playWhenReady = true
                if (rt.can("played")) {
                    launch {
                        runCatching {
                            rt.call("played", buildJsonObject { put("id", track.id) }.toString())
                        }
                    }
                }
            } catch (e: PluginCallException) {
                if (e.code == "signIn") onError?.invoke("这首歌需要先登录")
                else onError?.invoke(e.message ?: "无法播放")
            } catch (e: Exception) {
                onError?.invoke("无法播放：${e.message}")
            }
            onUiUpdate?.invoke()
        }
    }

    fun next(context: Context) {
        if (index + 1 < queue.size) playAt(context, index + 1)
    }

    fun previous(context: Context) {
        if (index - 1 >= 0) playAt(context, index - 1)
    }

    fun toggle(context: Context) {
        val p = player(context)
        if (p.isPlaying) p.pause() else p.play()
        onUiUpdate?.invoke()
    }

    fun seekTo(positionMs: Long) {
        exo?.seekTo(positionMs)
    }

    fun isPlaying(): Boolean = exo?.isPlaying == true
    fun position(): Long = exo?.currentPosition ?: 0
    fun duration(): Long = (exo?.duration ?: 0).coerceAtLeast(0)
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Android 12+ requires startForeground() within ~5s of
        // startForegroundService(); stream URLs can take longer than that to
        // resolve, so post a preparation notification right away. Media3
        // swaps in its own media notification once playback begins.
        startPreparationForeground()
        session = MediaSession.Builder(this, Player.player(this)).build()
        addSession(session!!)
    }

    private fun startPreparationForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = "playback"
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "播放", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MyGo Music")
            .setContentText("正在准备播放…")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
