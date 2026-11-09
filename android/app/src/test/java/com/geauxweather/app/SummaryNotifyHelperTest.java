package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SummaryNotifyHelperTest {

    private static final long OFFSET = -5 * 3600;                 // EST
    private static final long MIDNIGHT = 86400L * 20000 - OFFSET; // a local midnight, as UTC seconds
    private static final long NOW = MIDNIGHT + 7 * 3600 + 600;    // 7:10 AM local

    // Settings → Time forced to 12 or 24 hours (auto needs a Context); en-US PM text in these tests
    private static final java.text.DateFormat H12 = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US);
    private static final java.text.DateFormat H24 = NextHourNotifyHelper.timeFormat(null, "24");

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
        String text = SummaryNotifyHelper.precipText(times(), chances(10, 70, 15), new double[24], NOW, OFFSET, H12);
        assertEquals("Rain up to 70% after 3:00 PM", text);
        assertEquals("Rain up to 70% after 15:00",
                SummaryNotifyHelper.precipText(times(), chances(10, 70, 15), new double[24], NOW, OFFSET, H24));
    }

    @Test
    public void rainAlreadyLikelyHasNoStartHour() {
        assertEquals("Rain up to 80%",
                SummaryNotifyHelper.precipText(times(), chances(40, 80, 12), new double[24], NOW, OFFSET, H12));
    }

    @Test
    public void hoursAlreadyOverAreIgnored() {
        // 90% overnight only: by 7:10 AM the rest of the day is dry
        int[] p = chances(5, 5, 0);
        for (int i = 0; i < 6; i++) p[i] = 90;
        assertEquals("Rain unlikely", SummaryNotifyHelper.precipText(times(), p, new double[24], NOW, OFFSET, H12));
    }

    @Test
    public void snowWordFollowsTheLikelyHours() {
        double[] snow = new double[24];
        for (int i = 18; i < 24; i++) snow[i] = 0.5;
        assertEquals("Snow up to 60% after 6:00 PM",
                SummaryNotifyHelper.precipText(times(), chances(0, 60, 18), snow, NOW, OFFSET, H12));
        assertEquals("Rain or snow up to 60% after 12:00 PM",
                SummaryNotifyHelper.precipText(times(), chances(0, 60, 12), snow, NOW, OFFSET, H12));
    }

    @Test
    public void noChancesGivesNoText() {
        int[] p = new int[24];
        java.util.Arrays.fill(p, -1);
        assertNull(SummaryNotifyHelper.precipText(times(), p, new double[24], NOW, OFFSET, H12));
    }

    @Test
    public void unknownWeatherCodeLeavesOnlyTemperatures() {
        assertEquals("Today: 57°/44°", SummaryNotifyHelper.title(57, 44, -1));
    }

    @Test
    public void iconFollowsTheWeather() {
        assertEquals(R.drawable.ic_stat_sunny, SummaryNotifyHelper.icon(1));
        assertEquals(R.drawable.ic_stat_cloudy, SummaryNotifyHelper.icon(3));
        assertEquals(R.drawable.ic_stat_rain, SummaryNotifyHelper.icon(61));
        assertEquals(R.drawable.ic_stat_rain, SummaryNotifyHelper.icon(95));
    }

    @Test
    public void sentFromTheChosenHourUntilNoon() {
        assertEquals(false, SummaryNotifyHelper.inWindow(6, 7));
        assertEquals(true, SummaryNotifyHelper.inWindow(7, 7));
        assertEquals(true, SummaryNotifyHelper.inWindow(11, 7)); // phone first used at 11: Doze held the job
        assertEquals(false, SummaryNotifyHelper.inWindow(12, 7));
    }
}
