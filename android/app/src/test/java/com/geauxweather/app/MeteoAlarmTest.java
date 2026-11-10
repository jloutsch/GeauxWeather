package com.geauxweather.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Mirrors tests/eu-alerts.test.js for the native background check. */
public class MeteoAlarmTest {

    private static final long NOW = HurricaneAlertHelper.parseIsoMillis("2026-11-10T12:00:00Z");
    private static final String DUBLIN_MATCH =
            "{\"lat\":53.35,\"lon\":-6.26,\"feed\":\"ireland\",\"codes\":[\"FIPS:EI07\",\"EMMA_ID:IE001\"]}";

    /** One warning shaped like the live feed, with French and English text. */
    private static JSONObject warning(String id, String status, String msgType, String event, String severity,
                                      String sent, String expires, JSONObject area) throws Exception {
        JSONArray infos = new JSONArray();
        for (String lang : new String[]{"fr-FR", "en-GB"}) {
            JSONObject info = new JSONObject();
            info.put("language", lang);
            info.put("event", lang.startsWith("en") ? event : "Vent jaune");
            info.put("severity", severity);
            info.put("headline", event + " warning");
            info.put("description", "Gusts of 90 km/h.");
            info.put("instruction", lang.startsWith("en") ? "Secure loose objects." : "Sécurisez les objets.");
            info.put("senderName", "Met Éireann");
            info.put("expires", expires);
            info.put("area", new JSONArray().put(area));
            infos.put(info);
        }
        JSONObject alert = new JSONObject();
        alert.put("identifier", id);
        alert.put("status", status);
        alert.put("msgType", msgType);
        alert.put("sent", sent);
        alert.put("info", infos);
        return new JSONObject().put("alert", alert);
    }

    private static JSONObject codeArea(String desc, String type, String value) throws Exception {
        return new JSONObject().put("areaDesc", desc)
                .put("geocode", new JSONArray().put(new JSONObject().put("valueName", type).put("value", value)));
    }

    private static JSONObject dublinWarning(String id) throws Exception {
        return warning(id, "Actual", "Alert", "Yellow Wind", "Moderate", "2026-11-10T06:00:00+00:00",
                "2026-11-11T06:00:00+00:00", codeArea("Dublin", "FIPS", "EI07"));
    }

    private static JSONArray parse(JSONObject... warnings) throws Exception {
        JSONArray ws = new JSONArray();
        for (JSONObject w : warnings) ws.put(w);
        return HurricaneAlertHelper.fromMeteoAlarm(new JSONObject().put("warnings", ws),
                HurricaneAlertHelper.euMatchFor(DUBLIN_MATCH, 53.35, -6.26), 53.35, -6.26, NOW);
    }

    @Test
    public void warningsBecomeNwsStyleAlertsInEnglish() throws Exception {
        JSONArray out = parse(dublinWarning("a1"));
        assertEquals(1, out.length());
        JSONObject a = out.getJSONObject(0);
        assertEquals("Yellow Wind", a.getString("event"));
        assertEquals("Moderate", a.getString("severity"));
        assertEquals("Secure loose objects.", a.getString("instruction"));
        assertEquals("Dublin", a.getString("areaDesc"));
        assertEquals("2026-11-11T06:00:00+00:00", a.getString("ends"));
        assertEquals("2026-11-10T06:00:00+00:00", a.getString("sent"));
        assertEquals("MeteoAlarm", a.getString("source"));
        assertEquals("Met Éireann", a.getString("sender"));
        assertTrue(a.getString("id").startsWith("ma:ireland|"));
    }

