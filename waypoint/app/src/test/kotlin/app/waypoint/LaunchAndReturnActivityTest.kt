package app.waypoint

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.waypoint.calls.CallReports
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** Opening WhatsApp for a call never fails silently, and what happened is kept for the call report. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LaunchAndReturnActivityTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val call = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse("content://com.android.contacts/data/1"), "vnd.android.cursor.item/vnd.com.whatsapp.voip.call")
        .setPackage("com.whatsapp")
    private val chat = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse("content://com.android.contacts/data/2"), "vnd.android.cursor.item/vnd.com.whatsapp.profile")
        .setPackage("com.whatsapp")

    @Before
    fun strict() {
        // Starting an activity nothing handles throws, as on a phone.
        shadowOf(app).checkActivities(true)
    }

    private fun handles(intent: Intent) = shadowOf(app.packageManager).addResolveInfoForIntent(
        intent, ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "com.whatsapp"; name = "com.whatsapp.Handler" } },
    )

    private fun launch() = Robolectric.buildActivity(
        LaunchAndReturnActivity::class.java,
        LaunchAndReturnActivity.intent(app, call, LaunchAndReturnActivity.PURPOSE_CALL, listOf(chat), "Opened the chat", "Couldn't open WhatsApp"),
    ).create()

    @Test
    fun whenWhatsAppWontStartTheCallItsChatOpensAndYouAreTold() {
        handles(chat)
        launch()
        assertEquals(chat.type, shadowOf(app).nextStartedActivity?.type)
        assertEquals("Opened the chat", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun whenNothingOpensYouAreTold() {
        launch()
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals("Couldn't open WhatsApp", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun aCallThatDoesntStartIsReportedWithAndroidsError() {
        CallReports.last.value = null
        Robolectric.buildActivity(LaunchAndReturnActivity::class.java, LaunchAndReturnActivity.intent(app, call, LaunchAndReturnActivity.PURPOSE_CALL)).create()
        val report = CallReports.last.value!!
        assertEquals(true, report.failed)
        assertContains(report.text, "ActivityNotFoundException")
    }

    @Test
    fun aCallWhatsAppAcceptsIsReportedAsAccepted() {
        CallReports.last.value = null
        handles(call)
        Robolectric.buildActivity(LaunchAndReturnActivity::class.java, LaunchAndReturnActivity.intent(app, call, LaunchAndReturnActivity.PURPOSE_CALL)).create()
        val report = CallReports.last.value!!
        assertEquals(false, report.failed)
        assertContains(report.text, "WhatsApp accepted the request")
    }

    /** On the phone, WhatsApp refused the call: "requires android.permission.CALL_PHONE". */
    @Test
    fun waypointAsksForThePhonePermissionWhatsAppsCallScreenRequires() {
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertContains(info.requestedPermissions.orEmpty().toList(), android.Manifest.permission.CALL_PHONE)
    }
}
