package app.waypoint.calls

import android.Manifest
import android.app.ActivityOptions
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
    /** Their WhatsApp numbers, when there are several: you choose which to call. */
    val numbers: List<CallNumber> = emptyList(),
)

/** One of a person's WhatsApp numbers: its voice-call row, and the number to show. */
data class CallNumber(val dataId: Long, val label: String)

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
    /** The notification's buttons, by name (for the audio output report). */
    val actions: List<String> = emptyList(),
)

/** What happened the last time a WhatsApp call was started; shown to relay when calling fails. */
data class CallReport(val failed: Boolean, val text: String, val chat: Intent?, val title: String? = null)

object CallReports {
    val last = MutableStateFlow<CallReport?>(null)
}

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
            actions = actions.map { it.title?.toString().orEmpty() },
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

    /**
     * WhatsApp's call screen (the one Contacts opens) is only open to apps with the Phone
     * permission; without it Android refuses the request ("requires android.permission.CALL_PHONE").
     * Waypoint uses it for nothing else.
     */
    fun hasCallAccess(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    fun whatsAppInstalled(): Boolean = APPS.keys.any { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }

    /** Everyone with WhatsApp: favourites (starred contacts) first, then A–Z. */
    suspend fun contacts(): List<CallContact> = withContext(Dispatchers.IO) {
        if (!hasContactsAccess()) return@withContext emptyList()
        val mimes = APPS.values.toTypedArray()
        // One entry per person and app; a person with several WhatsApp numbers has them all.
        val people = linkedMapOf<Pair<Long, String>, MutableList<Pair<CallContact, String?>>>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf("_id", "contact_id", "display_name", "starred", "photo_thumb_uri", "mimetype", "data1", "data3"),
            "mimetype IN (${mimes.joinToString { "?" }})",
            mimes,
            "starred DESC, display_name COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(5)
                val row = CallContact(
                    dataId = c.getLong(0),
                    name = c.getString(2) ?: continue,
                    starred = c.getInt(3) == 1,
                    photo = c.getString(4)?.toUri(),
                    mimeType = mime,
                    packageName = APPS.entries.first { it.value == mime }.key,
                    contactId = c.getLong(1),
                )
                people.getOrPut(row.contactId to mime) { mutableListOf() } += row to (c.getString(6) + "\u0000" + c.getString(7))
            }
        }
        people.values.map { rows ->
            val numbers = rows.distinctBy { (_, idAndLabel) -> idAndLabel?.substringBefore('\u0000') }
                .map { (row, idAndLabel) -> CallNumber(row.dataId, numberLabel(idAndLabel?.substringBefore('\u0000'), idAndLabel?.substringAfter('\u0000'))) }
            rows.first().first.copy(numbers = if (numbers.size > 1) numbers else emptyList())
        }
    }

    /** "+39 338 123 4508" from WhatsApp's "Voice call +39 338 123 4508", or from its id. */
    private fun numberLabel(jid: String?, label: String?): String =
        label?.let { Regex("""\+?\d[\d \-()]{5,}\d""").find(it)?.value }
            ?: jid?.substringBefore('@')?.takeIf { it.all(Char::isDigit) }?.let { "+$it" }
            ?: label ?: "Number"

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
        val chat = chatRow(contact)?.let { id ->
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id), PROFILES.getValue(contact.packageName))
                .setPackage(contact.packageName)
        }
        // Diagnostic build: what was tried, and how Android and WhatsApp answered (CallReports),
        // with what WhatsApp shows meanwhile when the accessibility reader is on.
        pending = runCatching { diagnose(contact, whatsApp, chat) }.getOrElse { "Couldn't build the report: $it" } to chat
        attempt++
        outcome = null
        recordedCall = callScreenReaderOn()
        if (recordedCall) CallScreenCapture.start(listOf(contact.name))
        LaunchAndReturnActivity.start(context, whatsApp, LaunchAndReturnActivity.PURPOSE_CALL)
    }

    private var pending: Pair<String, Intent?>? = null
    private var attempt = 0
    private var outcome: String? = null
    private var recordedCall = false
    private var reportBase: String? = null

    /** Called by the launcher with how the WhatsApp request went. */
    internal fun attempted(opened: Boolean, error: Throwable?) {
        val (before, chat) = pending ?: ("(no details)" to null)
        val result = if (opened) {
            "Result: WhatsApp accepted the request. If no call started, say what WhatsApp showed."
        } else {
            "Result: FAILED: ${error?.let { "${it.javaClass.name}: ${it.message}" } ?: "nothing opened"}"
        }
        reportBase = "$before\n$result"
        CallReports.last.value = CallReport(failed = !opened, text = reportBase!!, chat = chat)
    }

    /** The last attempt's report, with whether the call started and what WhatsApp showed. */
    fun refreshCallReport(failed: Boolean? = null) {
        val last = CallReports.last.value ?: return
        val base = reportBase ?: return
        val text = buildString {
            append(base)
            outcome?.let { append("\nOutcome: $it") }
            append("\n\nWhat WhatsApp showed:\n")
            append(
                if (recordedCall) CallScreenCapture.report("(recorded by Waypoint's WhatsApp reader)")
                else "not recorded: turn on \"Waypoint: WhatsApp call output\" in Accessibility to include it",
            )
        }
        CallReports.last.value = last.copy(failed = failed ?: last.failed, text = text)
    }

    /** What the report says before the attempt: the phone, WhatsApp, the contact row, who handles it. */
    private fun diagnose(contact: CallContact, call: Intent, chat: Intent?): String = buildString {
        val pm = context.packageManager
        appendLine("Waypoint ${app.waypoint.BuildConfig.VERSION_NAME} call report")
        appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        val wa = runCatching { pm.getPackageInfo(contact.packageName, 0) }.getOrNull()
        appendLine("WhatsApp: ${contact.packageName} ${wa?.versionName ?: "NOT INSTALLED"}")
        appendLine("Contacts access: ${hasContactsAccess()}, Phone permission: ${hasCallAccess()}")
        appendLine("Contact: ${maskName(contact.name)}, contact_id ${contact.contactId}, row _id ${contact.dataId}")
        appendLine("Name: ${contact.name.length} chars; besides letters, digits and spaces: " + contact.name.codePoints().toArray()
            .filterNot { Character.isLetterOrDigit(it) || Character.isSpaceChar(it) }.joinToString(" ") { "U+%04X".format(it) }.ifEmpty { "nothing" })
        appendLine("Numbers offered: ${contact.numbers.size.coerceAtLeast(1)}")
        appendLine("Row: ${row(contact.dataId)}")
        appendLine("WhatsApp rows for this person: ${rowsFor(contact.contactId)}")
        appendLine("Request: ${call.action} ${call.data} type=${call.type} package=${call.`package`}")
        val handlers = runCatching { pm.queryIntentActivities(call, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
        appendLine("Handled by: " + handlers.joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name}" }.ifEmpty { "NOTHING (no WhatsApp screen accepts this request)" })
        val anyCall = Intent(Intent.ACTION_VIEW).setType(contact.mimeType).setPackage(contact.packageName)
        val forType = runCatching { pm.queryIntentActivities(anyCall, PackageManager.MATCH_DEFAULT_ONLY) }.getOrDefault(emptyList())
        appendLine("Handlers for the call type alone: " + forType.joinToString { it.activityInfo.name }.ifEmpty { "none" })
        appendLine("Chat row: ${if (chat != null) "found" else "none"}")
    }

    private fun row(id: Long): String = runCatching {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf("mimetype", "data1", "data2", "data3"), "_id = ?", arrayOf(id.toString()), null,
        )?.use { c ->
            if (!c.moveToFirst()) "not found"
            else "mimetype=${c.getString(0)}, data1=${maskDigits(c.getString(1))}, data2=${c.getString(2)}, data3=${maskDigits(c.getString(3))}"
        } ?: "query returned nothing"
    }.getOrElse { "error: $it" }

    private fun rowsFor(contactId: Long): String = runCatching {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf("_id", "mimetype", "data1", "raw_contact_id"),
            "contact_id = ? AND mimetype LIKE ?", arrayOf(contactId.toString(), "%whatsapp%"), null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) add("${c.getString(1).substringAfterLast("/")} (row ${c.getLong(0)}, raw ${c.getLong(3)}, ${maskDigits(c.getString(2))})")
            }.joinToString().ifEmpty { "none" }
        } ?: "none"
    }.getOrElse { "error: $it" }

    /** First character (a whole emoji, not half of one) of each word. */
    private fun maskName(name: String) = name.split(" ").filter { it.isNotEmpty() }.joinToString(" ") {
        String(Character.toChars(it.codePointAt(0))) + "…"
    }
    private fun maskDigits(s: String?) = s?.replace(Regex("\\d(?=\\d{2})"), "•")

    /** The WhatsApp chat row of the same person in Contacts. */
    private fun chatRow(contact: CallContact): Long? = runCatching {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf("_id"),
            "contact_id = ? AND mimetype = ?", arrayOf(contact.contactId.toString(), PROFILES.getValue(contact.packageName)), null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }.getOrNull()

    /** After a call is placed: back to the map as soon as the call shows up (or give up). */
    internal fun returnToMapWhenConnected(back: () -> Unit) {
        val mine = attempt
        val started = android.os.SystemClock.elapsedRealtime()
        fun seconds() = "%.1f s".format((android.os.SystemClock.elapsedRealtime() - started) / 1000.0)
        scope.launch {
            val up = withTimeoutOrNull(10_000) { liveCall.first { it != null && !it.incoming } }
            if (mine != attempt) return@launch
            if (up != null) {
                outcome = "WhatsApp started the call after ${seconds()}"
                refreshCallReport()
                if (recordedCall) CallScreenCapture.stop()
                delay(1_500)
                runCatching(back)
                return@launch
            }
            // No call: the report opens (it says what WhatsApp showed), unless the call starts late.
            outcome = "NO CALL: WhatsApp hadn't started the call after 10 s"
            refreshCallReport(failed = true)
            val late = withTimeoutOrNull(50_000) { liveCall.first { it != null && !it.incoming } }
            if (late != null && mine == attempt) {
                outcome = "WhatsApp started the call, late, after ${seconds()}"
                refreshCallReport(failed = false)
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

    /**
     * Sends one of the calling app's notification intents. Opening its call screen starts an
     * activity for it, which Android 14 only allows with Waypoint's say-so (it's on screen).
     */
    private fun send(intent: PendingIntent?): Boolean = intent != null && runCatching {
        val options = when {
            Build.VERSION.SDK_INT >= 36 -> ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE).toBundle()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> @Suppress("DEPRECATION") ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
            else -> null
        }
        intent.send(context, 0, null, null, null, null, options)
    }.isSuccess

    // Audio output (diagnostic build): see CallScreen.kt.
    val outputs = AudioOutputs(context)

    /** Where the call's audio goes, as it changes; null where Android can't say (before 12). */
    val output = callbackFlow {
        trySend(outputs.current())
        val stop = outputs.watch { trySend(it) }
        awaitClose(stop)
    }.distinctUntilChanged()

    fun callScreenReaderOn(): Boolean = WhatsAppCallScreen.isOn(context)

    private var stopWatchingOutput: (() -> Unit)? = null

    /**
     * Moves [call]'s audio to [target]: opens WhatsApp's call screen, where [WhatsAppCallScreen]
     * presses its output button and brings the map back; [OutputSwitch.result] says how it went.
     * The screen is recorded meanwhile, for the report if it fails.
     */
    fun switchOutput(call: LiveCall, target: AudioOutput) {
        CallScreenCapture.start(listOfNotNull(call.caller) + outputs.deviceNames())
        CallScreenCapture.note("Switching to ${target.label}. ", "Output before: ${outputs.describe()}")
        stopWatchingOutput?.invoke()
        stopWatchingOutput = outputs.watch { CallScreenCapture.note("Output changed to ${it?.label ?: "none"}") }
        val request = OutputSwitch.start(target)
        open(call)
        scope.launch {
            // Nothing happened on WhatsApp's screen (it didn't come up, or the reader is off).
            delay(OutputSwitch.TIMEOUT_MS + 1_500)
            finishOutputSwitch(request.id, reached = outputs.current() == target, why = "WhatsApp's call screen didn't come up, or nothing could be pressed on it")
        }
    }

    /** Ends a switch (once): the result, with the report if the audio didn't get there. */
    internal fun finishOutputSwitch(id: Long, reached: Boolean, why: String): Boolean {
        if (OutputSwitch.request?.id != id) return false
        val report = outputReport(CallNotifications.current.value, if (reached) null else why)
        return OutputSwitch.finish(id, reached, why, report.takeUnless { reached })
    }

    private fun outputReport(call: LiveCall?, failure: String?): String {
        stopWatchingOutput?.invoke()
        stopWatchingOutput = null
        CallScreenCapture.note("", "Output after: ${outputs.describe()}")
        CallScreenCapture.stop()
        val pm = context.packageManager
        val pkg = call?.packageName
        val version = pkg?.let { runCatching { pm.getPackageInfo(it, 0).versionName }.getOrNull() }
        val header = buildString {
            appendLine("Waypoint ${app.waypoint.BuildConfig.VERSION_NAME} audio output report")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Call app: ${pkg ?: "unknown"} ${version ?: ""}")
            failure?.let { appendLine("Result: FAILED: $it") }
            append("Call notification buttons: ${call?.actions?.joinToString { "\"$it\"" }?.ifEmpty { "none" } ?: "no notification"}")
        }
        return CallScreenCapture.report(header)
    }

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
