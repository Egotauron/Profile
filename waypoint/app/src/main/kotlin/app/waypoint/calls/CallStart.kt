package app.waypoint.calls

import android.os.SystemClock

/**
 * Starting the call when WhatsApp opens the chat instead.
 *
 * From the call report (WhatsApp 2.26.45): for some contacts, WhatsApp answers the voice-call
 * request (the right row, one number) by opening the chat with them, not calling. The chat's top
 * bar has an ImageButton labelled "Voice call", so Waypoint's WhatsApp reader presses it. Only
 * that button is pressed (not "Video call", nor a "Voice call" line in the messages), and then
 * only a "Call" button in a confirmation, if WhatsApp asks.
 */
object WhatsAppCallStart {
    sealed interface Step {
        data object Done : Step
        data object Wait : Step
        data class Press(val node: ScreenNode, val why: String) : Step
    }

    private val CONFIRM = setOf("call", "voice call", "start call", "start voice call")

    private fun all(n: ScreenNode): Sequence<ScreenNode> = sequenceOf(n) + n.children.asSequence().flatMap(::all)

    /** WhatsApp's call screen (the controls seen in the audio output report). */
    fun isCallScreen(n: ScreenNode): Boolean =
        n.id?.substringAfter(":id/") in setOf("call_controls_card", "end_call_button", "audio_route_button")

    fun next(screens: List<ScreenNode>, pressedVoiceCall: Boolean, pressedConfirm: Boolean): Step {
        val nodes = screens.asSequence().flatMap(::all).toList()
        if (nodes.any(::isCallScreen)) return Step.Done
        if (!pressedVoiceCall) {
            val button = nodes.firstOrNull {
                it.clickable && CallScreenFormat.isControl(it.className) && it.desc?.trim().equals("Voice call", ignoreCase = true)
            }
            return if (button != null) Step.Press(button, "the chat's Voice call button") else Step.Wait
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
    const val WINDOW_MS = 12_000L

    class Attempt(val id: Long, val startedMs: Long) {
        @Volatile var pressedVoiceCall = false
        @Volatile var pressedConfirm = false
    }

    @Volatile var attempt: Attempt? = null
        private set

    val active: Boolean get() = attempt?.let { SystemClock.elapsedRealtime() - it.startedMs < WINDOW_MS } == true

    fun start(): Attempt = Attempt(SystemClock.elapsedRealtime(), SystemClock.elapsedRealtime()).also { attempt = it }

    fun end() {
        attempt = null
    }
}
