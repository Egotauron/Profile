package app.waypoint.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.ElectricBike
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.waypoint.trip.DistanceUnits
import app.waypoint.trip.Segment
import app.waypoint.trip.TransitVehicle
import app.waypoint.trip.TravelMode
import app.waypoint.trip.Warning
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Instant

object Format {
    private val clockFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    fun clock(at: Instant): String =
        clockFmt.format(java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(ZoneId.systemDefault()))

    /** "14:05" today, otherwise "Sat 14:05". */
    fun dayAndClock(at: Instant): String {
        val z = java.time.Instant.ofEpochMilli(at.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
        return if (z.toLocalDate() == java.time.LocalDate.now()) clock(at)
        else DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK).format(z)
    }

    /** "8 min", "1 h 05 min", "40 s". */
    fun duration(d: Duration): String {
        val totalMin = ((d.inWholeSeconds + 30) / 60)
        return when {
            d.inWholeSeconds < 60 -> "${d.inWholeSeconds.coerceAtLeast(0)} s"
            totalMin < 60 -> "$totalMin min"
            else -> "${totalMin / 60} h ${(totalMin % 60).toString().padStart(2, '0')} min"
        }
    }

    /** "2:05" for countdowns. */
    fun countdown(d: Duration): String {
        val s = d.inWholeSeconds.coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun distance(meters: Double, units: DistanceUnits): String = when (units) {
        DistanceUnits.MILES -> if (meters < 400) "${(meters * 1.09361 / 10).roundToInt() * 10} yd" else "%.1f mi".format(Locale.UK, meters / 1609.344)
        DistanceUnits.KILOMETERS -> if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else "%.1f km".format(Locale.UK, meters / 1000)
    }
}

object ModeUi {
    fun label(mode: TravelMode): String = when (mode) {
        TravelMode.DRIVE -> "Drive"
        TravelMode.WALK -> "Walk"
        TravelMode.CYCLE -> "Cycling"
        TravelMode.BIKE_SHARE -> "Bike share"
        TravelMode.TRANSIT -> "Transit"
    }

    fun icon(mode: TravelMode): ImageVector = when (mode) {
        TravelMode.DRIVE -> Icons.Filled.DirectionsCar
        TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
        TravelMode.CYCLE -> Icons.AutoMirrored.Filled.DirectionsBike
        TravelMode.BIKE_SHARE -> Icons.Filled.ElectricBike
        TravelMode.TRANSIT -> Icons.Filled.DirectionsTransit
    }

    fun color(mode: TravelMode): Color = when (mode) {
        TravelMode.DRIVE -> Palette.Drive
        TravelMode.WALK -> Palette.Walk
        TravelMode.CYCLE -> Palette.Cycle
        TravelMode.BIKE_SHARE -> Palette.BikeShare
        TravelMode.TRANSIT -> Palette.Transit
    }

    /** Chip labels in the spec's order: Walk · Bus · Train · Tube · Tram. */
    fun vehicleLabel(v: TransitVehicle): String = when (v) {
        TransitVehicle.BUS -> "Bus"
        TransitVehicle.TRAIN -> "Train"
        TransitVehicle.SUBWAY -> "Tube"
        TransitVehicle.TRAM -> "Tram"
    }

    fun vehicleIcon(v: TransitVehicle): ImageVector = when (v) {
        TransitVehicle.BUS -> Icons.Filled.DirectionsBus
        TransitVehicle.TRAIN -> Icons.Filled.Train
        TransitVehicle.SUBWAY -> Icons.Filled.DirectionsSubway
        TransitVehicle.TRAM -> Icons.Filled.Tram
    }

    fun vehicleColor(v: TransitVehicle): Color = when (v) {
        TransitVehicle.BUS -> Palette.Bus
        TransitVehicle.TRAIN -> Palette.Train
        TransitVehicle.SUBWAY -> Palette.Tube
        TransitVehicle.TRAM -> Palette.Tram
    }

    fun segmentIcon(s: Segment): ImageVector = when (s) {
        is Segment.Walk -> icon(TravelMode.WALK)
        is Segment.Drive -> icon(TravelMode.DRIVE)
        is Segment.Cycle -> icon(TravelMode.CYCLE)
        is Segment.Park -> Icons.Filled.LocalParking
        is Segment.TransitRide -> vehicleIcon(s.vehicle)
        is Segment.BikePickup, is Segment.BikeDropoff -> icon(TravelMode.BIKE_SHARE)
    }

    fun warning(w: Warning): String = when (w) {
        Warning.CHARGE_ZONE -> "Enters the Congestion Charge zone during charging hours"
        Warning.VEHICLE_LEFT_BEHIND -> "Your vehicle stays behind at a stop the trip doesn't return to"
        Warning.LATE_FOR_ARRIVE_BY -> "You'll arrive after your Arrive-by time"
        Warning.NO_TRANSIT_MATCH -> "No transit with the allowed vehicles; walking shown instead"
        Warning.NO_BIKE_DOCKS -> "No dock pair with bikes and spaces nearby"
        Warning.NO_LIVE_TRAFFIC -> "ETA without live traffic"
        Warning.CHARGE_ZONE_NOT_AVOIDED -> "This routing server can't avoid the zone; add a Stadia key in Settings"
        Warning.ULEZ -> "Drives in the ULEZ: your car doesn't meet the standards, so a daily charge applies"
    }

    /** Info-only warnings are shown quietly. */
    fun isInfo(w: Warning) = w == Warning.NO_LIVE_TRAFFIC
}
