package app.waypoint

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.IntentCompat

/**
 * Opens another app (WhatsApp for a call, a music player) and closes. Opening it from this
 * short-lived screen rather than the map is what lets Android bring Waypoint back in front a few
 * seconds later, once the call is up or the music plays: an app may reopen itself shortly after
 * one of its screens was shown. If Android refuses anyway, the other app stays open and you switch
 * back yourself. Not exported: only Waypoint can start it.
 */
class LaunchAndReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_TARGET, Intent::class.java)
        if (target != null && runCatching { startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) {
            val app = applicationContext
            val back = {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
            when (intent.getStringExtra(EXTRA_PURPOSE)) {
                PURPOSE_CALL -> app.graph.calls.returnToMapWhenConnected(back)
                PURPOSE_MUSIC -> app.graph.music.playThenReturn(target.`package` ?: target.component?.packageName, back)
            }
        }
        finish()
    }

    companion object {
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_PURPOSE = "purpose"
        const val PURPOSE_CALL = "call"
        const val PURPOSE_MUSIC = "music"

        fun start(context: Context, target: Intent, purpose: String) {
            context.startActivity(
                Intent(context, LaunchAndReturnActivity::class.java)
                    .putExtra(EXTRA_TARGET, target)
                    .putExtra(EXTRA_PURPOSE, purpose)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
