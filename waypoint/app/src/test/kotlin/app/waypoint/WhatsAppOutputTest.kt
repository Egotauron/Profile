package app.waypoint

import app.waypoint.calls.AudioOutput.BLUETOOTH
import app.waypoint.calls.AudioOutput.EARPIECE
import app.waypoint.calls.AudioOutput.SPEAKER
import app.waypoint.calls.ScreenNode
import app.waypoint.calls.WhatsAppOutput
import app.waypoint.calls.WhatsAppOutput.Step
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Moving a WhatsApp call's audio by pressing WhatsApp's own button, on its real call screen. */
class WhatsAppOutputTest {
    private fun button(id: String, desc: String) = ScreenNode("android.widget.Button", "com.whatsapp:id/$id", desc, null, clickable = true)

    /** WhatsApp 2.26.45's call screen, from the report sent from the phone (no Bluetooth). */
    private fun callScreen(speakerOn: Boolean) = ScreenNode(
        "android.widget.FrameLayout", "com.whatsapp:id/action_bar_root", null, null,
        children = listOf(
            ScreenNode(
                "android.view.ViewGroup", "com.whatsapp:id/call_screen_header_view", null, null,
                children = listOf(button("minimize_btn", "Minimise call"), button("participant_btn", "Add people to this call")),
            ),
            ScreenNode(
                "android.view.ViewGroup", "com.whatsapp:id/call_controls_card", null, null,
                children = listOf(
                    button("audio_route_button", if (speakerOn) "Turn speaker phone off" else "Turn speaker phone on"),
                    button("camera_button", "Camera"),
                    button("mute_button", "Microphone"),
                    ScreenNode("android.widget.TextView", "com.whatsapp:id/audio_route_text", null, "Speaker"),
                    button("more_button", "More menu"),
                    button("screen_sharing_button", "Start screen sharing"),
                    button("end_call_button", "Leave call"),
                    ScreenNode("android.widget.TextView", "com.whatsapp:id/end_call_text", null, "End"),
                ),
            ),
        ),
    )

    private val phoneAndSpeaker = setOf(EARPIECE, SPEAKER)

    private fun pressed(step: Step): ScreenNode = assertIs<Step.Press>(step).node

    @Test
    fun phoneToSpeakerPressesTheSpeakerSwitchOnce() {
        val step = WhatsAppOutput.next(listOf(callScreen(speakerOn = false)), SPEAKER, EARPIECE, phoneAndSpeaker, 0, false)
        assertEquals("com.whatsapp:id/audio_route_button", pressed(step).id)
        // Pressed, the audio hasn't moved yet: wait rather than press again (it would switch back).
        assertEquals(Step.Wait, WhatsAppOutput.next(listOf(callScreen(speakerOn = true)), SPEAKER, EARPIECE, phoneAndSpeaker, 1, false))
        assertEquals(Step.Done, WhatsAppOutput.next(listOf(callScreen(speakerOn = true)), SPEAKER, SPEAKER, phoneAndSpeaker, 1, true))
    }

    @Test
    fun speakerToPhonePressesItWhenItSaysTurnOff() {
        assertEquals("Turn speaker phone off", pressed(WhatsAppOutput.next(listOf(callScreen(true)), EARPIECE, SPEAKER, phoneAndSpeaker, 0, false)).desc)
        // The screen says the speaker is already off: nothing to press.
        assertEquals(Step.Wait, WhatsAppOutput.next(listOf(callScreen(false)), EARPIECE, SPEAKER, phoneAndSpeaker, 0, false))
    }

    @Test
    fun waitsForTheCallScreenAndGivesUpOnWhatIsntThere() {
        assertEquals(Step.Wait, WhatsAppOutput.next(emptyList(), SPEAKER, EARPIECE, phoneAndSpeaker, 0, false))
        assertIs<Step.GiveUp>(WhatsAppOutput.next(listOf(callScreen(false)), BLUETOOTH, EARPIECE, phoneAndSpeaker, 0, false))
        assertIs<Step.GiveUp>(WhatsAppOutput.next(listOf(callScreen(false)), SPEAKER, EARPIECE, phoneAndSpeaker, WhatsAppOutput.MAX_PRESSES, true))
    }

