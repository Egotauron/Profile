package app.waypoint.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import app.waypoint.LaunchAndReturnActivity
import app.waypoint.calls.CallNotifications
import androidx.core.app.NotificationManagerCompat
import app.waypoint.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Notification access: lets the music controls see the active player, and the call controls see
 * the call in progress (its notification, with its answer and hang-up buttons). Only call
 * notifications are looked at; nothing is stored or leaves the phone.
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() = publishCall()
    override fun onListenerDisconnected() { CallNotifications.current.value = null }
    override fun onNotificationPosted(sbn: StatusBarNotification) { if (CallNotifications.isCall(sbn)) publishCall() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (sbn.notification.category == Notification.CATEGORY_CALL) publishCall() }

    private fun publishCall() {
        val call = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter(CallNotifications::isCall)
            .maxByOrNull { it.postTime }
        CallNotifications.current.value = call?.let { CallNotifications.toLiveCall(it, appLabel(it.packageName)) }
    }

    private fun appLabel(pkg: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}

data class NowPlaying(
    val packageName: String,
    val appName: String,
    val title: String?,
    val artist: String?,
    val art: Bitmap?,
    val isPlaying: Boolean,
)

data class MusicApp(val packageName: String, val name: String)

/**
 * The music shortcut (spec §5): follows the active player through MediaSession and offers
 * play/pause and next. Without notification access it just opens your chosen player.
 */
