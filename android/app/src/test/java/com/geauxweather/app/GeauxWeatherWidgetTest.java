package com.geauxweather.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GeauxWeatherWidgetTest {
    @Test
    public void defaultTwoByOneShowsNoExtras() {
        assertEquals(GeauxWeatherWidgetProvider.EXTRAS_NONE, GeauxWeatherWidgetProvider.extrasMode(180, 80));
    }

    @Test
    public void wideShortWidgetShowsExtrasOnTheSide() {
        assertEquals(GeauxWeatherWidgetProvider.EXTRAS_SIDE, GeauxWeatherWidgetProvider.extrasMode(350, 80));
    }

    @Test
    public void tallWidgetShowsExtrasBelow() {
        assertEquals(GeauxWeatherWidgetProvider.EXTRAS_BELOW, GeauxWeatherWidgetProvider.extrasMode(180, 180));
        assertEquals(GeauxWeatherWidgetProvider.EXTRAS_BELOW, GeauxWeatherWidgetProvider.extrasMode(350, 180));
    }

    @Test
    public void unknownSizeShowsNoExtras() {
        assertEquals(GeauxWeatherWidgetProvider.EXTRAS_NONE, GeauxWeatherWidgetProvider.extrasMode(0, 0));
    }

    @Test
    public void highLowSizeAddsToEachWidgetsOwnSize() {
        assertEquals(0, GeauxWeatherWidgetProvider.hiLowExtraSp("small"), 0);
        assertEquals(3, GeauxWeatherWidgetProvider.hiLowExtraSp("medium"), 0);
        assertEquals(6, GeauxWeatherWidgetProvider.hiLowExtraSp("large"), 0);
        assertEquals(0, GeauxWeatherWidgetProvider.hiLowExtraSp(null), 0);
        assertEquals(0, GeauxWeatherWidgetProvider.hiLowExtraSp("huge"), 0);
    }

    @Test
    public void uvTextUsesWhoBands() {
        assertEquals("UV 0 · Low", GeauxWeatherWidgetProvider.uvText(0.2));
        assertEquals("UV 3 · Moderate", GeauxWeatherWidgetProvider.uvText(2.6));
        assertEquals("UV 11 · Extreme", GeauxWeatherWidgetProvider.uvText(11));
        assertEquals("UV —", GeauxWeatherWidgetProvider.uvText(Double.NaN));
    }

    @Test
    public void aqiTextFollowsTheUnitsScale() {
        assertEquals("AQI 42 · Good", GeauxWeatherWidgetProvider.aqiText(42, true));
        assertEquals("AQI 151 · Unhealthy", GeauxWeatherWidgetProvider.aqiText(151, true));
        assertEquals("EU AQI 35 · Fair", GeauxWeatherWidgetProvider.aqiText(35, false));
        assertEquals("AQI —", GeauxWeatherWidgetProvider.aqiText(Double.NaN, true));
        assertEquals("EU AQI —", GeauxWeatherWidgetProvider.aqiText(Double.NaN, false));
    }
}
