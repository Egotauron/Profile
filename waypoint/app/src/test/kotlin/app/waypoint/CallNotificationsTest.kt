package app.waypoint

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.waypoint.calls.CallNotifications
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Reading the call in progress from real call notifications, as WhatsApp and the phone app post them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun intent(name: String) = PendingIntent.getBroadcast(context, name.hashCode(), Intent(name), PendingIntent.FLAG_IMMUTABLE)
    private fun posted(n: Notification) = StatusBarNotification("com.whatsapp", "com.whatsapp", 1, null, 0, 0, 0, n, Process.myUserHandle(), 0L)
    private val anna = Person.Builder().setName("Anna").build()

    @Test
    fun ongoingCallStyleCall() {
        val n = Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setStyle(Notification.CallStyle.forOngoingCall(anna, intent("hangup")))
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
            .setUsesChronometer(true).setWhen(1_000_000L)
            .build()
        val sbn = posted(n)
        assertTrue(CallNotifications.isCall(sbn))
        val call = CallNotifications.toLiveCall(sbn, "WhatsApp")
        assertEquals("Anna", call.caller)
        assertFalse(call.incoming)
        assertEquals(1_000_000L, call.since)
        assertNotNull(call.hangUp)
        assertNull(call.answer)
    }

    @Test
    fun incomingCallStyleCall() {
        val n = Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setStyle(Notification.CallStyle.forIncomingCall(anna, intent("decline"), intent("answer")))
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
            .build()
        val call = CallNotifications.toLiveCall(posted(n), "WhatsApp")
        assertTrue(call.incoming)
        assertNotNull(call.answer)
        assertNotNull(call.decline)
        assertNull(call.since)
    }

    @Test
    fun olderStyleWithPlainButtons() {
        val hangUp = intent("legacy-hangup")
        val n = Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("Ben").setContentText("Ongoing voice call")
            .addAction(Notification.Action.Builder(null, "Speaker", intent("speaker")).build())
            .addAction(Notification.Action.Builder(null, "Hang up", hangUp).build())
            .setCategory(Notification.CATEGORY_CALL).setOngoing(true)
            .build()
        val call = CallNotifications.toLiveCall(posted(n), "WhatsApp")
        assertEquals("Ben", call.caller)
        assertFalse(call.incoming)
        assertEquals(hangUp, call.hangUp)
    }

    @Test
    fun missedCallIsNotALiveCall() {
        val n = Notification.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Missed call from Anna")
            .setCategory(Notification.CATEGORY_CALL)
            .build()
        assertFalse(CallNotifications.isCall(posted(n)))
    }
}
