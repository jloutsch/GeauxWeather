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
    public void uvLevelUsesWhoBandsLikeTheTodayTile() {
        assertEquals(0, GeauxWeatherWidgetProvider.uvLevel(0.2));   // Low: green
        assertEquals(1, GeauxWeatherWidgetProvider.uvLevel(2.6));   // Moderate: yellow
        assertEquals(2, GeauxWeatherWidgetProvider.uvLevel(6));     // High: orange
        assertEquals(3, GeauxWeatherWidgetProvider.uvLevel(8));     // Very high: red
        assertEquals(4, GeauxWeatherWidgetProvider.uvLevel(11));    // Extreme: violet
        assertEquals(-1, GeauxWeatherWidgetProvider.uvLevel(Double.NaN));
    }

    @Test
    public void aqiLevelFollowsTheUnitsScaleLikeTheTodayTile() {
        assertEquals(0, GeauxWeatherWidgetProvider.aqiLevel(42, true));
        assertEquals(2, GeauxWeatherWidgetProvider.aqiLevel(120, true));
        assertEquals(5, GeauxWeatherWidgetProvider.aqiLevel(320, true));
        assertEquals(0, GeauxWeatherWidgetProvider.aqiLevel(35, false));  // EU "Fair" shares the green
        assertEquals(1, GeauxWeatherWidgetProvider.aqiLevel(55, false));  // EU "Moderate"
        assertEquals(5, GeauxWeatherWidgetProvider.aqiLevel(120, false)); // EU "Extremely poor"
        assertEquals(-1, GeauxWeatherWidgetProvider.aqiLevel(Double.NaN, true));
    }

    @Test
    public void numberTextRoundsOrShowsDash() {
        assertEquals("3", GeauxWeatherWidgetProvider.numberText(2.6));
        assertEquals("—", GeauxWeatherWidgetProvider.numberText(Double.NaN));
    }

    @Test
    public void levelColorsMatchTheTodayTileCss() throws Exception {
        String css = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../../www/css/app.css")),
                java.nio.charset.StandardCharsets.UTF_8);
        String[] keys = {"good", "moderate", "usg", "unhealthy", "very", "hazardous"};
        for (int i = 0; i < keys.length; i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\.metric\\.aqi-" + keys[i] + " \\.metric-value \\{ color: #([0-9a-fA-F]{6}); \\}")
                    .matcher(css);
            org.junit.Assert.assertTrue("app.css no longer colors aqi-" + keys[i], m.find());
            assertEquals(keys[i], 0xFF000000 | Integer.parseInt(m.group(1), 16), GeauxWeatherWidgetProvider.LEVEL_COLORS[i]);
        }
    }
}
