/*
 * GeauxWeather
 * Copyright (C) 2026 TuneFriend / James
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.geauxweather.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Severe storm alerts for the user's home/default location:
 * - NWS point alerts: tornado, severe thunderstorm, tropical
 * - NHC storms within ~500 mi of that location
 *
 * Settings key remains geauxweather_hurricane_alerts ("on"/"off") for compatibility.
 */
public final class HurricaneAlertHelper {

    static final String CHANNEL_ID = "geauxweather_severe";
    static final String WORK_NAME = "geauxweather_severe_alerts";
    private static final String PREFS = Notify.PREFS;
    private static final String KEY_ENABLED = "geauxweather_hurricane_alerts";
    private static final String KEY_SEEN = "geauxweather_severe_seen_native";
    private static final String KEY_LOC = "geauxweather_location";
    private static final String KEY_DEFAULT = "geauxweather_default";
    /** "advisories" also notifies on NWS Moderate alerts; anything else = warnings only. Set from Settings. */
    private static final String KEY_LEVEL = "geauxweather_alert_level";
    /** "on" while a storm-type alert/watch or nearby tropical storm is active → check every 15 min. */
    private static final String KEY_FAST = "geauxweather_alert_fast";
    private static final String KEY_PENDING_ALERT = "geauxweather_pending_alert";
    /** Epoch ms of the last completed check (background or app open), so the shared job can skip one. */
    private static final String KEY_LAST_CHECK = "geauxweather_alert_last_check";
    public static final String EXTRA_ALERT_JSON = "geauxweather_alert_json";
    public static final String ACTION_OPEN_ALERT = "com.geauxweather.app.OPEN_ALERT";
    private static final String NHC_URL = "https://www.nhc.noaa.gov/CurrentStorms.json";
    /** {lat, lon, feed, codes}: the MeteoAlarm areas for the home location, saved by eu-alerts.js. */
    private static final String KEY_EU_AREA = "geauxweather_eu_area";
    private static final String MA_FEED_URL = "https://feeds.meteoalarm.org/api/v1/warnings/feeds-";
    private static final int BASE_NOTIF_ID = 7000;
    /** Tropical systems farther than this from home are ignored (miles). */
    private static final double TROPICAL_RADIUS_MI = 500.0;

