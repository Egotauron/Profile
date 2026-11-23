package app.waypoint.trip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class DriveTimeAndLondonTest {
    // 24 Nov 2026 is a Tuesday; London is on GMT in November.
    private val tuesday0830 = Instant.parse("2026-11-24T08:30:00Z")
    private val tuesday1300 = Instant.parse("2026-11-24T13:00:00Z")
    private val tuesday1900 = Instant.parse("2026-11-24T19:00:00Z")
    private val saturday1000 = Instant.parse("2026-11-28T10:00:00Z")
    private val saturday1400 = Instant.parse("2026-11-28T14:00:00Z")
    private val boxingDay = Instant.parse("2026-12-26T10:00:00Z")

    @Test
    fun chargingHours() {
        assertTrue(London.isChargingTime(tuesday0830))
        assertTrue(London.isChargingTime(tuesday1300))
        assertFalse(London.isChargingTime(tuesday1900))
        assertFalse(London.isChargingTime(saturday1000))
        assertTrue(London.isChargingTime(saturday1400))
        assertFalse(London.isChargingTime(boxingDay))
    }

    @Test
    fun peaksUseLondonTimeInSummer() {
        // 07:30 BST = 06:30 UTC on a Monday in July: peak.
        assertTrue(London.isWeekdayPeak(Instant.parse("2026-07-06T06:30:00Z")))
        assertFalse(London.isWeekdayPeak(Instant.parse("2026-07-06T05:30:00Z")))
        assertFalse(London.isWeekdayPeak(saturday1000))
    }

    @Test
    fun startingFactorsMatchTheSpec() {
        val c = DriveTimeCalibration()
        assertEquals(1.4, c.factor(DriveArea.CENTRAL_LONDON, TimeBucket.WEEKDAY_PEAK))
        assertEquals(1.2, c.factor(DriveArea.LONDON, TimeBucket.WEEKDAY_PEAK))
        assertEquals(1.2, c.factor(DriveArea.LONDON, TimeBucket.OFF_PEAK))
        assertEquals(1.0, c.factor(DriveArea.ELSEWHERE, TimeBucket.WEEKDAY_PEAK))
        assertEquals(28.minutes, c.adjust(20.minutes, DriveArea.CENTRAL_LONDON, TimeBucket.WEEKDAY_PEAK))
    }

    @Test
    fun calibrationMovesTowardsObservedDrives() {
        var c = DriveTimeCalibration()
        repeat(10) { c = c.record(DriveArea.LONDON, TimeBucket.OFF_PEAK, planned = 20.minutes, actual = 30.minutes) }
        val f = c.factor(DriveArea.LONDON, TimeBucket.OFF_PEAK)
        assertTrue(f in 1.45..1.5, "factor $f should approach 1.5")
        assertEquals(10, c.cell(DriveArea.LONDON, TimeBucket.OFF_PEAK).samples)
        // Other cells are untouched; short drives and outliers are ignored.
        assertEquals(1.0, c.factor(DriveArea.ELSEWHERE, TimeBucket.OFF_PEAK))
        assertEquals(c, c.record(DriveArea.LONDON, TimeBucket.OFF_PEAK, 2.minutes, 10.minutes))
        assertEquals(c, c.record(DriveArea.LONDON, TimeBucket.OFF_PEAK, 20.minutes, 90.minutes))
        assertEquals(DriveTimeCalibration(), c.reset())
    }

    @Test
    fun areaOfADriveThroughTheCentre() {
        val throughCentre = listOf(LatLng(51.5074, -0.1400), LatLng(51.5130, -0.0900))
        assertEquals(DriveArea.CENTRAL_LONDON, DriveTimeCalibration.areaOf(throughCentre))
        val outer = listOf(LatLng(51.4500, -0.2500), LatLng(51.4300, -0.2000))
        assertEquals(DriveArea.LONDON, DriveTimeCalibration.areaOf(outer))
        val oxford = listOf(LatLng(51.75, -1.26), LatLng(51.76, -1.20))
        assertEquals(DriveArea.ELSEWHERE, DriveTimeCalibration.areaOf(oxford))
    }

    @Test
    fun openInLinks() {
        val stops = (1..12).map { OpenIn.Target(LatLng(51.5 + it / 100.0, -0.1), "Stop $it") }
        val g = OpenIn.googleMaps(LatLng(51.4869, -0.1696), stops, TravelMode.DRIVE)
        assertTrue(g.startsWith("https://www.google.com/maps/dir/?api=1&origin=51.486900,-0.169600"))
        // 9 waypoints + destination, the rest dropped.
        assertTrue(g.contains("&destination=51.600000,-0.100000"))
        assertEquals(9, g.substringAfter("&waypoints=").substringBefore("&").split("%7C").size)
        assertTrue(g.endsWith("&travelmode=driving"))

        assertEquals("0.000051", OpenIn.num(0.000051))
        assertEquals("-0.075900", OpenIn.num(-0.0759))
        assertEquals("British%20Museum%20%26%20Caf%C3%A9", OpenIn.encode("British Museum & Café"))
        val o = OpenIn.organicMaps(null, stops[0], TravelMode.WALK)
        assertEquals("om://route?dll=51.510000,-0.100000&daddr=Stop%201&type=pedestrian", o)
        assertTrue(OpenIn.osmAnd(stops[0], TravelMode.CYCLE).contains("profile=bicycle"))
    }
}
