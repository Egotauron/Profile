package app.waypoint.calls

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What to press on WhatsApp's call screen to move the call's audio, decided from the screen as
 * Waypoint's accessibility service reads it.
 *
 * From the call screen report (WhatsApp 2.26.45, no Bluetooth connected): the output control is
 * one button, `audio_route_button`, labelled "Turn speaker phone on" / "Turn speaker phone off";
 * pressing it moved the audio between the phone and the speaker in about 0.3 s. With Bluetooth
 * connected it hasn't been seen yet: it may open a list of outputs or step through them, so a
 * listed output is only pressed when its label clearly names the one wanted, and the button is
 * pressed again only while each press is moving the audio.
 */
object WhatsAppOutput {
    const val MAX_PRESSES = 4

    sealed interface Step {
        data object Done : Step
        data object Wait : Step
        data class Press(val node: ScreenNode, val why: String) : Step
        data class GiveUp(val why: String) : Step
    }

    /** Never pressed, whatever they say: ending the call, the microphone, the camera… */
    private val UNSAFE = Regex(
        "end_call|leave|hang|decline|reject|mute|micro|camera|video|screen_shar|participant|add people|minimi|more_button|more menu",
        RegexOption.IGNORE_CASE,
    )

    private fun words(target: AudioOutput, deviceNames: List<String>): List<Regex> = when (target) {
        AudioOutput.BLUETOOTH -> listOf(Regex("bluetooth", RegexOption.IGNORE_CASE)) +
            deviceNames.filter { it.isNotBlank() }.map { Regex(Regex.escape(it), RegexOption.IGNORE_CASE) }
        AudioOutput.EARPIECE -> listOf(Regex("\\b(phone|earpiece|handset)\\b", RegexOption.IGNORE_CASE))
        AudioOutput.SPEAKER -> listOf(Regex("speaker", RegexOption.IGNORE_CASE))
        AudioOutput.WIRED -> listOf(Regex("headphone|wired|headset", RegexOption.IGNORE_CASE))
        AudioOutput.OTHER -> emptyList()
    }

    private fun ScreenNode.label() = listOfNotNull(id, desc, text).joinToString(" ")

    fun isRouteButton(n: ScreenNode): Boolean = n.id?.endsWith(":id/audio_route_button") == true

    /** The node and the tappable node holding it (itself if tappable), depth first. */
    private fun walk(n: ScreenNode, tappable: ScreenNode?, out: MutableList<Pair<ScreenNode, ScreenNode?>>) {
        val holder = if (n.clickable) n else tappable
        out += n to holder
        n.children.forEach { walk(it, holder, out) }
    }

    /**
     * The next step towards [target], given WhatsApp's windows on screen ([screens]), where the
     * audio is now ([current]), the outputs there are ([available]), how many presses Waypoint has
     * made, and whether the last one moved the audio.
     */
    fun next(
        screens: List<ScreenNode>,
        target: AudioOutput,
        current: AudioOutput?,
        available: Set<AudioOutput>,
        presses: Int,
        lastPressMovedAudio: Boolean,
        deviceNames: List<String> = emptyList(),
    ): Step {
        if (current == target) return Step.Done
        if (target !in available) return Step.GiveUp("${target.label} isn't available to the call")
        if (presses >= MAX_PRESSES) return Step.GiveUp("pressed $presses times without reaching ${target.label}")
        val nodes = mutableListOf<Pair<ScreenNode, ScreenNode?>>().also { list -> screens.forEach { walk(it, null, list) } }
        val route = nodes.firstOrNull { isRouteButton(it.first) }?.let { it.second ?: it.first }

        // A list of outputs, after pressing the route button: the entry naming the target.
        if (presses > 0) {
            val patterns = words(target, deviceNames)
            val option = nodes.firstOrNull { (n, holder) ->
                holder != null && !isRouteButton(n) && holder != route &&
                    !UNSAFE.containsMatchIn(n.label()) && !UNSAFE.containsMatchIn(holder.label()) &&
                    // "Turn speaker phone on" names the speaker, not the phone.
                    !(target == AudioOutput.EARPIECE && n.label().contains("speaker", ignoreCase = true)) &&
                    patterns.any { it.containsMatchIn(listOfNotNull(n.desc, n.text).joinToString(" ")) }
            }
            if (option != null) return Step.Press(option.second!!, "the ${target.label} entry")
        }

        if (route == null) return Step.Wait
        val desc = route.desc.orEmpty()
        val speakerOn = when {
            desc.contains("speaker", ignoreCase = true) && desc.endsWith(" off", ignoreCase = true) -> true
            desc.contains("speaker", ignoreCase = true) && desc.endsWith(" on", ignoreCase = true) -> false
            else -> null
        }
        val bluetooth = AudioOutput.BLUETOOTH in available || AudioOutput.WIRED in available
        if (!bluetooth && speakerOn != null) {
            // Phone and speaker only: the button is a speaker switch (seen on the phone).
            val press = (target == AudioOutput.SPEAKER && !speakerOn) || (target == AudioOutput.EARPIECE && speakerOn)
            return when {
                press && presses == 0 -> Step.Press(route, "the speaker switch")
                press && lastPressMovedAudio -> Step.Press(route, "the speaker switch again")
                else -> Step.Wait
            }
        }
        // With Bluetooth: press once; again only while each press moves the audio (stepping
        // through outputs). A list that doesn't name the target is left alone.
        return when {
            presses == 0 -> Step.Press(route, "the audio output button")
            lastPressMovedAudio -> Step.Press(route, "the audio output button again")
            else -> Step.Wait
        }
    }

    /** The output after [current] among [available]: Bluetooth, headphones, phone, speaker, round. */
    fun cycle(current: AudioOutput?, available: Set<AudioOutput>): AudioOutput? {
        val order = listOf(AudioOutput.BLUETOOTH, AudioOutput.WIRED, AudioOutput.EARPIECE, AudioOutput.SPEAKER).filter { it in available }
        if (order.isEmpty()) return null
        val i = order.indexOf(current)
        return order[(i + 1) % order.size].takeIf { it != current }
    }
}

/**
 * A request from the map to move the call's audio, carried out by [WhatsAppCallScreen] on
 * WhatsApp's call screen; [result] tells the map how it went.
 */
object OutputSwitch {
    const val TIMEOUT_MS = 7_000L

    data class Request(val id: Long, val target: AudioOutput, val startedMs: Long)

    data class Result(val id: Long, val target: AudioOutput, val reached: Boolean, val why: String, val report: String?)

    @Volatile var request: Request? = null
        private set
    val result = MutableStateFlow<Result?>(null)

    @Synchronized
    fun start(target: AudioOutput): Request =
        Request(SystemClock.elapsedRealtime(), target, SystemClock.elapsedRealtime()).also { request = it; result.value = null }

    /** Ends [id]'s request (once): true if this call ended it. */
    @Synchronized
    fun finish(id: Long, reached: Boolean, why: String, report: String?): Boolean {
        val r = request?.takeIf { it.id == id } ?: return false
        request = null
        result.value = Result(id, r.target, reached, why, report)
        return true
    }
}
