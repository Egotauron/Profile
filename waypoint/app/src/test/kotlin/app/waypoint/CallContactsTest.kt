package app.waypoint

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.waypoint.calls.CallController
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The people you can call on WhatsApp, read from the rows WhatsApp adds to your contacts. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallContactsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** Contacts with WhatsApp voice-call rows. */
    class FakeContacts : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val columns = projection ?: emptyArray()
            val cursor = MatrixCursor(columns)
            fun row(id: Long, contact: Long, name: String, jid: String, label: String?) {
                val values = mapOf(
                    "_id" to id, "contact_id" to contact, "display_name" to name, "starred" to 0, "photo_thumb_uri" to null,
                    "mimetype" to VOIP, "data1" to jid, "data3" to label,
                )
                cursor.addRow(columns.map { values[it] })
            }
            row(10, 1, "Anna ❤️", "393381234508@s.whatsapp.net", "Voice call +39 338 123 4508")
            row(11, 1, "Anna ❤️", "447700900123@s.whatsapp.net", "Voice call +44 7700 900123")
            row(12, 1, "Anna ❤️", "393381234508@s.whatsapp.net", "Voice call +39 338 123 4508") // the same number again
            row(20, 2, "Ben", "447700900456@s.whatsapp.net", null)
            return cursor
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    @Test
    fun aPersonWithSeveralWhatsAppNumbersHasThemAllToChooseFrom() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.buildContentProvider(FakeContacts::class.java).create("com.android.contacts")
        val people = CallController(app).contacts()

        assertEquals(listOf("Anna ❤️", "Ben"), people.map { it.name })
        val anna = people.first()
        assertEquals(10L, anna.dataId)
        assertEquals(listOf(10L to "+39 338 123 4508", 11L to "+44 7700 900123"), anna.numbers.map { it.dataId to it.label })
        // One number: called straight away, no choice.
        assertTrue(people.last().numbers.isEmpty())
    }

    private companion object {
        const val VOIP = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    }
}
