package dev.walden.mygomusic

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import androidx.media3.common.Player as Media3Player

/**
 * Process-wide playback: one ExoPlayer, a queue of plugin tracks, stream
 * URLs resolved through the active plugin right before each track plays.
 *
 * ExoPlayer itself only ever holds the *current* item (streams are resolved
 * lazily per track). [QueueForwardingPlayer] exposes the real queue's
 * next/previous commands to the MediaSession so notification / lockscreen /
 * Bluetooth controls work, while leaving the timeline/index/count consistent
 * with ExoPlayer's single item (see the H3 note on [QueueForwardingPlayer]).
 */
object Player {
    private var exo: ExoPlayer? = null
    private lateinit var httpFactory: androidx.media3.datasource.okhttp.OkHttpDataSource.Factory
    private var serviceStarted = false

    /**
     * H2: per-stream HTTP headers keyed by the *exact* resolved stream URL. The
     * [ResolvingDataSource] below applies them per DataSpec, so track A's auth
     * headers can never leak into track B (the old global
     * `httpFactory.setDefaultRequestProperties` mutated shared state).
     */
    private val streamHeaders = ConcurrentHashMap<String, Map<String, String>>()

    /** H2: the in-flight stream resolve for the latest [playAt]; cancelled on each new track. */
    private var resolveJob: Job? = null

