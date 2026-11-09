package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WidgetRefreshWorkerTest {

    @Test
    public void dayConditionsMatchWebSide() {
        assertEquals("☀️ Clear", WidgetRefreshWorker.condition(0, true));
        assertEquals("☁️ Overcast", WidgetRefreshWorker.condition(3, true));
        assertEquals("❄️ Snow", WidgetRefreshWorker.condition(73, true));
        assertEquals("⛈ Thunderstorm", WidgetRefreshWorker.condition(95, true));
    }

    @Test
    public void nightIconsMatchWebSide() {
        assertEquals("🌙 Clear", WidgetRefreshWorker.condition(0, false));
        assertEquals("🌙 Mainly clear", WidgetRefreshWorker.condition(1, false));
        assertEquals("☁️ Partly cloudy", WidgetRefreshWorker.condition(2, false));
        assertEquals("🌧 Rain showers", WidgetRefreshWorker.condition(80, false));
        assertEquals("☁️ Overcast", WidgetRefreshWorker.condition(3, false));
    }

    @Test
    public void unknownCodeFallsBack() {
        assertEquals("☁️ —", WidgetRefreshWorker.condition(-1, true));
    }

    @Test
    public void intervalFollowsWhatIsSwitchedOn() {
        assertEquals(15, WidgetRefreshWorker.intervalMinutes(true, true, true));   // storm active
        assertEquals(15, WidgetRefreshWorker.intervalMinutes(true, true, false));
        assertEquals(30, WidgetRefreshWorker.intervalMinutes(true, false, true));  // widget + alerts share runs
        assertEquals(30, WidgetRefreshWorker.intervalMinutes(false, false, true)); // widget only
        assertEquals(60, WidgetRefreshWorker.intervalMinutes(true, false, false)); // alerts only
        assertEquals(30, WidgetRefreshWorker.intervalMinutes(false, true, true));  // stale fast flag, alerts off
    }

    @Test
    public void alertsRunAboutHourlyOnThirtyMinuteRuns() {
        long min = 60 * 1000L;
        assertTrue(WidgetRefreshWorker.alertDue(60 * min, 0, false));
        assertFalse(WidgetRefreshWorker.alertDue(30 * min, 0, false)); // every other 30-min run
        assertTrue(WidgetRefreshWorker.alertDue(55 * min, 0, false));  // a run a few minutes early still checks
        assertTrue(WidgetRefreshWorker.alertDue(1 * min, 0, true));    // storm: every run
    }

    @Test
    public void onlyOfferedModelsReachTheRequest() {
        assertEquals("ecmwf_ifs025", WidgetRefreshWorker.forecastModel("ecmwf_ifs025"));
        assertEquals("gem_seamless", WidgetRefreshWorker.forecastModel("gem_seamless"));
        assertEquals(null, WidgetRefreshWorker.forecastModel("best_match"));
        assertEquals(null, WidgetRefreshWorker.forecastModel("x&latitude=0"));
        assertEquals(null, WidgetRefreshWorker.forecastModel(null));
    }
}
