package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SummaryNotifyHelperTest {

    private static final long OFFSET = -5 * 3600;                 // EST
    private static final long MIDNIGHT = 86400L * 20000 - OFFSET; // a local midnight, as UTC seconds
    private static final long NOW = MIDNIGHT + 7 * 3600 + 600;    // 7:10 AM local

    private static long[] times() {
        long[] t = new long[24];
        for (int i = 0; i < 24; i++) t[i] = MIDNIGHT + i * 3600L;
        return t;
    }

    /** Chance per local hour: {@code base} everywhere, {@code high} from {@code fromHour} on. */
    private static int[] chances(int base, int high, int fromHour) {
        int[] p = new int[24];
        for (int i = 0; i < 24; i++) p[i] = i >= fromHour ? high : base;
        return p;
    }

    @Test
    public void titleRoundsTemperaturesAndDropsTheEmoji() {
        assertEquals("Today: 57°/44° · Partly cloudy", SummaryNotifyHelper.title(56.6, 43.5, 2));
        assertEquals("Today: -3°/-11° · Clear", SummaryNotifyHelper.title(-3.2, -10.6, 0));
    }

    @Test
    public void rainLaterTodayGivesTheStartHour() {
        String text = SummaryNotifyHelper.precipText(times(), chances(10, 70, 15), new double[24], NOW, OFFSET, false);
        assertEquals("Rain up to 70% after 3 PM", text);
        assertEquals("Rain up to 70% after 15:00",
                SummaryNotifyHelper.precipText(times(), chances(10, 70, 15), new double[24], NOW, OFFSET, true));
    }

    @Test
    public void rainAlreadyLikelyHasNoStartHour() {
        assertEquals("Rain up to 80%",
                SummaryNotifyHelper.precipText(times(), chances(40, 80, 12), new double[24], NOW, OFFSET, false));
    }

    @Test
    public void hoursAlreadyOverAreIgnored() {
        // 90% overnight only: by 7:10 AM the rest of the day is dry
        int[] p = chances(5, 5, 0);
        for (int i = 0; i < 6; i++) p[i] = 90;
        assertEquals("Rain unlikely", SummaryNotifyHelper.precipText(times(), p, new double[24], NOW, OFFSET, false));
    }

    @Test
    public void snowWordFollowsTheLikelyHours() {
        double[] snow = new double[24];
        for (int i = 18; i < 24; i++) snow[i] = 0.5;
        assertEquals("Snow up to 60% after 6 PM",
                SummaryNotifyHelper.precipText(times(), chances(0, 60, 18), snow, NOW, OFFSET, false));
        assertEquals("Rain or snow up to 60% after 12 PM",
                SummaryNotifyHelper.precipText(times(), chances(0, 60, 12), snow, NOW, OFFSET, false));
    }

    @Test
    public void noChancesGivesNoText() {
        int[] p = new int[24];
        java.util.Arrays.fill(p, -1);
        assertNull(SummaryNotifyHelper.precipText(times(), p, new double[24], NOW, OFFSET, false));
    }

    @Test
    public void hourText() {
        assertEquals("12 AM", SummaryNotifyHelper.hourText(0, false));
        assertEquals("12 PM", SummaryNotifyHelper.hourText(12 * 3600, false));
        assertEquals("09:00", SummaryNotifyHelper.hourText(9 * 3600, true));
    }
}