    @Test
    public void keepsOnlyCurrentRealWarningsForThisArea() throws Exception {
        JSONArray out = parse(
                warning("other-county", "Actual", "Alert", "Yellow Wind", "Moderate", "2026-11-10T06:00:00+00:00",
                        "2026-11-11T06:00:00+00:00", codeArea("Kerry", "FIPS", "EI11")),
                warning("same-value-other-type", "Actual", "Alert", "Yellow Wind", "Moderate",
                        "2026-11-10T06:00:00+00:00", "2026-11-11T06:00:00+00:00", codeArea("X", "NUTS3", "EI07")),
                warning("ended", "Actual", "Alert", "Yellow Wind", "Moderate", "2026-11-10T06:00:00+00:00",
                        "2026-11-10T11:00:00+00:00", codeArea("Dublin", "FIPS", "EI07")),
                warning("exercise", "Exercise", "Alert", "Yellow Wind", "Moderate", "2026-11-10T06:00:00+00:00",
                        "2026-11-11T06:00:00+00:00", codeArea("Dublin", "FIPS", "EI07")),
                warning("cancel", "Actual", "Cancel", "Yellow Wind", "Moderate", "2026-11-10T06:00:00+00:00",
                        "2026-11-11T06:00:00+00:00", codeArea("Dublin", "FIPS", "EI07")),
                dublinWarning("keep"));
        assertEquals(1, out.length());
        assertEquals("keep", out.getJSONObject(0).getString("identifier"));
    }

    @Test
    public void outlinedWarningsMatchByPoint() throws Exception {
        String box = "51.9,4.8 51.9,5.3 52.2,5.3 52.2,4.8 51.9,4.8";
        JSONObject in = warning("in", "Actual", "Alert", "Moderate Fog", "Moderate", "2026-11-10T06:00:00+00:00",
                "2026-11-11T06:00:00+00:00",
                new JSONObject().put("areaDesc", "Utrecht").put("polygon", new JSONArray().put(box)));
        JSONObject match = HurricaneAlertHelper.euMatchFor(
                "{\"lat\":52.09,\"lon\":5.12,\"feed\":\"netherlands\",\"codes\":[]}", 52.09, 5.12);
        JSONArray out = HurricaneAlertHelper.fromMeteoAlarm(
                new JSONObject().put("warnings", new JSONArray().put(in)), match, 52.09, 5.12, NOW);
        assertEquals(1, out.length());
        assertTrue(HurricaneAlertHelper.inCapPolygon(box, 52.09, 5.12));
        assertFalse(HurricaneAlertHelper.inCapPolygon(box, 53.2, 6.57)); // Groningen
    }

    @Test
    public void reissuedWarningKeepsItsIdAndEscalationChangesIt() throws Exception {
        String v1 = parse(dublinWarning("v1")).getJSONObject(0).getString("id");
        String v2 = parse(dublinWarning("v2")).getJSONObject(0).getString("id");
        assertEquals(v1, v2);
        String orange = parse(warning("o", "Actual", "Alert", "Orange Wind", "Severe", "2026-11-10T06:00:00+00:00",
                "2026-11-11T06:00:00+00:00", codeArea("Dublin", "FIPS", "EI07"))).getJSONObject(0).getString("id");
        assertNotEquals(v1, orange);
    }

    @Test
    public void savedAreaAppliesOnlyToTheLocationItWasWorkedOutFor() {
        assertNotNull(HurricaneAlertHelper.euMatchFor(DUBLIN_MATCH, 53.351, -6.259));
        assertNull(HurricaneAlertHelper.euMatchFor(DUBLIN_MATCH, 48.86, 2.35)); // moved to Paris
        assertNull(HurricaneAlertHelper.euMatchFor(null, 53.35, -6.26));
        assertNull(HurricaneAlertHelper.euMatchFor("not json", 53.35, -6.26));
    }

    @Test
    public void parsesFeedTimesWithAnOffset() {
        assertEquals(HurricaneAlertHelper.parseIsoMillis("2026-11-10T12:00:00Z"),
                HurricaneAlertHelper.parseIsoMillis("2026-11-10T13:00:00+01:00"));
        assertEquals(-1, HurricaneAlertHelper.parseIsoMillis("soon"));
    }
}
