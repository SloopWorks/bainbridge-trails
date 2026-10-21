package com.bainbridgetrails.play.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan
import kotlin.time.Instant

/** One island day's sun events. Dawn/dusk = civil twilight (zenith 96°); sunrise/sunset zenith 90.833°. */
data class SunDay(val dawn: Instant?, val sunrise: Instant?, val noon: Instant, val sunset: Instant?, val dusk: Instant?)

/**
 * NOAA GML solar calculator equations (Meeus, *Astronomical Algorithms*; gml.noaa.gov/grad/solcalc/calcdetails.html),
 * schedule.md §1.1: "theoretically accurate to within a minute" between ±72° latitude, valid 1800–2100. Total: a
 * zenith that is never reached gives null for that event.
 */
object Sun {
    const val ISLAND_LAT = 47.63
    const val ISLAND_LON = -122.52
    private const val ZENITH_SUN = 90.833
    private const val ZENITH_CIVIL = 96.0

    fun sunEvents(date: LocalDate, lat: Double = ISLAND_LAT, lon: Double = ISLAND_LON, tz: TimeZone = IslandTime.zone): SunDay {
        // NOAA evaluates from 0h UT of the date; for a zone far from UTC the event can land on the neighbouring local
        // date, so shift the UT base day until the local solar noon falls on [date].
        var base = date
        for (attempt in 0 until 2) {
            val local = IslandTime.dateOf(noonOf(base, lon), tz)
            if (local == date) break
            base = base.plus(if (local < date) 1 else -1, DateTimeUnit.DAY)
        }
        return SunDay(
            dawn = eventOf(base, true, lat, lon, ZENITH_CIVIL),
            sunrise = eventOf(base, true, lat, lon, ZENITH_SUN),
            noon = noonOf(base, lon),
            sunset = eventOf(base, false, lat, lon, ZENITH_SUN),
            dusk = eventOf(base, false, lat, lon, ZENITH_CIVIL),
        )
    }

    private fun jd0(date: LocalDate): Double = date.toEpochDays() + 2_440_587.5

    private fun at(date: LocalDate, utcMinutes: Double): Instant =
        Instant.fromEpochMilliseconds(date.toEpochDays() * 86_400_000L + (utcMinutes * 60_000.0).roundToLong())

    private fun noonOf(date: LocalDate, lon: Double): Instant {
        val jd = jd0(date)
        val first = 720.0 - 4.0 * lon - equationOfTime(cent(jd - lon / 360.0))
        return at(date, 720.0 - 4.0 * lon - equationOfTime(cent(jd + first / 1440.0)))
    }

    private fun eventOf(date: LocalDate, rise: Boolean, lat: Double, lon: Double, zenith: Double): Instant? {
        val jd = jd0(date)
        val first = utcMinutes(rise, jd, lat, lon, zenith) ?: return null
        val refined = utcMinutes(rise, jd + first / 1440.0, lat, lon, zenith) ?: return null
        return at(date, refined)
    }

    private fun utcMinutes(rise: Boolean, jd: Double, lat: Double, lon: Double, zenith: Double): Double? {
        val t = cent(jd)
        val ha = hourAngle(lat, declination(t), zenith) ?: return null
        val delta = lon + deg(if (rise) ha else -ha)
        return 720.0 - 4.0 * delta - equationOfTime(t)
    }

    private fun cent(jd: Double): Double = (jd - 2_451_545.0) / 36_525.0
    private fun rad(d: Double): Double = d * PI / 180.0
    private fun deg(r: Double): Double = r * 180.0 / PI

    private fun geomMeanLongSun(t: Double): Double {
        var l0 = 280.46646 + t * (36_000.76983 + t * 0.0003032)
        l0 %= 360.0
        if (l0 < 0) l0 += 360.0
        return l0
    }

    private fun geomMeanAnomalySun(t: Double): Double = 357.52911 + t * (35_999.05029 - 0.0001537 * t)
    private fun eccentricity(t: Double): Double = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

    private fun eqOfCenter(t: Double): Double {
        val m = rad(geomMeanAnomalySun(t))
        return sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) +
            sin(3 * m) * 0.000289
    }

    private fun apparentLong(t: Double): Double {
        val omega = 125.04 - 1934.136 * t
        return geomMeanLongSun(t) + eqOfCenter(t) - 0.00569 - 0.00478 * sin(rad(omega))
    }

    private fun obliquityCorrection(t: Double): Double {
        val seconds = 21.448 - t * (46.8150 + t * (0.00059 - t * 0.001813))
        val e0 = 23.0 + (26.0 + seconds / 60.0) / 60.0
        return e0 + 0.00256 * cos(rad(125.04 - 1934.136 * t))
    }

    private fun declination(t: Double): Double = deg(asin(sin(rad(obliquityCorrection(t))) * sin(rad(apparentLong(t)))))

    /** Minutes of time. */
    private fun equationOfTime(t: Double): Double {
        val epsilon = obliquityCorrection(t)
        val l0 = rad(geomMeanLongSun(t))
        val e = eccentricity(t)
        val m = rad(geomMeanAnomalySun(t))
        var y = tan(rad(epsilon) / 2.0)
        y *= y
        val eTime = y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) -
            0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m)
        return deg(eTime) * 4.0
    }

    /** Hour angle (radians) at [zenith] degrees, or null when the sun never reaches it that day. */
    private fun hourAngle(lat: Double, dec: Double, zenith: Double): Double? {
        val arg = cos(rad(zenith)) / (cos(rad(lat)) * cos(rad(dec))) - tan(rad(lat)) * tan(rad(dec))
        return if (arg < -1.0 || arg > 1.0) null else acos(arg)
    }
}
