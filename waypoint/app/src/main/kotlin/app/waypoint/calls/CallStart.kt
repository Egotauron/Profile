package app.waypoint.calls

import android.content.Intent
import android.os.SystemClock

/**
 * Starting the call when WhatsApp doesn't.
 *
 * From the call reports (WhatsApp 2.26.45): for some contacts, WhatsApp answers the voice-call
 * request (the right row, one number) without calling. Once it opened the chat with them; once it
 * just closed, leaving WhatsApp's chat list (or whatever WhatsApp last showed: another chat). So
 * Waypoint's WhatsApp reader makes sure the chat on screen is with the person you tapped (opening
 * it if not) and presses that chat's "Voice call" button, then "Call" if WhatsApp asks to confirm.
 *
 * Nothing is pressed in a chat with anyone else, nor while a call is already going; not "Video
 * call", nor a "Voice call" line in the messages.
 */
object WhatsAppCallStart {
    /** WhatsApp calls by itself within this; after it, Waypoint opens the chat. */
    const val OPEN_CHAT_AFTER_MS = 2_500L

    sealed interface Step {
        data object Done : Step
        data object Wait : Step
        data object OpenChat : Step
        data class Press(val node: ScreenNode, val why: String) : Step
    }

    private val CONFIRM = setOf("call", "voice call", "start call", "start voice call")

    private fun all(n: ScreenNode): Sequence<ScreenNode> = sequenceOf(n) + n.children.asSequence().flatMap(::all)

    /** WhatsApp's call screen (the controls seen in the audio output report). */
    fun isCallScreen(n: ScreenNode): Boolean =
        n.id?.substringAfter(":id/") in setOf("call_controls_card", "end_call_button", "audio_route_button")

    /** Names compared by their letters and digits: "Mum ❤️" is "mum". */
    fun sameName(a: String?, b: String?): Boolean {
        fun key(s: String?) = s.orEmpty().filter { it.isLetterOrDigit() }.lowercase()
        return key(a).isNotEmpty() && key(a) == key(b)
    }

    /** The name at the top of the chat on screen, if a chat is on screen. */
    fun chatWith(screens: List<ScreenNode>): String? =
        screens.asSequence().flatMap(::all).firstOrNull { it.id?.endsWith(":id/conversation_contact_name") == true }?.text

    /**
     * The next step to calling [name]: [elapsedMs] since the request, whether a call is already
     * going, what Waypoint has done so far, and whether it can open their chat.
     */
    fun next(
        screens: List<ScreenNode>,
        name: String,
        elapsedMs: Long,
        callGoing: Boolean,
        openedChat: Boolean,
        pressedVoiceCall: Boolean,
        pressedConfirm: Boolean,
        canOpenChat: Boolean,
    ): Step {
        val nodes = screens.asSequence().flatMap(::all).toList()
        if (callGoing || nodes.any(::isCallScreen)) return Step.Done
        if (!pressedVoiceCall) {
            val chat = chatWith(screens)
            if (sameName(chat, name)) {
                val button = nodes.firstOrNull {
                    it.clickable && CallScreenFormat.isControl(it.className) && it.desc?.trim().equals("Voice call", ignoreCase = true)
                }
                return if (button != null) Step.Press(button, "the chat's Voice call button") else Step.Wait
            }
            // Someone else's chat, or (after a moment) anything else: open theirs, once.
            if (!openedChat && canOpenChat && (chat != null || elapsedMs >= OPEN_CHAT_AFTER_MS)) return Step.OpenChat
            return Step.Wait
        }
        if (!pressedConfirm) {
            val confirm = nodes.firstOrNull {
                it.clickable && CallScreenFormat.isControl(it.className) && it.text?.trim()?.lowercase() in CONFIRM
            }
            if (confirm != null) return Step.Press(confirm, "Call in WhatsApp's confirmation")
        }
        return Step.Wait
    }
}

/** The call being started from the map, which [WhatsAppCallScreen] helps along for a few seconds. */
object CallStart {
    const val WINDOW_MS = 15_000L

    /** Calling [name]; [chat] opens the chat with them. */
    class Attempt(val id: Long, val startedMs: Long, val name: String, val chat: Intent?) {
        @Volatile var openedChat = false
        @Volatile var pressedVoiceCall = false
        @Volatile var pressedConfirm = false
    }

    @Volatile var attempt: Attempt? = null
        private set

    val active: Boolean get() = attempt?.let { SystemClock.elapsedRealtime() - it.startedMs < WINDOW_MS } == true

    fun start(name: String, chat: Intent?): Attempt =
        Attempt(SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime(), name, chat).also { attempt = it }

    fun end() {
        attempt = null
    }
}
