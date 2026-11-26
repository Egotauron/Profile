package app.waypoint

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.waypoint.calls.CallScreenFormat
import app.waypoint.calls.ScreenNode
import app.waypoint.calls.WhatsAppCallScreen
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** The WhatsApp call screen reader: only WhatsApp, and what it records is masked. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallScreenTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private val callScreen = ScreenNode(
        "android.widget.FrameLayout", null, null, null,
        children = listOf(
            ScreenNode("android.widget.TextView", "com.whatsapp:id/name", null, "Anna Rossi"),
            ScreenNode("android.widget.TextView", "com.whatsapp:id/call_status", null, "01:23"),
            ScreenNode("android.widget.ImageView", null, "Profile photo of Anna Rossi", null),
            ScreenNode("android.widget.LinearLayout", null, null, null),
            ScreenNode(
                "android.widget.LinearLayout", "com.whatsapp:id/controls", null, null,
                children = listOf(
                    ScreenNode("android.widget.ImageButton", "com.whatsapp:id/audio_route", "Audio output", null, clickable = true, bounds = "[0,0][10,10]"),
                    ScreenNode("android.widget.Button", null, null, "Ed's AirPods 2", clickable = true, selected = true),
                ),
            ),
        ),
    )

    @Test
    fun buttonLabelsStayReadableAndNamesAndNumbersAreMasked() {
        val lines = CallScreenFormat.lines(callScreen, listOf("Anna Rossi", "Ed's AirPods 2"))
        val all = lines.joinToString("\n")
        assertFalse("Anna" in all || "Rossi" in all, all)
        assertFalse("AirPods" in all || Regex("\\d").containsMatchIn(all.replace(Regex("\\[[\\d,]+]"), "")), all)
        assertTrue(lines.any { it.contains("ImageButton #audio_route desc=\"Audio output\" TAPPABLE [0,0][10,10]") }, all)
        assertTrue(lines.any { it.contains("TextView #name text=\"A…\"") }, all)
        assertTrue(lines.any { it.contains("desc=\"Profile photo of A… R…\"") }, all)
        assertTrue(lines.any { it.contains("text=\"E… A… •\" TAPPABLE selected") }, all)
        // Empty layouts are left out; the controls' children are indented under them.
        assertEquals(6, lines.size, all)
        assertTrue(lines.first { "audio_route" in it }.startsWith("  ImageButton"), all)
        assertTrue(lines.any { it.startsWith("TextView #call_status text=\"•…\"") }, all)
    }

    @Test
    fun longScreensAreCut() {
        val big = ScreenNode("Root", null, null, null, children = List(500) { ScreenNode("Button", null, "b", null, clickable = true) })
        val lines = CallScreenFormat.lines(big, emptyList())
        assertEquals(CallScreenFormat.MAX_LINES + 1, lines.size)
        assertTrue(lines.last().startsWith("… (cut"))
    }

    @Test
    fun theReaderOnlyHearsWhatsAppAndOnlyAndroidCanBindIt() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, WhatsAppCallScreen::class.java), PackageManager.GET_META_DATA)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, info.permission)
        val parser = app.resources.getXml(info.metaData.getInt("android.accessibilityservice"))
        var packages: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "accessibility-service") {
                packages = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "packageNames")
            }
        }
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), packages?.split(",")?.toSet())
    }
}
