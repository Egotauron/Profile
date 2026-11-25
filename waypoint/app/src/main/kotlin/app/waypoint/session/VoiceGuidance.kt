package app.waypoint.session

import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.SpokenInstruction

/** How much the navigation voice says. */
@Serializable
enum class VoiceMode {
    /** Every instruction. */
    ON,

    /** Only when you leave the road you're on: turns, exits, roundabouts, forks, arriving. */
    QUIET,

    /** Nothing: no speech, and the music is never lowered. */
    OFF,
    ;

    /** The sound button goes on → quiet → off → on. */
    fun next(): VoiceMode = when (this) {
        ON -> QUIET
        QUIET -> OFF
        OFF -> ON
    }
}

/**
 * What's spoken during navigation, in front of Ferrostar's speech.
 *
 * Ferrostar's speech asks for audio focus (lowering your music) before it checks its own mute, and
 * when muted it never speaks, so it never gives the focus back: muted, the music stayed lowered.
 * Here, nothing reaches it when the voice is off, and in quiet mode only road changes do
 * ([QuietRules]). [currentStep] is the route step being driven (whose instructions are spoken).
 */
class VoiceGuidance(
    private val speech: SpokenInstructionObserver,
    private val currentStep: () -> RouteStep?,
) : SpokenInstructionObserver {
    private val _mode = MutableStateFlow(VoiceMode.ON)
    val mode: StateFlow<VoiceMode> = _mode.asStateFlow()

    private val _muted = MutableStateFlow(false)
    override val muteState: StateFlow<Boolean> = _muted.asStateFlow()

    fun setMode(mode: VoiceMode) {
        _mode.value = mode
        _muted.value = mode == VoiceMode.OFF
        // Stops anything being said and gives the music its volume back.
        if (mode == VoiceMode.OFF) speech.stopAndClearQueue()
    }

    override fun setMuted(isMuted: Boolean) = setMode(if (isMuted) VoiceMode.OFF else VoiceMode.ON)

    override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) {
        val say = when (_mode.value) {
            VoiceMode.OFF -> false
            VoiceMode.QUIET -> QuietRules.worthSaying(spokenInstruction, currentStep())
            VoiceMode.ON -> true
        }
        if (say) speech.onSpokenInstructionTrigger(spokenInstruction)
    }

    override fun stopAndClearQueue() = speech.stopAndClearQueue()

    /** Waypoint's own announcements (arrived, get off at the next stop): any mode but off. */
    fun announce(text: String) {
        if (_mode.value != VoiceMode.OFF) speech.onSpokenInstructionTrigger(SpokenInstruction(text, null, 0.0, UUID.randomUUID()))
    }
}

/** What quiet mode says: road changes, once each. */
object QuietRules {
    /**
     * Leaving the road you're on. Continuing, the road changing name, setting off and notices
     * aren't; nor is a "turn" straight on.
     */
    fun isRoadChange(type: ManeuverType?, modifier: ManeuverModifier?): Boolean = when (type) {
        null -> true
        ManeuverType.TURN -> modifier != ManeuverModifier.STRAIGHT
        ManeuverType.ARRIVE, ManeuverType.MERGE, ManeuverType.ON_RAMP, ManeuverType.OFF_RAMP, ManeuverType.FORK,
        ManeuverType.END_OF_ROAD, ManeuverType.ROUNDABOUT, ManeuverType.ROTARY, ManeuverType.ROUNDABOUT_TURN,
        -> true
        ManeuverType.NEW_NAME, ManeuverType.DEPART, ManeuverType.CONTINUE, ManeuverType.NOTIFICATION,
        ManeuverType.EXIT_ROUNDABOUT, ManeuverType.EXIT_ROTARY,
        -> false
    }

    /** Only the last call for a manoeuvre ("Turn left onto …"), not the earlier "In 300 metres, …". */
    fun isLastCall(instruction: SpokenInstruction, stepInstructions: List<SpokenInstruction>): Boolean =
        stepInstructions.isEmpty() ||
            stepInstructions.minByOrNull { it.triggerDistanceBeforeManeuver }?.utteranceId == instruction.utteranceId

    /** A step's instructions announce the manoeuvre at its end, which its banner shows. */
    fun worthSaying(instruction: SpokenInstruction, step: RouteStep?): Boolean {
        if (step == null) return true
        val content = step.visualInstructions.lastOrNull()?.primaryContent
        return isRoadChange(content?.maneuverType, content?.maneuverModifier) && isLastCall(instruction, step.spokenInstructions)
    }
}
