package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NextHourNotifyHelperTest {
    private static final long NOW = 1_000_000_000L; // epoch seconds
    private static final long H = 60 * 60 * 1000L;

    /** Six 15-minute slots; the first ends 5 minutes ago (the current slot). */
    private static long[] slots() {
        long[] t = new long[6];
        for (int i = 0; i < 6; i++) t[i] = NOW - 5 * 60 + i * 15 * 60;
        return t;
    }

    @Test
    public void dryHourReturnsMinusOne() {
        assertEquals(-1, NextHourNotifyHelper.startWithinHour(slots(), new double[6], NOW));
    }

    @Test
    public void rainInThirdSlotStartsWhenThatSlotStarts() {
        double[] p = {0, 0, 0.3, 0.5, 0, 0};
        long[] t = slots();
        assertEquals(t[2] - 15 * 60, NextHourNotifyHelper.startWithinHour(t, p, NOW));
    }

    @Test
    public void pastSlotAndTracesAreIgnored() {
        double[] p = {2.0, 0.05, 0, 0, 0, 0}; // slot 0 already ended; 0.05 mm is below the minimum
        assertEquals(-1, NextHourNotifyHelper.startWithinHour(slots(), p, NOW));
    }

    @Test
    public void slotStartingAfterTheHourIsIgnored() {
        long[] t = {NOW + 60 * 60 + 15 * 60}; // slot starts exactly 60 minutes from now
        assertEquals(-1, NextHourNotifyHelper.startWithinHour(t, new double[] {1.0}, NOW));
        long[] t2 = {NOW + 59 * 60 + 15 * 60}; // starts in 59 minutes: counts
        assertEquals(NOW + 59 * 60, NextHourNotifyHelper.startWithinHour(t2, new double[] {1.0}, NOW));
    }

    @Test
    public void notifiesOnlyOnDryToWet() {
        long now = 10 * H;
        assertTrue(NextHourNotifyHelper.shouldNotify(true, false, false, now, 0));
        assertFalse(NextHourNotifyHelper.shouldNotify(true, false, true, now, 0)); // already notified this spell
        assertFalse(NextHourNotifyHelper.shouldNotify(true, true, false, now, 0)); // already raining
        assertFalse(NextHourNotifyHelper.shouldNotify(false, false, false, now, 0));
    }

    @Test
    public void flickeringForecastWaitsForCooldown() {
        long now = 10 * H;
        assertFalse(NextHourNotifyHelper.shouldNotify(true, false, false, now, now - H));
        assertTrue(NextHourNotifyHelper.shouldNotify(true, false, false, now, now - 2 * H));
    }

    @Test
    public void titleSaysSoonOrGivesTime() {
        assertEquals("Rain starting soon", NextHourNotifyHelper.title(false, NOW + 5 * 60, NOW, "12:30 PM"));
        assertEquals("Snow starting around 12:30 PM", NextHourNotifyHelper.title(true, NOW + 30 * 60, NOW, "12:30 PM"));
    }
}
