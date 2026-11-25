package app.waypoint

import app.waypoint.session.QuietRules
import app.waypoint.session.VoiceGuidance
import app.waypoint.session.VoiceMode
import com.stadiamaps.ferrostar.core.SpokenInstructionObserver
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import uniffi.ferrostar.ManeuverModifier
import uniffi.ferrostar.ManeuverType
import uniffi.ferrostar.RouteStep
import uniffi.ferrostar.SpokenInstruction
import uniffi.ferrostar.VisualInstruction
import uniffi.ferrostar.VisualInstructionContent

class VoiceGuidanceTest {
    /** Stands in for Ferrostar's speech: records what it's asked to say and to stop. */
    private class Speech : SpokenInstructionObserver {
        val said = mutableListOf<String>()
        var stops = 0
        override fun onSpokenInstructionTrigger(spokenInstruction: SpokenInstruction) { said += spokenInstruction.text }
        override fun stopAndClearQueue() { stops++ }
        override fun setMuted(isMuted: Boolean) = error("the guidance never mutes Ferrostar's speech")
        override val muteState: StateFlow<Boolean> = MutableStateFlow(false)
    }

    private fun said(text: String, at: Double) = SpokenInstruction(text, null, at, UUID.randomUUID())

    private fun step(type: ManeuverType, modifier: ManeuverModifier?, spoken: List<SpokenInstruction>) = RouteStep(
        geometry = emptyList(), distance = 300.0, duration = 60.0, roadName = "King's Road", exits = emptyList(),
        instruction = "", visualInstructions = listOf(
            VisualInstruction(VisualInstructionContent("Sloane Street", type, modifier, null, null, emptyList()), null, null, 300.0),
        ),
        spokenInstructions = spoken, annotations = null, incidents = emptyList(), drivingSide = null, roundaboutExitNumber = null,
    )

    @Test
    fun offPassesNothingOnAndGivesTheMusicBack() {
        val speech = Speech()
        val voice = VoiceGuidance(speech) { null }
        voice.setMode(VoiceMode.OFF)
        assertEquals(1, speech.stops, "stopping also releases audio focus")
        voice.onSpokenInstructionTrigger(said("Turn left onto Sloane Street.", 30.0))
        voice.announce("Arrived at the Ashmolean.")
        assertEquals(emptyList(), speech.said, "nothing reaches the speech, so the music is never lowered")
        assertTrue(voice.isMuted)
    }

    @Test
    fun quietSaysTheLastCallForARoadChange() {
        val early = said("In 300 metres, Turn left onto Sloane Street.", 300.0)
        val last = said("Turn left onto Sloane Street.", 30.0)
        val speech = Speech()
        val voice = VoiceGuidance(speech) { step(ManeuverType.TURN, ManeuverModifier.LEFT, listOf(early, last)) }
        voice.setMode(VoiceMode.QUIET)
        voice.onSpokenInstructionTrigger(early)
        voice.onSpokenInstructionTrigger(last)
        assertEquals(listOf("Turn left onto Sloane Street."), speech.said)
    }

    @Test
    fun quietSkipsContinuingOnTheSameRoad() {
        val cont = said("Continue on King's Road for 2 kilometres.", 30.0)
        val speech = Speech()
        val voice = VoiceGuidance(speech) { step(ManeuverType.CONTINUE, ManeuverModifier.STRAIGHT, listOf(cont)) }
        voice.setMode(VoiceMode.QUIET)
        voice.onSpokenInstructionTrigger(cont)
        voice.announce("Get off at the next stop, Aldwych.")
        assertEquals(listOf("Get off at the next stop, Aldwych."), speech.said, "trip alerts still speak")
    }

    @Test
    fun onSaysEverything() {
        val speech = Speech()
        val voice = VoiceGuidance(speech) { step(ManeuverType.CONTINUE, null, emptyList()) }
        voice.onSpokenInstructionTrigger(said("In 300 metres, Turn left.", 300.0))
        voice.onSpokenInstructionTrigger(said("Continue for 2 kilometres.", 30.0))
        assertEquals(2, speech.said.size)
    }

    @Test
    fun whatCountsAsARoadChange() {
        for (t in listOf(ManeuverType.ARRIVE, ManeuverType.OFF_RAMP, ManeuverType.ON_RAMP, ManeuverType.ROUNDABOUT, ManeuverType.FORK, ManeuverType.MERGE, ManeuverType.END_OF_ROAD)) {
            assertTrue(QuietRules.isRoadChange(t, null), "$t")
        }
        assertTrue(QuietRules.isRoadChange(ManeuverType.TURN, ManeuverModifier.SLIGHT_RIGHT))
        assertFalse(QuietRules.isRoadChange(ManeuverType.TURN, ManeuverModifier.STRAIGHT))
        for (t in listOf(ManeuverType.CONTINUE, ManeuverType.NEW_NAME, ManeuverType.DEPART, ManeuverType.NOTIFICATION, ManeuverType.EXIT_ROUNDABOUT)) {
            assertFalse(QuietRules.isRoadChange(t, null), "$t")
        }
    }

    @Test
    fun theButtonGoesOnQuietOff() {
        assertEquals(listOf(VoiceMode.QUIET, VoiceMode.OFF, VoiceMode.ON), listOf(VoiceMode.ON.next(), VoiceMode.QUIET.next(), VoiceMode.OFF.next()))
    }
}
