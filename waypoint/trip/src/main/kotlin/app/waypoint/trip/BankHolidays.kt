package app.waypoint.trip

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * England and Wales bank holidays from the standing rules (substitute days included). One-off
 * holidays, such as a coronation or jubilee, aren't included.
 */
object BankHolidays {
    private val cache = mutableMapOf<Int, Set<LocalDate>>()

    fun englandAndWales(year: Int): Set<LocalDate> = synchronized(cache) {
        cache.getOrPut(year) { compute(year) }
    }

    private fun compute(year: Int): Set<LocalDate> {
        val days = mutableSetOf<LocalDate>()
        /** Adds [date], moved to the next weekday not already a holiday if it falls on a weekend. */
        fun addWithSubstitute(date: LocalDate) {
            var d = date
            while (d.isWeekend() || d in days) d = d.plus(DatePeriod(days = 1))
            days += d
        }
        addWithSubstitute(LocalDate(year, Month.JANUARY, 1))
        val easter = easterSunday(year)
        days += easter.minus(DatePeriod(days = 2)) // Good Friday
        days += easter.plus(DatePeriod(days = 1)) // Easter Monday
        days += firstMonday(year, Month.MAY) // Early May
        days += lastMonday(year, Month.MAY) // Spring
        days += lastMonday(year, Month.AUGUST) // Summer
        // Christmas Day, then Boxing Day, each moved past weekends and each other.
        val christmas = LocalDate(year, Month.DECEMBER, 25)
        val boxing = LocalDate(year, Month.DECEMBER, 26)
        if (!christmas.isWeekend()) days += christmas
        if (!boxing.isWeekend()) days += boxing
        if (christmas.isWeekend()) addWithSubstitute(christmas)
        if (boxing.isWeekend()) addWithSubstitute(boxing)
        return days
    }

    private fun LocalDate.isWeekend() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY

    private fun firstMonday(year: Int, month: Month): LocalDate {
        var d = LocalDate(year, month, 1)
        while (d.dayOfWeek != DayOfWeek.MONDAY) d = d.plus(DatePeriod(days = 1))
        return d
    }

    private fun lastMonday(year: Int, month: Month): LocalDate {
        var d = LocalDate(year, month, 1).plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        while (d.dayOfWeek != DayOfWeek.MONDAY) d = d.minus(DatePeriod(days = 1))
        return d
    }

    /** Anonymous Gregorian algorithm. */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate(year, month, day)
    }
}
