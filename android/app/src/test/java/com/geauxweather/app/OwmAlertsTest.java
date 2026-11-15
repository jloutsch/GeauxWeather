package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Mirrors the OpenWeatherMap tests in tests/alerts.test.js for the native background check. */
public class OwmAlertsTest {

    private static final long NOW_S = 1794780000L; // 2026-11-15T22:00:00Z

    private static JSONObject alert(String sender, String event, long start, long end) throws Exception {
        return new JSONObject().put("sender_name", sender).put("event", event)
                .put("start", start).put("end", end).put("description", "Details.");
    }

    @Test
    public void severityFromTheAlertName() {
        assertEquals("Extreme", HurricaneAlertHelper.owmSeverity("Red warning of wind"));
        assertEquals("Severe", HurricaneAlertHelper.owmSeverity("Amber warning of snow"));
        assertEquals("Severe", HurricaneAlertHelper.owmSeverity("Orange Thunderstorm warning"));
        assertEquals("Moderate", HurricaneAlertHelper.owmSeverity("Yellow warning of rain"));
        assertEquals("Moderate", HurricaneAlertHelper.owmSeverity("Yellow Flood Warning"));
        assertEquals("Severe", HurricaneAlertHelper.owmSeverity("Severe Thunderstorm Warning"));
        assertEquals("Severe", HurricaneAlertHelper.owmSeverity("Heavy rain warning"));
        assertEquals("Severe", HurricaneAlertHelper.owmSeverity("Tropical Cyclone Advice"));
        assertEquals("Moderate", HurricaneAlertHelper.owmSeverity("Heavy rain advisory"));
        assertEquals("Minor", HurricaneAlertHelper.owmSeverity("Hydrological information"));
    }

    @Test
    public void alertsBecomeNwsStyleAlertsAndEndedOnesAreDropped() throws Exception {
        JSONObject json = new JSONObject().put("alerts", new JSONArray()
                .put(alert("Australian Government Bureau of Meteorology", "Severe Thunderstorm Warning",
                        NOW_S - 3600, NOW_S + 3600))
                .put(alert("Old", "Flood Warning", NOW_S - 9000, NOW_S - 60)));
        JSONArray out = HurricaneAlertHelper.fromOwm(json, NOW_S * 1000);
        assertEquals(1, out.length());
        JSONObject a = out.getJSONObject(0);
        assertEquals("Severe Thunderstorm Warning", a.getString("event"));
        assertEquals("Severe", a.getString("severity"));
        assertEquals("OpenWeatherMap", a.getString("source"));
        assertEquals("Australian Government Bureau of Meteorology", a.getString("sender"));
        assertEquals("2026-11-15T23:00:00Z", a.getString("ends"));
        assertEquals("owm:Australian Government Bureau of Meteorology|Severe Thunderstorm Warning|"
                + (NOW_S - 3600), a.getString("id"));
        assertEquals(0, HurricaneAlertHelper.fromOwm(new JSONObject(), NOW_S * 1000).length());
    }

    @Test
    public void notifiesByMappedSeverityNotUsKeywords() throws Exception {
        JSONObject yellow = HurricaneAlertHelper.fromOwm(new JSONObject().put("alerts", new JSONArray()
                .put(alert("Met Office", "Yellow Flood Warning", NOW_S, NOW_S + 60))), NOW_S * 1000).getJSONObject(0);
        assertFalse(HurricaneAlertHelper.shouldNotify(yellow, false));
        assertTrue(HurricaneAlertHelper.shouldNotify(yellow, true));
    }

    // Regression: ISSUE-004 — Brazil's INMET names alerts in Portuguese ("Tempestade"); unmatched names fell to Minor
    // Found by /qa on 2026-11-15 (live OpenWeatherMap key, São Paulo)
    // Report: .gstack/qa-reports/qa-report-owm-key-2026-11-15.md
    @Test
    public void unrecognisedNamesDefaultToModerate() throws Exception {
        JSONObject inmet = HurricaneAlertHelper.fromOwm(new JSONObject().put("alerts", new JSONArray()
                .put(alert("Instituto Nacional de Meteorologia", "Tempestade", NOW_S - 600, NOW_S + 3600))),
                NOW_S * 1000).getJSONObject(0);
        assertEquals("Moderate", inmet.getString("severity"));
        assertFalse(HurricaneAlertHelper.shouldNotify(inmet, false));
        assertTrue(HurricaneAlertHelper.shouldNotify(inmet, true));
        assertEquals("Extreme", HurricaneAlertHelper.owmSeverity("Alerta roja por lluvias"));
        assertEquals("Severe", HurricaneAlertHelper.owmSeverity("Aviso laranja de chuva"));
        assertEquals("Severe", HurricaneAlertHelper.owmSeverity("Alerta naranja"));
        assertEquals("Moderate", HurricaneAlertHelper.owmSeverity("Aviso amarelo"));
        assertEquals("Minor", HurricaneAlertHelper.owmSeverity("Coastal flood statement"));
        assertEquals("Minor", HurricaneAlertHelper.owmSeverity("Fire weather outlook"));
    }

    // Regression: ISSUE-002 — OpenWeatherMap alerts have no area name; the notification read "Severe ·  · near Sydney"
    // Found by /qa on 2026-11-15
    // Report: .gstack/qa-reports/qa-report-owm-key-2026-11-15.md
    @Test
    public void notificationTextSkipsAMissingAreaName() {
        assertEquals("Severe · near Sydney", HurricaneAlertHelper.notificationText("Severe", "", "Sydney"));
        assertEquals("Severe · Middlesex, MA · near Stoneham",
                HurricaneAlertHelper.notificationText("Severe", "Middlesex, MA", "Stoneham"));
        assertEquals("Middlesex, MA", HurricaneAlertHelper.notificationText("", "Middlesex, MA", null));
    }

    @Test
    public void canadaBoxKeepsEnvironmentCanada() {
        assertTrue(HurricaneAlertHelper.inCanada(53.25, -132.07)); // Haida Gwaii
        assertTrue(HurricaneAlertHelper.inCanada(45.5, -73.57)); // Montreal
        assertFalse(HurricaneAlertHelper.inCanada(-33.87, 151.21)); // Sydney
        assertFalse(HurricaneAlertHelper.inCanada(51.5, -0.13)); // London
    }

    @Test
    public void urlCarriesTheKeyEncoded() {
        String url = HurricaneAlertHelper.owmUrl(-33.87, 151.21, "a b&c");
        assertTrue(url.startsWith("https://api.openweathermap.org/data/3.0/onecall?"));
        assertTrue(url.contains("lat=-33.8700&lon=151.2100"));
        assertTrue(url.endsWith("&appid=a+b%26c"));
    }
}