    private HurricaneAlertHelper() {}

    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return "on".equals(prefs.getString(KEY_ENABLED, "off"));
    }

    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_ENABLED, enabled ? "on" : "off").apply();
        if (enabled) {
            check(context, true);
            schedule(context);
        } else {
            cancelSchedule(context);
        }
    }

    /**
     * Alerts run inside the shared background job (WidgetRefreshWorker) so they don't wake the phone
     * separately: hourly, or every 15 min while a storm is active (see isFast). This also retires the
     * old alert-only jobs on existing installs.
     */
    public static void schedule(Context context) {
        cancelOldJobs(context);
        WidgetRefreshWorker.ensureScheduled(context);
    }

    /** Alerts off: the shared job slows down, or stops if nothing else needs it. */
    public static void cancelSchedule(Context context) {
        cancelOldJobs(context);
        WidgetRefreshWorker.ensureScheduled(context);
    }

    private static void cancelOldJobs(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        WorkManager.getInstance(context).cancelUniqueWork("geauxweather_hurricane_alerts");
    }

    /**
     * 15-min checks (WorkManager's minimum) while a storm-type alert/watch or nearby tropical storm
     * is active: warnings often last 30–45 min. Otherwise hourly to save battery.
     * shortcut: a warning issued with no prior watch can wait up to ~60 min; upgrade to push alerts if that matters.
     */
    static boolean isFast(Context context) {
        return "on".equals(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAST, "off"));
    }

    /**
     * Only a phone that has never completed a check stays quiet (it would otherwise announce every
     * alert already in effect). An empty seen record alone is normal after a quiet check, and treating
     * it as a first run swallowed the first warning after every all-clear.
     */
    static boolean isFirstRun(long lastCheckMs, int seenCount) {
        return lastCheckMs == 0 && seenCount == 0;
    }

    static long lastCheckMs(Context context) {
        try {
            return Long.parseLong(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_CHECK, "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    /** @param seedOnly if true, update seen state without notifications */
    public static void check(Context context, boolean seedOnly) {
        if (!isEnabled(context) && !seedOnly) return;
        ensureChannel(context);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        double[] loc = resolveLocation(prefs);
        if (loc == null) {
            // No home/default location yet — nothing location-specific to alert on
            return;
        }
        double lat = loc[0];
        double lon = loc[1];
        String placeLabel = resolveLocationLabel(prefs);

        JSONObject prev = loadSeen(prefs);
        JSONObject next = new JSONObject();
        boolean firstRun = isFirstRun(lastCheckMs(context), prev.length());
        boolean quiet = seedOnly || firstRun;

        boolean nwsOk = false;
        boolean nhcOk = false;
        try {
            nwsOk = checkNwsPoint(context, lat, lon, placeLabel, prev, next, quiet);
        } catch (Exception ignored) {
        }
        try {
            nhcOk = checkNhCNearby(context, lat, lon, placeLabel, prev, next, quiet);
        } catch (Exception ignored) {
        }

        // A failed feed keeps its previous entries; otherwise the next run would re-notify them, or
        // (record emptied) count as a quiet "first run" and silently swallow a new warning.
        try {
            java.util.Iterator<String> keys = prev.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ((!nwsOk && key.startsWith("nws:")) || (!nhcOk && key.startsWith("nhc:"))) {
                    next.put(key, prev.get(key));
                }
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_SEEN, next.toString())
                .putString(KEY_LAST_CHECK, String.valueOf(System.currentTimeMillis())).apply();
        android.util.Log.i(WidgetRefreshWorker.TAG, "alerts: nws " + (nwsOk ? "ok" : "failed") + ", nhc "
                + (nhcOk ? "ok" : "failed") + ", " + next.length() + " active" + (quiet ? " (first run, quiet)" : ""));

        Boolean fast = nextFastMode(stormActive(next), nwsOk && nhcOk);
        String wanted = fast == null ? null : (fast ? "on" : "off");
        if (wanted != null && !wanted.equals(prefs.getString(KEY_FAST, "off"))) {
            prefs.edit().putString(KEY_FAST, wanted).apply();
            if (isEnabled(context)) schedule(context);
        }
    }

    /**
     * Speed up as soon as a storm is seen; slow down only when both feeds answered and
     * showed nothing, so a failed request never drops to hourly during an active watch.
     * @return true = 15 min, false = 60 min, null = keep the current interval
     */
    static Boolean nextFastMode(boolean stormActive, boolean bothFeedsOk) {
        if (stormActive) return true;
        return bothFeedsOk ? Boolean.FALSE : null;
    }

    /** Any storm-type NWS alert or watch (tornado, thunderstorm, flood, tropical…) or a nearby NHC storm. */
    static boolean stormActive(JSONObject seen) {
        java.util.Iterator<String> keys = seen.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("nhc:")) return true;
            JSONObject fp = seen.optJSONObject(key);
            if (fp != null && isSevereEvent(fp.optString("event", ""))) return true;
        }
        return false;
    }

    public static void showAlert(Context context, String title, String body, String tag) {
        showAlert(context, title, body, tag, null);
    }

    public static void showAlert(Context context, String title, String body, String tag, JSONObject detail) {
        ensureChannel(context);
        int id = BASE_NOTIF_ID + Math.abs((tag != null ? tag : title).hashCode() % 500);
        if (detail == null) {
            detail = new JSONObject();
            try {
                detail.put("kind", "generic");
                detail.put("key", tag != null ? tag : title);
                detail.put("event", title != null ? title : "Severe weather");
                detail.put("headline", title != null ? title : "");
                detail.put("description", body != null ? body : "");
                detail.put("layer", "tornado");
            } catch (Exception ignored) {
            }
        }
        post(context, id, title, body, detail);
    }

    /** Pending alert JSON for MainActivity → WebView deep link (cleared when consumed). */
    public static String takePendingAlertJson(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_PENDING_ALERT, null);
        if (raw != null) prefs.edit().remove(KEY_PENDING_ALERT).apply();
        return raw;
    }

    // ─── NWS local alerts ─────────────────────────────────────────────────

    private static boolean checkNwsPoint(
            Context context,
            double lat,
            double lon,
            String placeLabel,
            JSONObject prev,
            JSONObject next,
            boolean quiet
    ) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray alerts = fetchPointAlerts(lat, lon, prefs.getString(KEY_EU_AREA, null));
        if (alerts == null) return false;
        boolean includeAdvisories = "advisories".equals(prefs.getString(KEY_LEVEL, "warnings"));

        for (int i = 0; i < alerts.length(); i++) {
            JSONObject props = alerts.optJSONObject(i);
            if (props == null) continue;
            String event = props.optString("event", "");
            if (!shouldNotify(event, props.optString("severity", ""), includeAdvisories)) continue;

            String alertId = props.optString("id", "");
            if (alertId.isEmpty()) {
                alertId = event + "|" + props.optString("sent", "") + "|" + props.optString("areaDesc", "");
            }
            String key = "nws:" + alertId;
            String headline = props.optString("headline", event);
            String area = props.optString("areaDesc", placeLabel);
            String severity = props.optString("severity", "");

            JSONObject fp = new JSONObject();
            fp.put("event", event);
            fp.put("headline", headline);
            next.put(key, fp);

            if (quiet) continue;
            if (prev.optJSONObject(key) != null) continue;

            String title = event;
            String text = area;
            if (!severity.isEmpty()) text = severity + " · " + text;
            if (placeLabel != null && !placeLabel.isEmpty()) {
                text = text + " · near " + placeLabel;
            }
            String description = props.optString("description", "");
            String instruction = props.optString("instruction", "");
            String ends = props.optString("ends", props.optString("expires", ""));
            String more = text;
            if (!instruction.isEmpty()) {
                more = text + "\n\n" + trimLen(instruction, 400);
            } else if (!description.isEmpty()) {
                more = text + "\n\n" + trimLen(description, 400);
            }
            JSONObject detail = new JSONObject();
            try {
                detail.put("kind", "nws");
                detail.put("key", key);
                detail.put("event", event);
                detail.put("headline", headline);
                detail.put("area", area);
                detail.put("severity", severity);
                detail.put("description", trimLen(description, 1200));
                detail.put("instruction", trimLen(instruction, 800));
                detail.put("ends", ends);
                detail.put("source", props.optString("source", ""));
                detail.put("sender", props.optString("sender", ""));
                detail.put("sent", props.optString("sent", ""));
                detail.put("lat", lat);
                detail.put("lon", lon);
                detail.put("place", placeLabel != null ? placeLabel : "");
                // The Tornado layer shows NWS (U.S.) alerts only; other countries' alerts open the radar
                String layer = mapLayerForEvent(event);
                detail.put("layer", props.has("source") && "tornado".equals(layer) ? "radar" : layer);
            } catch (Exception ignored) {
            }
            post(
                    context,
                    BASE_NOTIF_ID + Math.abs(key.hashCode() % 500),
                    title,
                    more,
                    detail
            );
        }
        return true;
    }

    /**
     * Alerts for a point as NWS-style properties, or null when the feed failed. NWS covers the U.S.
     * and answers 400 for a point it doesn't; then the DWD (via Bright Sky) in Germany, MeteoAlarm in
     * the other EU countries (areas worked out by eu-alerts.js and saved as euArea), else Environment
     * Canada (empty outside Canada). Same logic as fetchPointAlerts in hurricane-alerts.js.
     */
    static JSONArray fetchPointAlerts(double lat, double lon, String euArea) throws Exception {
        HttpResult nws = httpGetResult(String.format(Locale.US,
                "https://api.weather.gov/alerts/active?status=actual&point=%.4f,%.4f", lat, lon),
                "application/geo+json,application/json");
        if (nws.ok()) {
            JSONArray out = new JSONArray();
            JSONArray features = new JSONObject(nws.body).optJSONArray("features");
            for (int i = 0; features != null && i < features.length(); i++) {
                JSONObject f = features.optJSONObject(i);
                if (f != null && f.optJSONObject("properties") != null) out.put(f.optJSONObject("properties"));
            }
            return out;
        }
        if (nws.code != 400) return null; // a real failure, not "outside the U.S."
        if (inGermany(lat, lon)) {
            HttpResult dwd = httpGetResult(String.format(Locale.US,
                    "https://api.brightsky.dev/alerts?lat=%.4f&lon=%.4f", lat, lon), "application/json");
            // 404: not covered by the DWD. The box also holds Prague, Luxembourg, Strasbourg…: try MeteoAlarm next
            if (dwd.code != 404) return dwd.ok() ? fromBrightSky(new JSONObject(dwd.body)) : null;
        }
        JSONObject eu = euMatchFor(euArea, lat, lon);
        if (eu != null) {
            HttpResult ma = httpGetResult(MA_FEED_URL + eu.optString("feed"), "application/json");
            return ma.ok() ? fromMeteoAlarm(new JSONObject(ma.body), eu, lat, lon, System.currentTimeMillis()) : null;
        }
        double d = 0.001;
        HttpResult eccc = httpGetResult(String.format(Locale.US,
                "https://api.weather.gc.ca/collections/weather-alerts/items?f=json&lang=en&bbox=%.4f,%.4f,%.4f,%.4f",
                lon - d, lat - d, lon + d, lat + d), "application/json");
        return eccc.ok() ? fromEccc(new JSONObject(eccc.body)) : null;
    }

    /** Germany, roughly; Bright Sky answers "not covered" for the bits of neighbours this box includes. */
    static boolean inGermany(double lat, double lon) {
        return lat >= 47.2 && lat <= 55.1 && lon >= 5.8 && lon <= 15.1;
    }

    /** "winter storm warning" → "Winter Storm Warning" (also after hyphens). */
    static String titleCase(String s) {
        StringBuilder b = new StringBuilder(s == null ? "" : s);
        for (int i = 0; i < b.length(); i++) {
            if (i == 0 || b.charAt(i - 1) == ' ' || b.charAt(i - 1) == '-') b.setCharAt(i, Character.toUpperCase(b.charAt(i)));
        }
        return b.toString();
    }

    /** Environment Canada alert type → NWS severity: warnings/watches notify, advisories with "+ advisories", statements are skipped (like NWS Minor alerts). */
    static String ecccSeverity(String type) {
        if ("warning".equals(type) || "watch".equals(type)) return "Severe";
        return "advisory".equals(type) ? "Moderate" : "Minor";
    }

    private static JSONArray fromEccc(JSONObject geo) throws Exception {
        JSONArray out = new JSONArray();
        JSONArray features = geo.optJSONArray("features");
        for (int i = 0; features != null && i < features.length(); i++) {
            JSONObject f = features.optJSONObject(i);
            JSONObject p = f != null ? f.optJSONObject("properties") : null;
            if (p == null || p.optString("status_en", "").toLowerCase(Locale.US).matches(".*(ended|cancel).*")) continue;
            String event = titleCase(p.optString("alert_name_en", "weather alert"));
            JSONObject a = new JSONObject();
            // Re-issues get new ids; name + area notifies once per alert per area until it ends
            a.put("id", "eccc:" + p.optString("alert_name_en") + "|" + p.optString("feature_id", p.optString("feature_name_en")));
            a.put("event", event);
            a.put("severity", ecccSeverity(p.optString("alert_type")));
            a.put("headline", event + " in effect");
            a.put("areaDesc", p.optString("feature_name_en", ""));
            a.put("description", p.optString("alert_text_en", ""));
            a.put("ends", p.optString("event_end_datetime", p.optString("expiration_datetime", "")));
            a.put("sent", p.optString("publication_datetime", ""));
            a.put("source", "Environment Canada");
            out.put(a);
        }
        return out;
    }

    private static JSONArray fromBrightSky(JSONObject json) throws Exception {
        JSONArray out = new JSONArray();
        JSONObject loc = json.optJSONObject("location");
        String place = loc != null ? loc.optString("name", loc.optString("district", "")) : "";
        String cell = loc != null ? loc.optString("warn_cell_id", "") : "";
        JSONArray alerts = json.optJSONArray("alerts");
        for (int i = 0; alerts != null && i < alerts.length(); i++) {
            JSONObject al = alerts.optJSONObject(i);
            if (al == null || "test".equals(al.optString("status"))) continue;
            JSONObject a = new JSONObject();
            // DWD updates get new ids; event + severity + warning area notifies again only when it escalates
            a.put("id", "dwd:" + al.optString("event_code") + "|" + al.optString("severity") + "|" + cell);
            a.put("event", titleCase(al.optString("event_en", "weather warning")));
            a.put("severity", titleCase(al.optString("severity", "minor")));
            a.put("headline", al.optString("headline_en", ""));
            a.put("areaDesc", place);
            a.put("description", al.optString("description_en", ""));
            a.put("instruction", al.optString("instruction_en", ""));
            a.put("ends", al.optString("expires", ""));
            a.put("sent", al.optString("effective", ""));
            a.put("source", "DWD");
            out.put(a);
        }
        return out;
    }

    // ─── MeteoAlarm (EU countries; mirrors eu-alerts.js) ─────────────────

    /** The saved MeteoAlarm areas, or null when none are saved or they were worked out for another place. */
    static JSONObject euMatchFor(String saved, double lat, double lon) {
        if (saved == null || saved.isEmpty()) return null;
        try {
            JSONObject m = new JSONObject(saved);
            if (m.optString("feed", "").isEmpty()) return null;
            if (Math.abs(m.getDouble("lat") - lat) > 0.01 || Math.abs(m.getDouble("lon") - lon) > 0.01) return null;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    /** Feed JSON → NWS-style alert properties for the warnings covering this point (see eu-alerts.js). */
    static JSONArray fromMeteoAlarm(JSONObject json, JSONObject match, double lat, double lon, long now)
            throws Exception {
        java.util.Set<String> codes = new java.util.HashSet<>();
        JSONArray saved = match.optJSONArray("codes");
        for (int i = 0; saved != null && i < saved.length(); i++) codes.add(saved.optString(i));
        String feed = match.optString("feed", "");

        JSONArray out = new JSONArray();
        JSONArray warnings = json.optJSONArray("warnings");
        for (int w = 0; warnings != null && w < warnings.length(); w++) {
            JSONObject wo = warnings.optJSONObject(w);
            JSONObject alert = wo != null ? wo.optJSONObject("alert") : null;
            if (alert == null || !"Actual".equals(alert.optString("status"))
                    || "Cancel".equals(alert.optString("msgType"))) continue;
            JSONArray infos = alert.optJSONArray("info");
            JSONObject info = null;
            for (int i = 0; infos != null && i < infos.length(); i++) {
                JSONObject cand = infos.optJSONObject(i);
                if (cand == null) continue;
                if (info == null) info = cand;
                if (cand.optString("language", "").toLowerCase(Locale.US).startsWith("en")) {
                    info = cand;
                    break;
                }
            }
            if (info == null) continue;
            long expires = parseIsoMillis(info.optString("expires", ""));
            if (expires > 0 && expires <= now) continue;

            JSONObject area = null;
            String where = "";
            JSONArray areas = info.optJSONArray("area");
            for (int i = 0; area == null && areas != null && i < areas.length(); i++) {
                JSONObject ar = areas.optJSONObject(i);
                if (ar == null) continue;
                JSONArray geo = ar.optJSONArray("geocode");
                for (int g = 0; geo != null && g < geo.length(); g++) {
                    JSONObject gc = geo.optJSONObject(g);
                    if (gc != null && codes.contains(gc.optString("valueName") + ":" + gc.optString("value"))) {
                        area = ar;
                        break;
                    }
                }
                JSONArray polys = ar.optJSONArray("polygon");
                for (int p = 0; area == null && polys != null && p < polys.length(); p++) {
                    if (inCapPolygon(polys.optString(p), lat, lon)) area = ar;
                }
                if (area != null) {
                    JSONObject first = geo != null ? geo.optJSONObject(0) : null;
                    where = first != null ? first.optString("valueName") + ":" + first.optString("value")
                            : ar.optString("areaDesc", "");
                }
            }
            if (area == null) continue;

            String event = info.optString("event", "Weather warning");
            if (!event.isEmpty()) event = Character.toUpperCase(event.charAt(0)) + event.substring(1);
            String severity = info.optString("severity", "Unknown");
            JSONObject a = new JSONObject();
            // Re-issued warnings get new identifiers; event + severity + area notifies again only when it escalates
            a.put("id", "ma:" + feed + "|" + event + "|" + severity + "|" + where);
            a.put("identifier", alert.optString("identifier", ""));
            a.put("event", event);
            a.put("severity", severity);
            a.put("headline", info.optString("headline", ""));
            a.put("areaDesc", area.optString("areaDesc", ""));
            a.put("description", info.optString("description", ""));
            a.put("instruction", info.optString("instruction", ""));
            a.put("ends", info.optString("expires", ""));
            a.put("sent", alert.optString("sent", info.optString("effective", "")));
            a.put("source", "MeteoAlarm");
            a.put("sender", info.optString("senderName", ""));
            a.put("web", info.optString("web", ""));
            out.put(a);
        }
        return out;
    }

    /** CAP polygon: "lat,lon lat,lon …"; ray casting on the point. */
    static boolean inCapPolygon(String poly, double lat, double lon) {
        if (poly == null) return false;
        String[] pairs = poly.trim().split("\\s+");
        if (pairs.length < 4) return false;
        double[] ys = new double[pairs.length];
        double[] xs = new double[pairs.length];
        try {
            for (int i = 0; i < pairs.length; i++) {
                String[] p = pairs[i].split(",");
                ys[i] = Double.parseDouble(p[0]);
                xs[i] = Double.parseDouble(p[1]);
            }
        } catch (Exception e) {
            return false;
        }
        boolean inside = false;
        for (int i = 0, j = pairs.length - 1; i < pairs.length; j = i++) {
            if ((ys[i] > lat) != (ys[j] > lat)
                    && lon < (xs[j] - xs[i]) * (lat - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** ISO 8601 with "Z" or a "+01:00" offset → epoch ms, or -1. (The X pattern needs API 24; minSdk is 23.) */
    static long parseIsoMillis(String iso) {
        if (iso == null) return -1;
        String s = iso.trim().replaceFirst("Z$", "+0000").replaceFirst("([+-]\\d\\d):(\\d\\d)$", "$1$2");
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            java.util.Date d = f.parse(s);
            return d != null ? d.getTime() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isSevereEvent(String event) {
        if (event == null) return false;
        String e = event.toLowerCase(Locale.US);
        return e.contains("tornado")
                || e.contains("severe thunderstorm")
                || e.contains("flash flood")
                || e.contains("flood warning")
                || e.contains("flood emergency")
                || e.contains("hurricane")
                || e.contains("tropical storm")
                || e.contains("tropical depression")
                || e.contains("storm surge")
                || e.contains("extreme wind");
    }

    /**
     * Notify on the original storm events, anything NWS rates Extreme or Severe
     * (winter storm, flood watch, high wind, …), and Moderate (advisories) when
     * the user opted in. Minor alerts (frost, small craft, …) are never notified.
     */
    static boolean shouldNotify(String event, String severity, boolean includeAdvisories) {
        if (isSevereEvent(event)) return true;
        if ("Extreme".equals(severity) || "Severe".equals(severity)) return true;
        return includeAdvisories && "Moderate".equals(severity);
    }

    // ─── NHC tropical near location ───────────────────────────────────────

    private static boolean checkNhCNearby(
            Context context,
            double lat,
            double lon,
            String placeLabel,
            JSONObject prev,
            JSONObject next,
            boolean quiet
    ) throws Exception {
        String body = httpGet(NHC_URL, "application/json");
        if (body == null || body.isEmpty()) return false;

        JSONObject root = new JSONObject(body);
        JSONArray storms = root.optJSONArray("activeStorms");
        if (storms == null) return true;

        for (int i = 0; i < storms.length(); i++) {
            JSONObject s = storms.optJSONObject(i);
            if (s == null) continue;

            double slat = s.optDouble("latitudeNumeric", Double.NaN);
            double slon = s.optDouble("longitudeNumeric", Double.NaN);
            if (Double.isNaN(slat) || Double.isNaN(slon)) continue;

            double distMi = haversineMiles(lat, lon, slat, slon);
            if (distMi > TROPICAL_RADIUS_MI) continue;

            String id = s.optString("id", s.optString("name", ""));
            if (id.isEmpty()) continue;

            String name = s.optString("name", id);
            String classification = s.optString("classification", "");
            String intensity = s.optString("intensity", "");
            String adv = "";
            JSONObject ft = s.optJSONObject("forecastTrack");
            if (ft != null) adv = ft.optString("advNum", "");
            if (adv.isEmpty()) {
                JSONObject pa = s.optJSONObject("publicAdvisory");
                if (pa != null) adv = pa.optString("advNum", "");
            }

            String key = "nhc:" + id;
            JSONObject fp = new JSONObject();
            fp.put("name", name);
            fp.put("classification", classification);
            fp.put("intensity", intensity);
            fp.put("adv", adv);
            fp.put("distMi", Math.round(distMi));
            next.put(key, fp);

            if (quiet) continue;

            JSONObject old = prev.optJSONObject(key);
            String near = Math.round(distMi) + " mi from " + (placeLabel != null && !placeLabel.isEmpty() ? placeLabel : "you");
            if (old == null) {
                JSONObject detail = new JSONObject();
                try {
                    detail.put("kind", "nhc");
                    detail.put("key", key);
                    detail.put("event", "Tropical cyclone");
                    detail.put("headline", classification + " " + name);
                    detail.put("description", near + (intensity.isEmpty() ? "" : " · " + intensity + " kt"));
                    detail.put("lat", slat);
                    detail.put("lon", slon);
                    detail.put("place", placeLabel != null ? placeLabel : "");
                    detail.put("layer", "hurricane");
                    detail.put("name", name);
                    detail.put("classification", classification);
                    detail.put("intensity", intensity);
                    detail.put("distMi", Math.round(distMi));
                } catch (Exception ignored) {
                }
                post(
                        context,
                        BASE_NOTIF_ID + Math.abs(key.hashCode() % 500),
                        "Tropical cyclone near you",
                        classification + " " + name
                                + (intensity.isEmpty() ? "" : " · " + intensity + " kt")
                                + " · " + near,
                        detail
                );
            } else {
                String oldAdv = old.optString("adv", "");
                if (!adv.isEmpty() && !adv.equals(oldAdv)) {
                    post(
                            context,
                            BASE_NOTIF_ID + Math.abs((key + adv).hashCode() % 500),
                            name + " — new advisory",
                            "Advisory #" + adv
                                    + (intensity.isEmpty() ? "" : " · " + intensity + " kt")
                                    + " · " + near
                    );
                } else {
                    try {
                        int oldKt = Integer.parseInt(old.optString("intensity", "0"));
                        int newKt = Integer.parseInt(intensity.isEmpty() ? "0" : intensity);
                        if (newKt > oldKt + 5) {
                            post(
                                    context,
                                    BASE_NOTIF_ID + Math.abs((key + intensity).hashCode() % 500),
                                    name + " strengthened",
                                    oldKt + " → " + newKt + " kt · " + near
                            );
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return true;
    }

    // ─── Location ─────────────────────────────────────────────────────────

    /** @return [lat, lon] or null */
    static double[] resolveLocation(SharedPreferences prefs) {
        double[] fromHome = parseLatLon(prefs.getString(KEY_LOC, null));
        if (fromHome != null) return fromHome;
        return parseLatLon(prefs.getString(KEY_DEFAULT, null));
    }

    static String resolveLocationLabel(SharedPreferences prefs) {
        String label = parseLabel(prefs.getString(KEY_LOC, null));
        if (label != null && !label.isEmpty()) return label;
        label = parseLabel(prefs.getString(KEY_DEFAULT, null));
        return label != null ? label : "your location";
    }

    private static double[] parseLatLon(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            if (!o.has("lat") || !o.has("lon")) return null;
            return new double[]{o.getDouble("lat"), o.getDouble("lon")};
        } catch (Exception e) {
            return null;
        }
    }

    private static String parseLabel(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new JSONObject(raw).optString("label", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject loadSeen(SharedPreferences prefs) {
        // Prefer new key; fall back to old hurricane-only seen map once
        String seenRaw = prefs.getString(KEY_SEEN, null);
        if (seenRaw == null || seenRaw.isEmpty()) {
            seenRaw = prefs.getString("geauxweather_hurricane_seen_native", "{}");
        }
        try {
            return new JSONObject(seenRaw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static double haversineMiles(double lat1, double lon1, double lat2, double lon2) {
        double R = 3958.8; // Earth radius miles
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private static String trimLen(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max - 1) + "…";
    }

    static String mapLayerForEvent(String event) {
        String e = event != null ? event.toLowerCase(Locale.US) : "";
        if (e.contains("tornado")) return "tornado";
        if (e.contains("hurricane") || e.contains("tropical") || e.contains("storm surge")) return "hurricane";
        if (e.contains("thunder") || e.contains("flood")) return "lightning";
        if (e.contains("wind")) return "wind";
        if (e.contains("fog")) return "fog";
        return "radar";
    }

    private static void post(Context context, int id, String title, String body) {
        post(context, id, title, body, null);
    }

    private static void post(Context context, int id, String title, String body, JSONObject detail) {
        Intent launch = new Intent(context, MainActivity.class);
        launch.setAction(ACTION_OPEN_ALERT);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (detail != null) {
            String json = detail.toString();
            launch.putExtra(EXTRA_ALERT_JSON, json);
            // Persist so WebView can read even if activity was already alive / cold-start race
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PENDING_ALERT, json)
                    .apply();
        }
        PendingIntent pi = PendingIntent.getActivity(
                context,
                id,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String preview = body != null ? body : "";
        // One-line notification preview (first line only)
        int nl = preview.indexOf('\n');
        String shortText = nl > 0 ? preview.substring(0, nl) : preview;

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_rain)
                .setContentTitle(title != null ? title : "Severe weather")
                .setContentText(shortText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(preview))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);

        try {
            NotificationManagerCompat.from(context).notify(id, b.build());
            android.util.Log.i(WidgetRefreshWorker.TAG, "alerts: notified \"" + title + "\"");
        } catch (SecurityException e) {
            android.util.Log.w(WidgetRefreshWorker.TAG, "alerts: notification blocked: " + e);
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Severe storm alerts",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        ch.setDescription("NWS warnings and watches (tornado, thunderstorm, flood, winter storm, high wind, …) and nearby tropical cyclones for your location");
        ch.setShowBadge(true);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300 && body != null;
        }
    }

    /** Body for a 2xx answer, else null. */
    static String httpGet(String urlStr, String accept) throws Exception {
        HttpResult r = httpGetResult(urlStr, accept);
        return r.ok() ? r.body : null;
    }

    /** Status code plus body (2xx only); callers that need to tell "not covered" from a failure use this. */
    static HttpResult httpGetResult(String urlStr, String accept) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty(
                "User-Agent",
                "GeauxWeather/1.0 (Android; FOSS; +https://github.com/tunefriend/GeauxWeather)"
        );
        conn.setRequestProperty("Accept", accept != null ? accept : "*/*");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return new HttpResult(code, null);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new HttpResult(code, sb.toString());
        } finally {
            conn.disconnect();
        }
    }
}
