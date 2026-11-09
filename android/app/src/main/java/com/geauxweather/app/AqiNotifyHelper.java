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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.Operation;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * "Air quality today: Unhealthy (AQI 162)": once a day at the chosen morning hour, when today's remaining
 * hourly AQI forecast reaches the chosen level. Same shape as UvNotifyHelper; scheduling, settings reads
 * and posting are shared (DailyNotifyWorker, Notify). Scale follows the app: US AQI for °F, EU AQI for °C.
 */
public final class AqiNotifyHelper {

    static final String WORK_NAME = "geauxweather_aqi_notify";
    private static final String CHANNEL_ID = "geauxweather_aqi";
    private static final int NOTIF_ID = 8400;
    private static final String KEY_ENABLED = "geauxweather_aqi_notify"; // "on" / "off"
    private static final String KEY_LEVEL = "geauxweather_aqi_level";    // "1" / "2" / "3" (see MIN_BAND)
    private static final String KEY_HOUR = "geauxweather_aqi_hour";      // "7" / "8" / "9"
    private static final String KEY_LAST = "geauxweather_aqi_last";      // date already notified for
    /** Evening and later runs stay quiet; smoke and ozone can peak in the afternoon, so later than UV. */
    static final int LAST_HOUR = 18;

    /** Band names and upper limits, as usAqiLabel / euAqiLabel in www/js/weather.js (tested). */
    static final String[] US_TEXT = {"Good", "Moderate", "Unhealthy (sensitive)", "Unhealthy", "Very unhealthy", "Hazardous"};
    static final int[] US_MAX = {50, 100, 150, 200, 300};
    static final String[] EU_TEXT = {"Good", "Fair", "Moderate", "Poor", "Very poor", "Extremely poor"};
    static final int[] EU_MAX = {20, 40, 60, 80, 100};
    /** Setting 1/2/3 → lowest band that notifies: US sensitive/unhealthy/very unhealthy, EU poor/very poor/extremely poor. */
    private static final int[] US_MIN_BAND = {2, 3, 4};
    private static final int[] EU_MIN_BAND = {3, 4, 5};

    private AqiNotifyHelper() {}

    public static boolean isEnabled(Context context) {
        return "on".equals(prefs(context).getString(KEY_ENABLED, "off"));
    }

    /** Called from Settings whenever the switch, level or time changes. */
    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putString(KEY_ENABLED, enabled ? "on" : "off").apply();
        if (enabled) {
            scheduleNext(context, ExistingWorkPolicy.REPLACE);
            new Thread(() -> check(context)).start();
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        }
    }

    public static void ensureScheduled(Context context) {
        if (isEnabled(context)) scheduleNext(context, ExistingWorkPolicy.KEEP);
    }

    static Operation scheduleNext(Context context, ExistingWorkPolicy policy) {
        return DailyNotifyWorker.enqueue(context, DailyNotifyWorker.AQI, WORK_NAME,
                DailyNotifyWorker.intPref(prefs(context), KEY_HOUR, 8), policy);
    }

    /** Band 0–5 for an AQI value on the US or EU scale. */
    static int band(double aqi, boolean us) {
        int[] max = us ? US_MAX : EU_MAX;
        int n = (int) Math.round(aqi);
        for (int i = 0; i < max.length; i++) if (n <= max[i]) return i;
        return max.length;
    }

    /** Notification title, or null when the day's peak stays below the chosen level (1–3). */
    static String title(double peak, boolean us, int level) {
        int lvl = Math.max(1, Math.min(3, level));
        int b = band(peak, us);
        if (b < (us ? US_MIN_BAND : EU_MIN_BAND)[lvl - 1]) return null;
        return "Air quality today: " + (us ? US_TEXT : EU_TEXT)[b] + " (" + (us ? "AQI " : "EU AQI ") + Math.round(peak) + ")";
    }

    /** @return false only when the forecast could not be fetched (worth a retry). */
    public static boolean check(Context context) {
        if (!isEnabled(context)) return true;
        SharedPreferences prefs = prefs(context);
        int hourNow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hourNow < DailyNotifyWorker.intPref(prefs, KEY_HOUR, 8) || hourNow >= LAST_HOUR) return true;

        double[] loc = HurricaneAlertHelper.resolveLocation(prefs);
        if (loc == null) return true;
        boolean us = Notify.imperial(prefs); // the Today AQI tile: US AQI for °F, EU AQI for °C
        String field = us ? "us_aqi" : "european_aqi";
        try {
            String body = HurricaneAlertHelper.httpGet(String.format(Locale.US,
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f&longitude=%.4f"
                            + "&hourly=%s&forecast_days=1&timezone=auto&timeformat=unixtime",
                    loc[0], loc[1], field), "application/json");
            if (body == null) {
                Log.w(WidgetRefreshWorker.TAG, "aqi: download failed");
                return false;
            }
            JSONObject json = new JSONObject(body);
            JSONObject hourly = json.getJSONObject("hourly");
            JSONArray times = hourly.getJSONArray("time");
            JSONArray values = hourly.getJSONArray(field);
            // Local date of the location (timezone=auto), so "today" is the place's day
            long offset = json.optLong("utc_offset_seconds", 0);
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            day.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String today = day.format(new Date((times.getLong(0) + offset) * 1000));
            if (today.equals(prefs.getString(KEY_LAST, null))) return true;

            // Peak over the hours still ahead (the current hour included)
            long nowSec = System.currentTimeMillis() / 1000;
            double peak = -1;
            long peakAt = 0;
            for (int i = 0; i < times.length(); i++) {
                if (times.getLong(i) + 3600 <= nowSec || values.isNull(i)) continue;
                double v = values.getDouble(i);
                if (v > peak) {
                    peak = v;
                    peakAt = times.getLong(i);
                }
            }
            int level = DailyNotifyWorker.intPref(prefs, KEY_LEVEL, 1);
            String title = peak < 0 ? null : title(peak, us, level);
            Log.i(WidgetRefreshWorker.TAG, "aqi: " + field + " peak " + peak + " (level " + level + ")"
                    + (title == null ? ", no notification" : ", notifying"));
            if (title == null) return true;
            String when = NextHourNotifyHelper.timeFormat(context, prefs.getString("geauxweather_clock", "auto"))
                    .format(new Date(peakAt * 1000));
            String text = HurricaneAlertHelper.resolveLocationLabel(prefs) + " · highest around " + when;
            if (Notify.post(context, CHANNEL_ID, "Poor air quality", "Air quality forecast to reach your chosen level today",
                    NOTIF_ID, R.drawable.ic_stat_cloudy, title, text, new Intent(context, MainActivity.class))) {
                prefs.edit().putString(KEY_LAST, today).apply();
            }
            return true;
        } catch (Exception e) {
            Log.w(WidgetRefreshWorker.TAG, "aqi: failed: " + e);
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Notify.PREFS, Context.MODE_PRIVATE);
    }
}
