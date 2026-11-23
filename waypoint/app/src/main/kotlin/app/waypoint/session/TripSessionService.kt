package app.waypoint.session

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.waypoint.MainActivity
import app.waypoint.R
import app.waypoint.WaypointApp
import app.waypoint.graph
import app.waypoint.trip.StopWait
import app.waypoint.trip.TripPhase
import kotlinx.coroutines.launch

/**
 * Foreground service (location type) that keeps the trip alive while the phone is locked or
 * Android Auto connects and disconnects. The notification mirrors the arrival card.
 */
class TripSessionService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        val canLocate = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!canLocate) {
            // The trip is only started after location is granted; nothing to keep alive otherwise.
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(graph.session.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        lifecycleScope.launch {
            graph.session.state.collect { s ->
                if (s.plan == null || s.phase is TripPhase.Finished) {
                    stopSelf()
                } else if (canNotify()) {
                    runCatching { NotificationManagerCompat.from(this@TripSessionService).notify(NOTIFICATION_ID, build(s)) }
                }
            }
        }
    }

    private fun canNotify(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_NEXT_LEG -> graph.session.startNextLeg()
            ACTION_END -> graph.session.endTrip()
        }
        return START_NOT_STICKY
    }

    private fun build(s: SessionState): Notification {
        val title = when (val p = s.phase) {
            is TripPhase.AtStop -> when (val w = p.wait) {
                is StopWait.Countdown -> "At ${s.stopName} · continuing to ${s.nextStopName}"
                StopWait.ForYou -> "Arrived at ${s.stopName}"
            }
            is TripPhase.OnLeg -> "Stop ${s.stopNumber} of ${s.stopCount}: ${s.stopName}"
            else -> "Trip ready"
        }
        val text = when (val seg = s.segment) {
            is SegmentUi.TransitWait -> "Wait for the ${seg.ride.line} to ${seg.ride.headsign}"
            is SegmentUi.TransitRide -> if (seg.getOffNext) "Get off at the next stop: ${seg.ride.alight.name}" else "Ride the ${seg.ride.line} to ${seg.ride.alight.name}"
            is SegmentUi.BikePickup -> "Unlock a bike at ${seg.dock.name}"
            is SegmentUi.BikeDropoff -> "Dock the bike at ${seg.dock.name}"
            else -> s.plan?.arriveAt?.let { "Trip ETA ${app.waypoint.ui.Format.clock(it)}" } ?: ""
        }
        return NotificationCompat.Builder(this, WaypointApp.CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_stat_waypoint)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(openAppIntent(this))
            .apply {
                if (s.atStop != null && s.nextStopName != null) {
                    addAction(0, "Start next leg", serviceIntent(ACTION_NEXT_LEG))
                }
                addAction(0, "End trip", serviceIntent(ACTION_END))
            }
            .build()
    }

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, TripSessionService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_NEXT_LEG = "app.waypoint.NEXT_LEG"
        const val ACTION_END = "app.waypoint.END_TRIP"

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
