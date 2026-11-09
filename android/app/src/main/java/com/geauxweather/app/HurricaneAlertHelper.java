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
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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
    private static final String PREFS = "CapacitorStorage";
    private static final String KEY_ENABLED = "geauxweather_hurricane_alerts";
    private static final String KEY_SEEN = "geauxweather_severe_seen_native";
    private static final String KEY_LOC = "geauxweather_location";
    private static final String KEY_DEFAULT = "geauxweather_default";
    /** "advisories" also notifies on NWS Moderate alerts; anything else = warnings only. Set from Settings. */
    private static final String KEY_LEVEL = "geauxweather_alert_level";
    /** "on" while a storm-type alert/watch or nearby tropical storm is active → check every 15 min. */
    private static final String KEY_FAST = "geauxweather_alert_fast";
    private static final String KEY_PENDING_ALERT = "geauxweather_pending_alert";
    public static final String EXTRA_ALERT_JSON = "geauxweather_alert_json";
    public static final String ACTION_OPEN_ALERT = "com.geauxweather.app.OPEN_ALERT";
    private static final String NHC_URL = "https://www.nhc.noaa.gov/CurrentStorms.json";
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

    public static void schedule(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        boolean fast = "on".equals(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAST, "off"));
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                HurricaneAlertWorker.class,
                // 15 min (WorkManager's minimum) while storms are active: warnings often last 30–45 min.
                // Otherwise 60 min to save battery; a watch for the location switches back to 15 min.
                // shortcut: a warning issued with no prior watch can wait up to ~60 min; upgrade to push alerts if that matters.
                fast ? 15 : 60,
                TimeUnit.MINUTES
        )
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
        );
        // Cancel legacy work name if present
        WorkManager.getInstance(context).cancelUniqueWork("geauxweather_hurricane_alerts");
    }

    public static void cancelSchedule(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        WorkManager.getInstance(context).cancelUniqueWork("geauxweather_hurricane_alerts");
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
        boolean firstRun = prev.length() == 0;
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

        prefs.edit().putString(KEY_SEEN, next.toString()).apply();

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
        String url = String.format(
                Locale.US,
                "https://api.weather.gov/alerts/active?status=actual&point=%.4f,%.4f",
                lat,
                lon
        );
        String body = httpGet(url, "application/geo+json,application/json");
        if (body == null || body.isEmpty()) return false;

        JSONObject root = new JSONObject(body);
        JSONArray features = root.optJSONArray("features");
        if (features == null) return true;
        boolean includeAdvisories = "advisories".equals(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LEVEL, "warnings"));

        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.optJSONObject(i);
            if (f == null) continue;
            JSONObject props = f.optJSONObject("properties");
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
                detail.put("lat", lat);
                detail.put("lon", lon);
                detail.put("place", placeLabel != null ? placeLabel : "");
                detail.put("layer", mapLayerForEvent(event));
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
        } catch (SecurityException ignored) {
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

    static String httpGet(String urlStr, String accept) throws Exception {
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
            if (code < 200 || code >= 300) return null;
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
            );
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
