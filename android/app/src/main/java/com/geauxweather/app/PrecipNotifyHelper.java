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

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Once-a-day notification when tomorrow's precipitation chance (rain or snow,
 * Open-Meteo precipitation_probability_max) reaches the user's threshold.
 * Settings live in CapacitorStorage so the web Settings screen can write them.
 */
public final class PrecipNotifyHelper {

    static final String WORK_NAME = "geauxweather_precip_notify";
    static final String EXTRA_OPEN_SCREEN = "geauxweather_open_screen";
    private static final String CHANNEL_ID = "geauxweather_precip";
    private static final int NOTIF_ID = 8100;
    private static final String PREFS = Notify.PREFS;
    private static final String KEY_ENABLED = "geauxweather_precip_notify";      // "on" / "off"
    private static final String KEY_THRESHOLD = "geauxweather_precip_threshold"; // "50" / "70" / "90"
    private static final String KEY_HOUR = "geauxweather_precip_hour";           // "15" / "18" / "21"
    private static final String KEY_LAST = "geauxweather_precip_last";           // date already notified for

    private PrecipNotifyHelper() {}

    public static boolean isEnabled(Context context) {
        return "on".equals(prefs(context).getString(KEY_ENABLED, "off"));
    }

    /** Called from Settings whenever the switch, threshold or time changes. */
    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putString(KEY_ENABLED, enabled ? "on" : "off").apply();
        if (enabled) {
            scheduleNext(context, ExistingWorkPolicy.REPLACE);
            new Thread(() -> check(context)).start();
        } else {
            cancelSchedule(context);
        }
    }

    /** App start / boot: make sure a run is queued without moving an existing one. */
    public static void ensureScheduled(Context context) {
        if (isEnabled(context)) scheduleNext(context, ExistingWorkPolicy.KEEP);
    }

    /**
     * Battery: one wake and one ~1 KB request per day, at the chosen hour. A one-time
     * request re-queued after each run (instead of periodic work) keeps it on that hour
     * without drift and without hourly wake-ups.
     */
    static void scheduleNext(Context context, ExistingWorkPolicy policy) {
        int hour = parseInt(prefs(context).getString(KEY_HOUR, "18"), 18);
        long delayMs = millisUntilNext(System.currentTimeMillis(), hour);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DailyNotifyWorker.class)
                .setInputData(DailyNotifyWorker.input(DailyNotifyWorker.PRECIP))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, req);
    }

    /** Milliseconds from now until the next HH:00 local time (tomorrow if already past). */
    static long millisUntilNext(long nowMs, int hour) {
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(nowMs);
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (next.getTimeInMillis() <= nowMs) next.add(Calendar.DAY_OF_MONTH, 1);
        return next.getTimeInMillis() - nowMs;
    }

    public static void cancelSchedule(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }

    /** @return false only when the forecast could not be fetched (worth a retry). */
    public static boolean check(Context context) {
        if (!isEnabled(context)) return true;
        SharedPreferences prefs = prefs(context);
        int notifyHour = parseInt(prefs.getString(KEY_HOUR, "18"), 18);
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < notifyHour) return true;

        double[] loc = HurricaneAlertHelper.resolveLocation(prefs);
        if (loc == null) return true;
        try {
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&daily=precipitation_probability_max,snowfall_sum,rain_sum,showers_sum"
                            + "&forecast_days=2&timezone=auto%s",
                    loc[0], loc[1], model(prefs));
            String body = HurricaneAlertHelper.httpGet(url, "application/json");
            if (body == null) {
                Log.w(WidgetRefreshWorker.TAG, "tomorrow precip: download failed");
                return false;
            }
            JSONObject daily = new JSONObject(body).getJSONObject("daily");
            String tomorrow = daily.getJSONArray("time").getString(1);
            if (tomorrow.equals(prefs.getString(KEY_LAST, null))) return true;

            int prob = daily.getJSONArray("precipitation_probability_max").optInt(1, 0);
            double snowCm = optDouble(daily.optJSONArray("snowfall_sum"));
            double rainMm = optDouble(daily.optJSONArray("rain_sum")) + optDouble(daily.optJSONArray("showers_sum"));
            int threshold = parseInt(prefs.getString(KEY_THRESHOLD, "70"), 70);

            String title = title(prob, snowCm, rainMm, threshold);
            Log.i(WidgetRefreshWorker.TAG, "tomorrow precip: " + prob + "% (threshold " + threshold + "%)"
                    + (title == null ? ", no notification" : ", notifying"));
            if (title == null) return true;
            String text = prob + "% chance · " + HurricaneAlertHelper.resolveLocationLabel(prefs);
            if (Notify.post(context, CHANNEL_ID, "Precipitation forecast",
                    "Rain or snow likely tomorrow, once a day at the time you choose", NOTIF_ID, R.drawable.ic_stat_rain,
                    title, text, new Intent(context, MainActivity.class).putExtra(EXTRA_OPEN_SCREEN, "10day"))) {
                prefs.edit().putString(KEY_LAST, tomorrow).apply();
            }
            return true;
        } catch (Exception e) {
            Log.w(WidgetRefreshWorker.TAG, "tomorrow precip: failed: " + e);
            return false;
        }
    }

    /**
     * Notification title, or null when tomorrow's chance is below the threshold.
     * Snowfall and rain totals only pick the wording; the chance alone decides whether to notify.
     */
    static String title(int probability, double snowCm, double rainMm, int threshold) {
        if (probability < threshold) return null;
        boolean snow = snowCm > 0;
        boolean rain = rainMm > 0;
        if (snow && rain) return "Rain and snow likely tomorrow";
        if (snow) return "Snow likely tomorrow";
        if (rain) return "Rain likely tomorrow";
        return "Precipitation likely tomorrow";
    }

    /** Settings → Forecast model; one model, so the response keeps plain column names. */
    private static String model(SharedPreferences prefs) {
        String m = WidgetRefreshWorker.forecastModel(prefs.getString("geauxweather_model", null));
        return m == null ? "" : "&models=" + m;
    }

    private static double optDouble(JSONArray arr) {
        if (arr == null || arr.isNull(1)) return 0;
        return arr.optDouble(1, 0);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
