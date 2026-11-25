package app.waypoint.calls

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import app.waypoint.LaunchAndReturnActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Someone you can call on WhatsApp (a row WhatsApp adds to your contacts). */
data class CallContact(
    val dataId: Long,
    val name: String,
    val starred: Boolean,
    val photo: Uri?,
    val mimeType: String,
    val packageName: String,
    /** The person in Contacts, to find their WhatsApp chat if the call can't start. */
    val contactId: Long = 0,
)

/**
 * A call in progress or ringing, from the calling app's call notification (WhatsApp, the phone
 * app). [answer], [decline] and [hangUp] are that notification's own buttons.
 */
data class LiveCall(
    val packageName: String?,
    val appName: String?,
    val caller: String?,
    val incoming: Boolean,
    /** When the call connected (epoch ms), for the timer; null while ringing or unknown. */
    val since: Long?,
    val answer: PendingIntent? = null,
    val decline: PendingIntent? = null,
    val hangUp: PendingIntent? = null,
    val open: PendingIntent? = null,
)

/** The newest call notification, posted by [app.waypoint.music.MediaListenerService]. */
object CallNotifications {
    val current = MutableStateFlow<LiveCall?>(null)

    // Notification.EXTRA_CALL_* (Android 12 CallStyle), as strings so older phones compile them too.
    private const val CALL_TYPE = "android.callType"
    private const val CALL_PERSON = "android.callPerson"
    private const val ANSWER = "android.answerIntent"
    private const val DECLINE = "android.declineIntent"
    private const val HANG_UP = "android.hangUpIntent"
    private const val TYPE_INCOMING = 1

    /** A call that's ringing or ongoing (not a missed-call notice). */
    fun isCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return n.category == Notification.CATEGORY_CALL &&
            (n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.extras.containsKey(CALL_TYPE))
    }

    fun toLiveCall(sbn: StatusBarNotification, appName: String?): LiveCall {
        val n = sbn.notification
        val x = n.extras
        val actions = n.actions.orEmpty()
        fun action(vararg words: String): PendingIntent? =
            actions.firstOrNull { a -> words.any { a.title?.toString()?.contains(it, ignoreCase = true) == true } }?.actionIntent
        val person = BundleCompat.getParcelable(x, CALL_PERSON, android.app.Person::class.java)?.name?.toString()
        val answer = BundleCompat.getParcelable(x, ANSWER, PendingIntent::class.java) ?: action("answer", "accept")
        val incoming = x.getInt(CALL_TYPE, 0) == TYPE_INCOMING || (answer != null && !x.containsKey(CALL_TYPE))
        return LiveCall(
            packageName = sbn.packageName,
            appName = appName,
            caller = person ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            incoming = incoming,
            since = if (!incoming && x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) n.`when` else null,
            answer = if (incoming) answer else null,
            decline = BundleCompat.getParcelable(x, DECLINE, PendingIntent::class.java) ?: action("decline", "reject"),
            hangUp = BundleCompat.getParcelable(x, HANG_UP, PendingIntent::class.java) ?: action("hang up", "end call"),
            open = n.contentIntent,
        )
    }
}

/**
 * WhatsApp calls from the map, and the controls of the call in progress. Calls start through the
 * voice-call rows WhatsApp adds to your contacts, so they need contacts access; the call in
 * progress is read from its notification (the same notification access as the music controls),
 * or, without it, from the phone being in call mode.
 */