    @Test
    fun withBluetoothAListedOutputIsOnlyPressedWhenItNamesTheTarget() {
        val all = setOf(BLUETOOTH, EARPIECE, SPEAKER)
        // First press opens whatever WhatsApp shows with Bluetooth on.
        assertEquals("com.whatsapp:id/audio_route_button", pressed(WhatsAppOutput.next(listOf(callScreen(false)), BLUETOOTH, EARPIECE, all, 0, false)).id)
        val sheet = ScreenNode(
            "android.widget.FrameLayout", null, null, null,
            children = listOf(
                ScreenNode("android.widget.LinearLayout", null, null, null, clickable = true, children = listOf(ScreenNode("TextView", null, null, "Phone"))),
                ScreenNode("android.widget.LinearLayout", "com.whatsapp:id/row_bt", null, null, clickable = true, children = listOf(ScreenNode("TextView", null, null, "Ed's AirPods"))),
                ScreenNode("android.widget.LinearLayout", null, null, null, clickable = true, children = listOf(ScreenNode("TextView", null, null, "Speaker"))),
            ),
        )
        val screens = listOf(callScreen(false), sheet)
        assertEquals("com.whatsapp:id/row_bt", pressed(WhatsAppOutput.next(screens, BLUETOOTH, EARPIECE, all, 1, false, listOf("Ed's AirPods"))).id)
        val phone = pressed(WhatsAppOutput.next(screens, EARPIECE, BLUETOOTH, all, 1, false))
        assertEquals("Phone", phone.children.single().text)
        // A list that doesn't name the target: nothing pressed (not the route button again either).
        assertEquals(Step.Wait, WhatsAppOutput.next(listOf(callScreen(false), sheet), BLUETOOTH, EARPIECE, all, 1, false, listOf("Car kit")))
    }

    @Test
    fun withBluetoothSteppingButtonIsPressedAgainOnlyWhileItMovesTheAudio() {
        val all = setOf(BLUETOOTH, EARPIECE, SPEAKER)
        assertIs<Step.Press>(WhatsAppOutput.next(listOf(callScreen(false)), SPEAKER, BLUETOOTH, all, 1, lastPressMovedAudio = true))
        assertEquals(Step.Wait, WhatsAppOutput.next(listOf(callScreen(false)), SPEAKER, BLUETOOTH, all, 1, lastPressMovedAudio = false))
    }

    @Test
    fun neverPressesEndMuteOrCameraEvenIfTheyNameTheTarget() {
        val trap = ScreenNode(
            "Frame", null, null, null,
            children = listOf(
                button("end_call_button", "Leave call and switch to phone"),
                button("mute_button", "Mute speaker"),
                ScreenNode("Row", null, "Camera bluetooth", null, clickable = true),
            ),
        )
        for (target in listOf(EARPIECE, SPEAKER, BLUETOOTH)) {
            val step = WhatsAppOutput.next(listOf(trap), target, null, setOf(BLUETOOTH, EARPIECE, SPEAKER), 1, false, listOf("bluetooth"))
            assertEquals(Step.Wait, step, "target $target")
        }
    }

    @Test
    fun theButtonStepsThroughTheOutputsThereAre() {
        assertEquals(SPEAKER, WhatsAppOutput.cycle(EARPIECE, phoneAndSpeaker))
        assertEquals(EARPIECE, WhatsAppOutput.cycle(SPEAKER, phoneAndSpeaker))
        val all = setOf(BLUETOOTH, EARPIECE, SPEAKER)
        assertEquals(EARPIECE, WhatsAppOutput.cycle(BLUETOOTH, all))
        assertEquals(SPEAKER, WhatsAppOutput.cycle(EARPIECE, all))
        assertEquals(BLUETOOTH, WhatsAppOutput.cycle(SPEAKER, all))
        assertEquals(BLUETOOTH, WhatsAppOutput.cycle(null, all))
        assertNull(WhatsAppOutput.cycle(EARPIECE, setOf(EARPIECE)))
        assertTrue(WhatsAppOutput.cycle(null, emptySet()) == null)
    }
}