class MusicController(private val context: Context, private val settings: SettingsStore) {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _hasAccess = MutableStateFlow(hasAccess())
    val access: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private var active: MediaController? = null
    private var registered = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = pickActive(currentControllers())
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickActive(controllers.orEmpty())
    }

    fun hasAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** Call when the app comes to the foreground (access may have been granted in Settings). */
    fun refreshAccess() {
        _hasAccess.value = hasAccess()
        if (_hasAccess.value && !registered) {
            runCatching {
                sessions.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
                registered = true
                pickActive(currentControllers())
            }
        }
    }

    private fun currentControllers(): List<MediaController> =
        runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList())

    private fun pickActive(controllers: List<MediaController>) {
        val best = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        if (best?.sessionToken != active?.sessionToken) {
            active?.unregisterCallback(controllerCallback)
            active = best
            best?.registerCallback(controllerCallback, main)
        }
        refresh()
    }

    private fun refresh() {
        val c = active ?: run { _nowPlaying.value = null; return }
        lastPlayer = c.packageName
        val md = c.metadata
        _nowPlaying.value = NowPlaying(
            packageName = c.packageName,
            appName = appName(c.packageName),
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    /** [fromCar]: never opens the player on the phone (the driver is looking at the car screen). */
    fun playPause(fromCar: Boolean = false) {
        val c = active ?: return run { scope.launch { startMusic(allowOpening = !fromCar) } }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastPlayer: String? = null

    private val _starting = MutableStateFlow(false)
    /** True while [startMusic] is getting a player going. */
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    /**
     * Play, even when no player is open, without leaving the map if possible:
     * 1. ask your player (the default one, else the last one seen) through its media service to
     *    play, or to "play something" ([MediaLibrary.resume]);
     * 2. with notification access, send Play the way headphones do (resumes the last player) and
     *    check that music started;
     * 3. last resort ([allowOpening]): open the player, press Play once it's up, and come back to
     *    the map ([playThenReturn]). Some players (Apple Music, Spotify) only take requests from
     *    approved apps, so they end up here.
     */
    suspend fun startMusic(allowOpening: Boolean = true) {
        active?.let { it.transportControls.play(); return }
        if (_starting.value) return
        _starting.value = true
        try {
            val pkg = settings.current.defaultMusicPackage ?: lastPlayer
            if (pkg != null && library.resume(pkg)) return
            if (hasAccess()) {
                library.resumeLast()
                if (playingWithin(2_500)) return
            }
            if (allowOpening) {
                val target = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
                    ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                runCatching {
                    LaunchAndReturnActivity.start(context, target, LaunchAndReturnActivity.PURPOSE_MUSIC, failedNote = "Couldn't open your music player")
                }
            }
        } finally {
            _starting.value = false
        }
    }

    /**
     * After opening [pkg]'s player: once its media session shows up, press Play if it isn't
     * playing, and when music plays, go back to the map. Needs notification access to see the
     * player; without it you press Play in the player yourself.
     */
    internal fun playThenReturn(pkg: String?, back: () -> Unit) {
        if (!hasAccess()) return
        scope.launch {
            val playing = withTimeoutOrNull(15_000) {
                _nowPlaying.first { it != null && (pkg == null || it.packageName == pkg) }
                repeat(3) {
                    if (_nowPlaying.value?.isPlaying == true) return@repeat
                    (currentControllers().firstOrNull { pkg == null || it.packageName == pkg } ?: active)?.transportControls?.play()
                    if (playingWithin(2_000)) return@repeat
                }
                _nowPlaying.first { it?.isPlaying == true }
            }
            if (playing != null) {
                delay(800)
                runCatching(back)
            }
        }
    }

    /** Plays [choice] from the player's library (the map's music browser). */
    fun play(choice: PlaylistChoice) {
        scope.launch {
            _starting.value = true
            try { if (!library.play(choice)) library.resumeLast() } finally { _starting.value = false }
        }
    }

    private suspend fun playingWithin(ms: Long): Boolean =
        withTimeoutOrNull(ms) { _nowPlaying.first { it?.isPlaying == true } } != null

    fun next() {
        active?.transportControls?.skipToNext()
    }

    fun previous() {
        active?.transportControls?.skipToPrevious()
    }

    /** The player shown on the music button: what's playing, else your default player. */
    fun currentPlayerPackage(): String? = _nowPlaying.value?.packageName ?: settings.current.defaultMusicPackage

    fun playerName(pkg: String?): String? = pkg?.let { appName(it) }

    private val icons = mutableMapOf<String, android.graphics.Bitmap?>()

    /** The player's launcher icon, for the round music button. */
    fun appIcon(pkg: String?): android.graphics.Bitmap? = pkg?.let { p ->
        icons.getOrPut(p) {
            runCatching { androidx.core.graphics.drawable.DrawableCompat.wrap(context.packageManager.getApplicationIcon(p)).let { d ->
                val size = 96
                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, size, size)
                d.draw(canvas)
                bmp
            } }.getOrNull()
        }
    }

    /** Opens the chosen player (or the system's music app). */
    fun launchPlayer(packageName: String? = settings.current.defaultMusicPackage) {
        val pm = context.packageManager
        val intent = packageName?.let { pm.getLaunchIntentForPackage(it) }
            ?: Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Players installed on the phone, for "long-press to switch player". */
    fun installedPlayers(): List<MusicApp> {
        val pm = context.packageManager
        val fromCategory = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
            .map { it.activityInfo.packageName }
        val fromBrowsers = pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), PackageManager.GET_RESOLVED_FILTER)
            .map { it.serviceInfo.packageName }
        return (fromCategory + fromBrowsers).distinct()
            .filter { it != context.packageName && pm.getLaunchIntentForPackage(it) != null }
            .map { MusicApp(it, appName(it)) }
            .sortedBy { it.name.lowercase() }
    }

    private fun appName(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    val library = MediaLibrary(context)

    /** Called when a trip starts (spec §5 "Later": start a chosen playlist when a trip starts). */
    suspend fun onTripStart() {
        val s = settings.current
        when (s.tripStartMusic) {
            TripStartMusic.NOTHING -> Unit
            TripStartMusic.RESUME -> if (_nowPlaying.value?.isPlaying != true) library.resumeLast()
            TripStartMusic.PLAYLIST -> {
                val choice = s.tripStartPlaylist
                val played = choice != null && runCatching { library.play(choice) }.getOrDefault(false)
                if (!played && _nowPlaying.value?.isPlaying != true) library.resumeLast()
            }
        }
    }

    companion object {
        fun notificationAccessIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
