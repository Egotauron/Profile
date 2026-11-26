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

    /** A chat as WhatsApp opened it, from the call report (WhatsApp 2.26.45), with [name]. */
    private fun chat(name: String) = node(
        "FrameLayout", "action_bar_root",
        children = listOf(
            node(
                "LinearLayout", "conversation_root_layout",
                children = listOf(
                    // A "Voice call" line in the messages comes first: never pressed.
                    node("ListView", "list", children = listOf(node("ViewGroup", "conversation_row_text", clickable = true, children = listOf(node("TextView", "message_text", text = "Voice call"))))),
                    node(
                        "ViewGroup", "toolbar",
                        children = listOf(
                            node("Button", "whatsapp_toolbar_home", desc = "Back", clickable = true),
                            node("LinearLayout", "conversation_contact", clickable = true, children = listOf(node("TextView", "conversation_contact_name", text = name))),
                            node("ImageButton", desc = "Video call", clickable = true),
                            node("ImageButton", desc = "Voice call", clickable = true),
                            node("ImageView", "menuitem_overflow", desc = "More options", clickable = true),
                        ),
                    ),
                    node("ImageButton", "voice_note_btn", desc = "Voice message, Button. Double tap and hold to record"),
                ),
            ),
        ),
    )

    /** WhatsApp's chat list, where it left you in the second report. */
    private val chatList = node(
        "FrameLayout", "action_bar_root",
        children = listOf(node("LinearLayout", "conversation_list_view_host", children = listOf(node("TextView", "conversations_row_contact_name", text = "Mum ❤️")))),
    )

    private fun next(
        screens: List<ScreenNode>, elapsedMs: Long = 1_000, callGoing: Boolean = false, openedChat: Boolean = false,
        pressedVoiceCall: Boolean = false, pressedConfirm: Boolean = false, canOpenChat: Boolean = true,
    ) = WhatsAppCallStart.next(screens, "Mum ❤️", elapsedMs, callGoing, openedChat, pressedVoiceCall, pressedConfirm, canOpenChat)

    @Test
    fun inTheirChatItsVoiceCallButtonIsPressed() {
        val pressed = assertIs<Step.Press>(next(listOf(chat("Mum ❤️")))).node
        assertEquals("android.widget.ImageButton" to "Voice call", pressed.className to pressed.desc)
        // The name as WhatsApp shows it may differ in emoji and spacing.
        assertIs<Step.Press>(next(listOf(chat("MUM"))))
        // Once: then wait for the call (or a confirmation).
        assertEquals(Step.Wait, next(listOf(chat("Mum ❤️")), pressedVoiceCall = true))
    }

    @Test
    fun inSomeoneElsesChatNothingIsPressedAndTheirChatIsOpened() {
        assertEquals(Step.OpenChat, next(listOf(chat("Matt"))))
        // Opened, but someone else's chat is still showing: still nothing pressed.
        assertEquals(Step.Wait, next(listOf(chat("Matt")), openedChat = true))
        assertEquals(Step.Wait, next(listOf(chat("Matt")), canOpenChat = false))
    }

    @Test
    fun leftOnTheChatListTheirChatIsOpenedAfterAMoment() {
        assertEquals(Step.Wait, next(listOf(chatList), elapsedMs = 1_000))
        assertEquals(Step.OpenChat, next(listOf(chatList), elapsedMs = WhatsAppCallStart.OPEN_CHAT_AFTER_MS))
        assertEquals(Step.Wait, next(listOf(chatList), elapsedMs = 5_000, openedChat = true))
    }

    @Test
    fun aConfirmationIsAnsweredWithCallNotCancel() {
        val dialog = node("FrameLayout", children = listOf(node("Button", text = "Cancel", clickable = true), node("Button", text = "CALL", clickable = true)))
        val pressed = assertIs<Step.Press>(next(listOf(chat("Mum ❤️"), dialog), pressedVoiceCall = true)).node
        assertEquals("CALL", pressed.text)
        assertEquals(Step.Wait, next(listOf(chat("Mum ❤️"), dialog), pressedVoiceCall = true, pressedConfirm = true))
    }

    @Test
    fun whenWhatsAppCallsByItselfNothingIsPressed() {
        val callScreen = node("FrameLayout", children = listOf(node("ViewGroup", "call_controls_card", children = listOf(node("Button", "end_call_button", desc = "Leave call", clickable = true)))))
        assertEquals(Step.Done, next(listOf(callScreen)))
        // A call already going (its notification, or call mode): not even in their chat.
        assertEquals(Step.Done, next(listOf(chat("Mum ❤️")), callGoing = true))
        assertEquals(Step.Wait, next(emptyList()))
    }
}