class CallController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audio = context.getSystemService(AudioManager::class.java)

    /** The phone is in a call (any app), when notifications can't tell us more. */
    private val inCallMode = callbackFlow {
        fun inCall() = audio.mode in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)
        trySend(inCall())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { trySend(inCall()) }
            audio.addOnModeChangedListener(ContextCompat.getMainExecutor(context), listener)
            awaitClose { audio.removeOnModeChangedListener(listener) }
        } else {
            val poll = launch { while (true) { delay(2_000); trySend(inCall()) } }
            awaitClose { poll.cancel() }
        }
    }.distinctUntilChanged()

    /** The call in progress or ringing; only watched while a screen shows it. */
    val liveCall: StateFlow<LiveCall?> = combine(CallNotifications.current, inCallMode) { fromNotification, inCall ->
        fromNotification ?: if (inCall) LiveCall(null, null, null, incoming = audio.mode == AudioManager.MODE_RINGTONE, since = null) else null
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    fun hasContactsAccess(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun whatsAppInstalled(): Boolean = APPS.keys.any { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }

    /** Everyone with WhatsApp: favourites (starred contacts) first, then A–Z. */
    suspend fun contacts(): List<CallContact> = withContext(Dispatchers.IO) {
        if (!hasContactsAccess()) return@withContext emptyList()
        val mimes = APPS.values.toTypedArray()
        val rows = mutableListOf<CallContact>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf("_id", "contact_id", "display_name", "starred", "photo_thumb_uri", "mimetype"),
            "mimetype IN (${mimes.joinToString { "?" }})",
            mimes,
            "starred DESC, display_name COLLATE NOCASE ASC",
        )?.use { c ->
            val seen = mutableSetOf<Pair<Long, String>>()
            while (c.moveToNext()) {
                val mime = c.getString(5)
                // One row per person and app, even with several numbers.
                if (!seen.add(c.getLong(1) to mime)) continue
                rows += CallContact(
                    dataId = c.getLong(0),
                    name = c.getString(2) ?: continue,
                    starred = c.getInt(3) == 1,
                    photo = c.getString(4)?.toUri(),
                    mimeType = mime,
                    packageName = APPS.entries.first { it.value == mime }.key,
                    contactId = c.getLong(1),
                )
            }
        }
        rows
    }

    /** A contact's photo, small enough for the list. */
    suspend fun photo(contact: CallContact): Bitmap? = withContext(Dispatchers.IO) {
        contact.photo?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() }
    }

    /** The photo of the contact called [name] (the caller of a live call), if there is one. */
    suspend fun photoFor(name: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!hasContactsAccess()) return@withContext null
        val uri = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf("photo_thumb_uri"), "display_name = ?", arrayOf(name), null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@withContext null
        runCatching { context.contentResolver.openInputStream(uri.toUri())?.use(BitmapFactory::decodeStream) }.getOrNull()
    }

    /**
     * Starts a WhatsApp voice call. WhatsApp shows its call screen; once the call is up, Waypoint
     * tries to come back to the map (Android may not allow that, then switch back yourself).
     */
    fun call(contact: CallContact) {
        val whatsApp = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, contact.dataId), contact.mimeType)
            .setPackage(contact.packageName)
        // If WhatsApp won't take the call request, their chat (one tap from a call) instead.
        val chat = chatRow(contact)?.let { id ->
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id), PROFILES.getValue(contact.packageName))
                .setPackage(contact.packageName)
        }
        LaunchAndReturnActivity.start(
            context, whatsApp, LaunchAndReturnActivity.PURPOSE_CALL,
            fallbacks = listOfNotNull(chat),
            fallbackNote = "WhatsApp didn't start the call, so it's open on ${contact.name}'s chat",
            failedNote = "Couldn't open WhatsApp for ${contact.name}",
        )
    }

    /** The WhatsApp chat row of the same person in Contacts. */
    private fun chatRow(contact: CallContact): Long? = runCatching {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf("_id"),
            "contact_id = ? AND mimetype = ?", arrayOf(contact.contactId.toString(), PROFILES.getValue(contact.packageName)), null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    /** After a call is placed: back to the map as soon as the call shows up (or give up). */
    internal fun returnToMapWhenConnected(back: () -> Unit) {
        scope.launch {
            val up = withTimeoutOrNull(8_000) { liveCall.first { it != null && !it.incoming } }
            if (up != null) {
                delay(1_500)
                runCatching(back)
            }
        }
    }

    fun answer(call: LiveCall) = send(call.answer)
    fun decline(call: LiveCall) = send(call.decline ?: call.hangUp)
    fun hangUp(call: LiveCall) = send(call.hangUp ?: call.decline)
    fun open(call: LiveCall) {
        if (!send(call.open)) call.packageName?.let { context.packageManager.getLaunchIntentForPackage(it) }
            ?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun send(intent: PendingIntent?): Boolean = intent != null && runCatching { intent.send() }.isSuccess

    companion object {
        /** WhatsApp and WhatsApp Business, with the contacts row type each uses for voice calls. */
        val APPS = linkedMapOf(
            "com.whatsapp" to "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
            "com.whatsapp.w4b" to "vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call",
        )

        /** The contacts row type each uses for "message" (the chat). */
        private val PROFILES = mapOf(
            "com.whatsapp" to "vnd.android.cursor.item/vnd.com.whatsapp.profile",
            "com.whatsapp.w4b" to "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile",
        )
    }
}
