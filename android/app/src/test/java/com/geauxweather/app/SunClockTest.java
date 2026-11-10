package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Same sun times as tests/weather.test.js (Open-Meteo archive API, UTC). */
public class SunClockTest {

    private static final long MIN = 60_000L;

    private static long utc(String iso) {
        return HurricaneAlertHelper.parseIsoMillis(iso);
    }

    // {lat, lon, sunrise, sunset}
    private static final Object[][] SUN = {
            {42.36, -71.06, "2025-06-21T09:07:00Z", "2025-06-22T00:24:00Z"}, // Boston, June
            {42.36, -71.06, "2025-12-21T12:10:00Z", "2025-12-21T21:15:00Z"}, // Boston, December
            {-33.87, 151.21, "2025-06-20T20:59:00Z", "2025-06-21T06:53:00Z"}, // Sydney
            {30.45, -91.19, "2025-03-20T12:07:00Z", "2025-03-21T00:16:00Z"}, // Baton Rouge
    };

    @Test
    public void switchesAtSunriseAndSunset() {
        for (Object[] s : SUN) {
            double lat = (double) s[0];
            double lon = (double) s[1];
            long rise = utc((String) s[2]);
            long set = utc((String) s[3]);
            assertFalse(SunClock.isDaytime(rise - 5 * MIN, lat, lon));
            assertTrue(SunClock.isDaytime(rise + 5 * MIN, lat, lon));
            assertTrue(SunClock.isDaytime(set - 5 * MIN, lat, lon));
            assertFalse(SunClock.isDaytime(set + 5 * MIN, lat, lon));
        }
    }

    @Test
    public void polarNightAndMidnightSun() {
        assertFalse(SunClock.isDaytime(utc("2025-12-21T11:00:00Z"), 69.65, 18.96)); // Tromsø, local noon
        assertTrue(SunClock.isDaytime(utc("2025-06-21T23:00:00Z"), 69.65, 18.96)); // Tromsø, 1 AM
        assertEquals(-1, SunClock.nextChange(utc("2025-12-20T12:00:00Z"), 69.65, 18.96));
    }

    @Test
    public void nextChangeFindsTheComingSunsetAndSunriseWithinTwoMinutes() {
        long rise = utc("2025-12-21T12:10:00Z");
        long set = utc("2025-12-21T21:15:00Z");
        long fromNoon = SunClock.nextChange(utc("2025-12-21T17:00:00Z"), 42.36, -71.06);
        assertTrue(Math.abs(fromNoon - set) <= 2 * MIN);
        long fromNight = SunClock.nextChange(utc("2025-12-21T06:00:00Z"), 42.36, -71.06);
        assertTrue(Math.abs(fromNight - rise) <= 2 * MIN);
        long fromJustAfter = SunClock.nextChange(set + 3 * MIN, 42.36, -71.06);
        assertTrue("next is the following sunrise", fromJustAfter > set + 10 * 60 * MIN);
    }
}
