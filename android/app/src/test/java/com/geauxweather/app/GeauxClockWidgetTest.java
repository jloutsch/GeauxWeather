package com.geauxweather.app;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class GeauxClockWidgetTest {

    @Test
    public void clearNightShowsMoon() {
        assertEquals("🌙", GeauxClockWidgetProvider.iconFor(0, "☀️ Clear", false));
        assertEquals("🌙", GeauxClockWidgetProvider.iconFor(1, "🌤 Mainly clear", false));
    }

    @Test
    public void partlyCloudyNightShowsCloud() {
        assertEquals("☁️", GeauxClockWidgetProvider.iconFor(2, "⛅ Partly cloudy", false));
    }

    @Test
    public void dayUsesSnapshotEmoji() {
        assertEquals("☀️", GeauxClockWidgetProvider.iconFor(0, "☀️ Clear", true));
        assertEquals("🌧", GeauxClockWidgetProvider.iconFor(61, "🌧 Light rain", false));
    }

    @Test
    public void missingConditionFallsBackToCloud() {
        assertEquals("☁️", GeauxClockWidgetProvider.iconFor(-1, "Open app to load", true));
        assertEquals("☁️", GeauxClockWidgetProvider.iconFor(-1, null, true));
    }

    @Test
    public void clockAppsSortFirstThenByLabel() {
        List<ClockAppPickerActivity.AppEntry> apps = new ArrayList<>();
        apps.add(new ClockAppPickerActivity.AppEntry("b.maps", "Maps", null, false));
        apps.add(new ClockAppPickerActivity.AppEntry("a.clock", "Zen Clock", null, true));
        apps.add(new ClockAppPickerActivity.AppEntry("c.cal", "calendar", null, false));
        apps.add(new ClockAppPickerActivity.AppEntry("d.clock", "Clock", null, true));

        ClockAppPickerActivity.sortApps(apps);

        assertEquals("Clock", apps.get(0).label);
        assertEquals("Zen Clock", apps.get(1).label);
        assertEquals("calendar", apps.get(2).label);
        assertEquals("Maps", apps.get(3).label);
    }
}
