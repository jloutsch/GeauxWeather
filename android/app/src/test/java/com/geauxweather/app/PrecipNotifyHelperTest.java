package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Calendar;

import org.junit.Test;

public class PrecipNotifyHelperTest {

    @Test
    public void belowThresholdDoesNotNotify() {
        assertNull(PrecipNotifyHelper.title(69, 0, 5, 70));
    }

    @Test
    public void atThresholdNotifies() {
        assertEquals("Rain likely tomorrow", PrecipNotifyHelper.title(70, 0, 5, 70));
    }

    @Test
    public void snowOnlyDayNotifiesAsSnow() {
        // Anchorage, 2026-11-09: 92%, 1.12 cm snow, ~0 rain
        assertEquals("Snow likely tomorrow", PrecipNotifyHelper.title(92, 1.12, 0, 70));
    }

    @Test
    public void mixedDayNamesBoth() {
        assertEquals("Rain and snow likely tomorrow", PrecipNotifyHelper.title(100, 2.38, 1.9, 70));
    }

    @Test
    public void noTotalsFallsBackToPrecipitation() {
        assertEquals("Precipitation likely tomorrow", PrecipNotifyHelper.title(95, 0, 0, 90));
    }

    @Test
    public void nextRunIsLaterTodayBeforeTheHour() {
        long now = at(14, 30);
        assertEquals((3 * 60 + 30) * 60_000L, PrecipNotifyHelper.millisUntilNext(now, 18));
    }

    @Test
    public void nextRunIsTomorrowAfterTheHour() {
        long now = at(19, 0);
        assertEquals(23 * 60 * 60_000L, PrecipNotifyHelper.millisUntilNext(now, 18));
    }

    @Test
    public void exactlyOnTheHourWaitsForTomorrow() {
        long now = at(18, 0);
        assertEquals(24 * 60 * 60_000L, PrecipNotifyHelper.millisUntilNext(now, 18));
    }

    private static long at(int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 10, hour, minute, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }
}
