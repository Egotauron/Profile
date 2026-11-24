package app.waypoint.calls

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.waypoint.MainActivity
import app.waypoint.graph

/**
 * Starts the WhatsApp call, then closes. Placing the call from this short-lived screen (rather
 * than the map) is what lets Android bring Waypoint back in front of WhatsApp's call screen a few
 * seconds later, when the call is up: an app may reopen itself shortly after one of its screens
 * was shown. If Android refuses anyway, the call carries on and you switch back yourself.
 */
class CallLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        val mime = intent.getStringExtra(EXTRA_MIME)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (data != null && mime != null && pkg in CallController.APPS.keys) {
            val call = Intent(Intent.ACTION_VIEW).setDataAndType(data, mime).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(call) }.isSuccess) {
                val app = applicationContext
                app.graph.calls.returnToMapWhenConnected {
                    app.startActivity(
                        Intent(app, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_MIME = "mime"
        const val EXTRA_PACKAGE = "package"
    }
}
