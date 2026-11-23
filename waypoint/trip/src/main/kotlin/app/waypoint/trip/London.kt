package app.waypoint.trip

import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * London areas used by the planner.
 *
 * Both polygons are hand-drawn approximations (lng/lat corners of the Inner Ring Road and of the
 * Greater London boundary). Spec §4.3: the charge-zone boundary is "bundled, refreshed by hand";
 * check TfL before relying on it for a charge.
 */
object London {
    val timeZone: TimeZone = TimeZone.of("Europe/London")

    /**
     * Congestion Charge zone, drawn just inside the Inner Ring Road (which itself is free to use):
     * Park Lane, Marylebone/Euston/Pentonville Roads, City Road, Commercial St, Tower Bridge,
     * New Kent Road, Elephant & Castle, Kennington Lane, Vauxhall Bridge Road.
     */
    val congestionChargeZone: List<LatLng> = listOf(
        LatLng(51.5120, -0.1560), // Park Lane (Marble Arch end)
        LatLng(51.5190, -0.1600), // Edgware Road
        LatLng(51.5228, -0.1430), // Marylebone Road
        LatLng(51.5290, -0.1240), // Euston Road / King's Cross
        LatLng(51.5310, -0.1060), // Pentonville Road / Angel
        LatLng(51.5250, -0.0880), // City Road / Old Street
        LatLng(51.5240, -0.0790), // Great Eastern Street / Shoreditch
        LatLng(51.5150, -0.0720), // Commercial Street / Aldgate
        LatLng(51.5095, -0.0745), // Tower Hill
        LatLng(51.5045, -0.0760), // Tower Bridge
        LatLng(51.4955, -0.0860), // Bricklayers Arms
        LatLng(51.4955, -0.1000), // Elephant & Castle
        LatLng(51.4900, -0.1150), // Kennington Lane
        LatLng(51.4890, -0.1265), // Vauxhall
        LatLng(51.4975, -0.1440), // Vauxhall Bridge Road / Victoria
        LatLng(51.5030, -0.1515), // Hyde Park Corner
    )

    /** Rough Greater London boundary (inside the M25). */
    val greaterLondon: List<LatLng> = listOf(
        LatLng(51.6860, -0.1050), LatLng(51.6830, -0.2050), LatLng(51.6500, -0.3000),
        LatLng(51.6250, -0.4000), LatLng(51.6300, -0.4950), LatLng(51.5550, -0.5100),
        LatLng(51.4800, -0.5100), LatLng(51.4500, -0.4600), LatLng(51.4100, -0.3700),
        LatLng(51.3300, -0.3100), LatLng(51.3300, -0.2000), LatLng(51.2900, -0.1100),
        LatLng(51.2900, 0.0300), LatLng(51.3300, 0.1200), LatLng(51.4000, 0.1600),
        LatLng(51.4800, 0.2200), LatLng(51.5300, 0.3000), LatLng(51.6200, 0.2200),
        LatLng(51.6300, 0.1100), LatLng(51.6600, 0.0200), LatLng(51.6860, -0.0200),
    )

    fun inGreaterLondon(p: LatLng): Boolean = Geo.contains(greaterLondon, p)

    fun inChargeZone(p: LatLng): Boolean = Geo.contains(congestionChargeZone, p)

    /**
     * Congestion Charge hours: Mon–Fri 07:00–18:00; weekends and bank holidays 12:00–18:00; no charge
     * from Christmas Day to the New Year's Day bank holiday.
     */
    fun isChargingTime(at: Instant): Boolean {
        val t = at.toLocalDateTime(timeZone)
        val date = t.date
        val newYearHoliday = BankHolidays.englandAndWales(date.year).filter { it.month == Month.JANUARY }.minOrNull()
        if ((t.month == Month.DECEMBER && t.day >= 25) || (newYearHoliday != null && date <= newYearHoliday)) return false
        val weekendHours = t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY ||
            date in BankHolidays.englandAndWales(date.year)
        val minutes = t.hour * 60 + t.minute
        val start = if (weekendHours) 12 * 60 else 7 * 60
        return minutes in start until 18 * 60
    }

    /**
     * Ultra Low Emission Zone: all of Greater London (approximated by [greaterLondon]), every day
     * except Christmas Day. Only cars that don't meet the ULEZ standards pay.
     */
    fun isUlezTime(at: Instant): Boolean {
        val t = at.toLocalDateTime(timeZone)
        return !(t.month == Month.DECEMBER && t.day == 25)
    }

    /** True when a non-compliant car would pay the ULEZ charge for this drive. */
    fun driveIsInUlez(geometry: List<LatLng>, depart: Instant): Boolean =
        isUlezTime(depart) && Geo.lineEnters(geometry, greaterLondon)

    /** Weekday peaks 07–10 and 16–19 (spec §4.3). */
    fun isWeekdayPeak(at: Instant): Boolean {
        val t = at.toLocalDateTime(timeZone)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        return t.hour in 7 until 10 || t.hour in 16 until 19
    }

    /** True when the drive enters the zone during charging hours at any point of [depart, arrive]. */
    fun driveIsCharged(geometry: List<LatLng>, depart: Instant, arrive: Instant): Boolean =
        (isChargingTime(depart) || isChargingTime(arrive)) && Geo.lineEnters(geometry, congestionChargeZone)
}
