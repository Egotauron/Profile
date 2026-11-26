package app.waypoint

import app.waypoint.calls.ScreenNode
import app.waypoint.calls.WhatsAppCallStart
import app.waypoint.calls.WhatsAppCallStart.Step
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

/** When WhatsApp opens the chat instead of calling, its Voice call button is pressed. */
class WhatsAppCallStartTest {
    private fun node(cls: String, id: String? = null, desc: String? = null, text: String? = null, clickable: Boolean = false, children: List<ScreenNode> = emptyList()) =
        ScreenNode("android.widget.$cls", id?.let { "com.whatsapp:id/$it" }, desc, text, clickable = clickable, children = children)

    /** The chat WhatsApp opened, from the call report (WhatsApp 2.26.45). */
    private val chat = node(
        "FrameLayout", "action_bar_root",
        children = listOf(
            // A "Voice call" line in the messages comes first: never pressed.
            node("ListView", "list", children = listOf(node("ViewGroup", "conversation_row_text", clickable = true, children = listOf(node("TextView", "message_text", text = "Voice call"))))),
            node(
                "ViewGroup", "toolbar",
                children = listOf(
                    node("Button", "whatsapp_toolbar_home", desc = "Back", clickable = true),
                    node("LinearLayout", "conversation_contact", clickable = true, children = listOf(node("TextView", "conversation_contact_name", text = "M"))),
                    node("ImageButton", desc = "Video call", clickable = true),
                    node("ImageButton", desc = "Voice call", clickable = true),
                    node("ImageView", "menuitem_overflow", desc = "More options", clickable = true),
                ),
            ),
            node("ImageButton", "voice_note_btn", desc = "Voice message, Button. Double tap and hold to record"),
        ),
    )

    @Test
    fun theChatsVoiceCallButtonIsPressed() {
        val pressed = assertIs<Step.Press>(WhatsAppCallStart.next(listOf(chat), pressedVoiceCall = false, pressedConfirm = false)).node
        assertEquals("android.widget.ImageButton" to "Voice call", pressed.className to pressed.desc)
        // Once: then wait for the call (or a confirmation).
        assertEquals(Step.Wait, WhatsAppCallStart.next(listOf(chat), pressedVoiceCall = true, pressedConfirm = false))
    }

    @Test
    fun aConfirmationIsAnsweredWithCallNotCancel() {
        val dialog = node("FrameLayout", children = listOf(node("Button", text = "Cancel", clickable = true), node("Button", text = "CALL", clickable = true)))
        val pressed = assertIs<Step.Press>(WhatsAppCallStart.next(listOf(chat, dialog), pressedVoiceCall = true, pressedConfirm = false)).node
        assertEquals("CALL", pressed.text)
        assertEquals(Step.Wait, WhatsAppCallStart.next(listOf(chat, dialog), pressedVoiceCall = true, pressedConfirm = true))
    }

    @Test
    fun whenWhatsAppCallsStraightAwayNothingIsPressed() {
        val callScreen = node("FrameLayout", children = listOf(node("ViewGroup", "call_controls_card", children = listOf(node("Button", "end_call_button", desc = "Leave call", clickable = true)))))
        assertEquals(Step.Done, WhatsAppCallStart.next(listOf(callScreen), pressedVoiceCall = false, pressedConfirm = false))
        assertEquals(Step.Wait, WhatsAppCallStart.next(emptyList(), pressedVoiceCall = false, pressedConfirm = false))
    }
}
