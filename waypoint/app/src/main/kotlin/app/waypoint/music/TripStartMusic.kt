package app.waypoint.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import android.view.KeyEvent
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

/** What happens to music when a trip starts (spec §5 "Later": start a chosen playlist). */
@Serializable
enum class TripStartMusic { NOTHING, RESUME, PLAYLIST }

/** A playlist (or album, station...) picked from a player's library. */
@Serializable
data class PlaylistChoice(val packageName: String, val mediaId: String, val title: String)

/** One entry of a player's media library. */
data class LibraryItem(val mediaId: String, val title: String, val subtitle: String?, val browsable: Boolean, val playable: Boolean)

class LibraryUnavailable(message: String) : Exception(message)

/**
 * Talks to a player's MediaBrowserService: browse its library and play an item. Many players
 * only let approved apps browse them (Spotify, for example); then [browse] fails and the trip
 * start falls back to resuming the last player.
 */
class MediaLibrary(private val context: Context) {

    private fun serviceOf(packageName: String): ComponentName? =
        context.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(packageName), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }

    /** Connects, runs [block], disconnects. Null when the player refuses or doesn't answer. */
    private suspend fun <T> withBrowser(packageName: String, block: suspend (MediaBrowserCompat) -> T): T? = withContext(Dispatchers.Main) {
        val component = serviceOf(packageName) ?: return@withContext null
        var browser: MediaBrowserCompat? = null
        val connected = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                browser = MediaBrowserCompat(context, component, object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() { if (cont.isActive) cont.resume(true) }
                    override fun onConnectionFailed() { if (cont.isActive) cont.resume(false) }
                    override fun onConnectionSuspended() { if (cont.isActive) cont.resume(false) }
                }, null)
                browser!!.connect()
            }
        } == true
        try {
            if (connected) block(browser!!) else null
        } finally {
            runCatching { browser?.disconnect() }
        }
    }

    /** Children of [parentId] (the library root when null). */
    suspend fun browse(packageName: String, parentId: String? = null): List<LibraryItem> =
        withBrowser(packageName) { browser ->
            val id = parentId ?: browser.root
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<List<LibraryItem>> { cont ->
                    browser.subscribe(id, object : MediaBrowserCompat.SubscriptionCallback() {
                        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
                            browser.unsubscribe(id)
                            if (cont.isActive) cont.resume(children.map {
                                LibraryItem(
                                    mediaId = it.mediaId.orEmpty(),
                                    title = it.description.title?.toString() ?: "Untitled",
                                    subtitle = it.description.subtitle?.toString(),
                                    browsable = it.isBrowsable,
                                    playable = it.isPlayable,
                                )
                            })
                        }

                        override fun onError(parentId: String) {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    })
                }
            } ?: emptyList()
        } ?: throw LibraryUnavailable("This player doesn't let other apps browse its library. Waypoint will resume your music instead.")

    /** Plays [choice]; false if the player refused. */
    suspend fun play(choice: PlaylistChoice): Boolean = withBrowser(choice.packageName) { browser ->
        val controller = MediaControllerCompat(context, browser.sessionToken)
        controller.transportControls.playFromMediaId(choice.mediaId, null)
        delay(1_000) // give the player a moment before we disconnect
        true
    } == true

    /** Asks [packageName]'s player to resume its last queue; false if it won't take requests. */
    suspend fun resume(packageName: String): Boolean = withBrowser(packageName) { browser ->
        MediaControllerCompat(context, browser.sessionToken).transportControls.play()
        delay(1_000) // give the player a moment before we disconnect
        true
    } == true

    /** Resumes whatever played last (the system sends Play to the most recent media session). */
    fun resumeLast() {
        val am = context.getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
    }
}