    /** M10: stop auto-advancing after this many consecutive failures (all-unavailable album). */
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private var consecutiveFailures = 0

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
        // OkHttp as the wire: HttpURLConnection (DefaultHttpDataSource) was observed to
        // drop the Referer header that bilibili's CDN requires (everything got 403), while
        // the identical headers via OkHttp return 206. OkHttpDataSource honors
        // DataSpec.httpRequestHeaders the same way, so the per-item resolver is unchanged.
        val okhttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // cross-protocol redirects (http<->https) are what
            // setAllowCrossProtocolRedirects(true) allowed before
            .build()
        val okhttpFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okhttpClient)
            // Keep the browser-like UA: several CDNs (NetEase in particular)
            // serve an error page instead of audio to non-browser agents,
            // which surfaces as UnrecognizedInputFormatException.
            .setUserAgent("Mozilla/5.0 (Linux; Android) MyGoMusic")
        // H2: resolve per-item headers from the DataSpec (keyed by stream URL). No global mutation.
        val resolvingFactory = ResolvingDataSource.Factory(
            okhttpFactory,
            ResolvingDataSource.Resolver { dataSpec ->
                val headers = streamHeaders[dataSpec.uri.toString()]
                if (headers.isNullOrEmpty()) dataSpec
                else dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
            },
        )
        val p = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            // H4: request audio focus (pause on call / duck) and pause when becoming noisy.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // M9: keep CPU + Wi-Fi alive while streaming with the screen off (needs WAKE_LOCK).
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.addListener(object : Media3Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Media3Player.STATE_IDLE || state == Media3Player.STATE_ENDED) ensureService(app)
                // Natural end of a track -> advance to the next one.
                if (state == Media3Player.STATE_ENDED) {
                    if (index + 1 < queue.size) playAt(app, index + 1)
                }
                onUiUpdate?.invoke()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // M10: real playback started -> reset the consecutive-failure guard.
                if (isPlaying) consecutiveFailures = 0
                onUiUpdate?.invoke()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                onUiUpdate?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                // M10: a single failing track must not stall the whole queue.
                handleTrackFailure(app, "播放失败：${error.errorCodeName}")
            }
        })
        exo = p
        return p
    }

    fun playQueue(context: Context, tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        queue = tracks
        consecutiveFailures = 0 // M10: a fresh queue starts a fresh failure budget.
        playAt(context, startIndex.coerceIn(tracks.indices))
    }

    fun playAt(context: Context, at: Int) {
        if (at !in queue.indices) return
        val app = context.applicationContext
        val track = queue[at]
        val p = player(app)
        ensureService(app)
        // H2: cancel any in-flight resolve from a previous playAt so a stale stream can't win.
        resolveJob?.cancel()
        index = at
        current = track
        onUiUpdate?.invoke()
        resolveJob = scope.launch {
            val rt = runtime
            // HOST_SPEC: a track's own `url` is often the *page* address (e.g.
            // bilibili sets https://www.bilibili.com/video/<bvid>), not audio.
            // Whenever the plugin exports `stream`, it MUST be consulted for the
            // playable URL + required Referer/UA headers. Only plugins without a
            // `stream` export (playback="remote") hand us a direct URL.
            var url: String? = null
            val mustResolve = rt != null && rt.can("stream") && !track.unavailable
            if (mustResolve) {
                try {
                    // HOST_SPEC: stream({id, quality}). The quality setting must
                    // be forwarded — dropping it made plugins fall back to a
                    // tier that returns a preview/error payload instead of audio.
                    val payload = buildJsonObject {
                        put("id", track.id)
                        put("quality", rt?.setting("quality")?.ifEmpty { "exhigh" } ?: "exhigh")
                        track.source?.let { put("source", it) }
                    }
                    val stream = pluginJson.decodeFromString<StreamInfo>(
                        rt.call("stream", payload.toString()),
                    )
                    // H2: this resolve must still be for the current track before touching the player.
                    if (index != at || current?.id != track.id) return@launch
                    url = stream.url
                    // H2: per-item headers keyed by this exact URL (bounded; no cross-track leak).
                    streamHeaders.keys.retainAll(setOf(stream.url))
                    if (stream.headers.isNotEmpty()) streamHeaders[stream.url] = stream.headers
                    android.util.Log.d("MyGoPlay", "resolved url=${stream.url.take(100)} headers=${stream.headers.keys}")
                } catch (e: Exception) {
                    // C1/M10: a cancelled resolve (new track, plugin switch, runtime closed) is NOT
                    // a track failure — never count it, surface it, or auto-advance on it.
                    if (e is CancellationException) throw e
                    if (runtime !== rt) return@launch // active plugin changed mid-resolve
                    if (index != at || current?.id != track.id) return@launch
                    handleTrackFailure(app, e.message ?: "无法播放")
                    return@launch
                }
            } else {
                // No `stream` export: the plugin handed us a directly playable URL.
                url = track.url
            }
            // H2: final staleness guard right before mutating the player.
            if (index != at || current?.id != track.id) return@launch
            if (url == null) {
                handleTrackFailure(app, "该歌曲暂不可播放")
                return@launch
            }
            val item = MediaItem.Builder()
                .setUri(url)
                .setMediaId(track.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.name)
                        .setArtist(track.artistNames())
                        .setArtworkUri(track.album.imageUrl?.let(Uri::parse))
                        .build(),
                )
                .build()
            p.setMediaItem(item, 0)
            p.prepare()
            p.playWhenReady = true
            if (rt != null) {
                try {
                    rt.call(
                        "played",
                        buildJsonObject {
                            put("id", track.id)
                            put("source", track.source)
                        }.toString(),
                    )
                } catch (_: Exception) {
                    // best-effort scrobble; never block playback
                }
            }
        }
    }

    fun toggle(context: Context) {
        consecutiveFailures = 0 // M10: a manual play/retry starts a fresh failure budget.
        val p = player(context)
        when (p.playbackState) {
            // M11: ▶ used to be a no-op at queue end. Replay the current queue item
            // (re-resolves the stream); fall back to seekTo(0)+play if there is no current item.
            Media3Player.STATE_ENDED ->
                if (index in queue.indices) playAt(context, index) else { p.seekTo(0); p.play() }
            // M11: recover after an error (IDLE) — re-prepare via a fresh resolve, then play.
            Media3Player.STATE_IDLE ->
                if (index in queue.indices) playAt(context, index) else { p.prepare(); p.play() }
            else -> if (p.isPlaying) p.pause() else { ensureService(context); p.play() }
        }
    }

    fun next(context: Context) {
        consecutiveFailures = 0 // M10: manual navigation resets the auto-advance failure budget.
        if (index + 1 < queue.size) playAt(context, index + 1)
    }

    fun previous(context: Context) {
        consecutiveFailures = 0 // M10: manual navigation resets the auto-advance failure budget.
        if (index <= 0) seekTo(0) else playAt(context, index - 1)
    }

    fun seekTo(ms: Long) { exo?.seekTo(ms) }
    fun position(): Long = exo?.currentPosition ?: 0
    fun duration(): Long = exo?.duration?.takeIf { it > 0 } ?: (current?.duration ?: 0)
    fun isPlaying(): Boolean = exo?.isPlaying == true

    /** M10: auto-advance past a failing track, but stop (once) after too many consecutive failures. */
    private fun handleTrackFailure(context: Context, reason: String) {
        consecutiveFailures++
        onUiUpdate?.invoke()
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            onError?.invoke("连续 $MAX_CONSECUTIVE_FAILURES 首播放失败，已停止（$reason）")
            return
        }
        if (index + 1 < queue.size) {
            playAt(context.applicationContext, index + 1)
        } else {
            onError?.invoke(reason)
        }
    }

    private fun ensureService(context: Context) {
        if (serviceStarted) return
        val intent = Intent(context, PlaybackService::class.java)
        try {
            context.startForegroundService(intent)
            serviceStarted = true
        } catch (_: Exception) {
            // foreground start not allowed while backgrounded; session still works in-process
        }
    }

    /** M7: called from [PlaybackService.onDestroy] so a later [ensureService] can restart it. */
    fun serviceDestroyed() {
        serviceStarted = false
    }
}

