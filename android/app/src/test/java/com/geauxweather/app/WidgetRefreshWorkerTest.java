package com.geauxweather.app;

import static org.junit.Assert.assertEquals;

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
}
