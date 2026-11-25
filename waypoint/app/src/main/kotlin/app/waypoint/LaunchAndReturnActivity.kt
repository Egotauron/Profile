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
        val fallbacks = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_FALLBACKS, Intent::class.java).orEmpty()
        // The first that opens; nothing failing silently: say what happened if it wasn't the first.
        val opened = (listOfNotNull(target) + fallbacks).indexOfFirst { t ->
            runCatching { startActivity(Intent(t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { android.util.Log.w("Waypoint", "Couldn't open $t", it) }
                .isSuccess
        }
        val note = when {
            opened < 0 -> intent.getStringExtra(EXTRA_FAILED)
            opened > 0 -> intent.getStringExtra(EXTRA_FALLBACK_NOTE)
            else -> null
        }
        note?.let { android.widget.Toast.makeText(applicationContext, it, android.widget.Toast.LENGTH_LONG).show() }
        if (target != null && opened == 0) {
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

        private const val EXTRA_FALLBACKS = "fallbacks"
        private const val EXTRA_FALLBACK_NOTE = "fallbackNote"
        private const val EXTRA_FAILED = "failed"

        /**
         * Opens [target], else the first of [fallbacks] that opens (then shows [fallbackNote]), else
         * shows [failedNote].
         */
        fun start(
            context: Context,
            target: Intent,
            purpose: String,
            fallbacks: List<Intent> = emptyList(),
            fallbackNote: String? = null,
            failedNote: String? = null,
        ) {
            context.startActivity(intent(context, target, purpose, fallbacks, fallbackNote, failedNote))
        }

        internal fun intent(
            context: Context,
            target: Intent,
            purpose: String,
            fallbacks: List<Intent> = emptyList(),
            fallbackNote: String? = null,
            failedNote: String? = null,
        ): Intent = Intent(context, LaunchAndReturnActivity::class.java)
            .putExtra(EXTRA_TARGET, target)
            .putParcelableArrayListExtra(EXTRA_FALLBACKS, ArrayList(fallbacks))
            .putExtra(EXTRA_FALLBACK_NOTE, fallbackNote)
            .putExtra(EXTRA_FAILED, failedNote)
            .putExtra(EXTRA_PURPOSE, purpose)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