/**
 * H3 (P0): exposes the real [Player.queue]'s next/previous commands to the MediaSession so
 * notification / lockscreen / Bluetooth next·previous actually change tracks and are disabled
 * at the queue ends. ExoPlayer only holds the current item, so the platform *queue count* still
 * reads 1 (P1 — full queue reporting — needs a setMediaItems refactor and is deliberately NOT
 * done here).
 *
 * Safety: we intentionally do NOT override getMediaItemCount / getCurrentMediaItemIndex /
 * getCurrentTimeline / getMediaItemAt. Media3 indexes the timeline carried by player events
 * (ExoPlayer's real single-window timeline) with getCurrentMediaItemIndex(); reporting index N>0
 * against that 1-window timeline would risk an out-of-bounds crash. Keeping those consistent with
 * ExoPlayer while overriding only commands / availability / seek avoids that. The session re-reads
 * getAvailableCommands() on every playback-state rebuild, so end-of-queue availability stays correct.
 */
private class QueueForwardingPlayer(
    private val app: Context,
    player: ExoPlayer,
) : ForwardingPlayer(player) {

    override fun hasNextMediaItem(): Boolean = Player.index + 1 < Player.queue.size
    override fun hasPreviousMediaItem(): Boolean = Player.index > 0
    override fun hasNext(): Boolean = hasNextMediaItem()
    override fun hasPrevious(): Boolean = hasPreviousMediaItem()
    override fun hasNextWindow(): Boolean = hasNextMediaItem()
    override fun hasPreviousWindow(): Boolean = hasPreviousMediaItem()

    override fun seekToNext() { Player.next(app) }
    override fun seekToNextMediaItem() { Player.next(app) }
    override fun seekToNextWindow() { Player.next(app) }
    override fun seekToPrevious() { Player.previous(app) }
    override fun seekToPreviousMediaItem() { Player.previous(app) }
    override fun seekToPreviousWindow() { Player.previous(app) }

    // 0 => "previous" always means the previous track, never "restart current".
    override fun getMaxSeekToPreviousPosition(): Long = 0L

    override fun getAvailableCommands(): Media3Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addIf(Media3Player.COMMAND_SEEK_TO_NEXT, hasNextMediaItem())
            .addIf(Media3Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextMediaItem())
            .addIf(Media3Player.COMMAND_SEEK_TO_PREVIOUS, hasPreviousMediaItem())
            .addIf(Media3Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousMediaItem())
            .build()
}

/** Media3 session service: keeps playback alive and exposes the media session. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        // L16b: don't stack a duplicate MainActivity when the notification is tapped.
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
        // Android 12+ requires startForeground() within ~5s of
        // startForegroundService(), but resolving a stream URL often takes
        // longer and Media3 only posts its notification once playback begins.
        // Post a preparation notification now; Media3 replaces it (same id 1001
        // = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID).
        startPreparationForeground(open)
        // H3: expose the real queue's next/previous commands to the session.
        val sessionPlayer = QueueForwardingPlayer(applicationContext, Player.player(this))
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(open).build()
        // Required: addSession() is what makes MediaSessionService own the media
        // notification and the foreground state. Overriding onGetSession alone
        // does NOT go foreground, which crashes with
        // ForegroundServiceDidNotStartInTimeException once the 5s window expires.
        addSession(session!!)
    }

    private fun startPreparationForeground(contentIntent: PendingIntent) {
        val notification = Notification.Builder(this, CHANNEL)
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
        Player.serviceDestroyed() // M7: allow ensureService() to restart the foreground service later.
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "playback"
    }
}