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

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The one frequent background job, so the phone wakes once per run instead of once per feature:
 * - widgets + status-bar notification (their snapshot is otherwise written only by the open app)
 *   and the next-hour rain/snow check, from one Open-Meteo download;
 * - severe weather alerts (NWS + NHC), about hourly, or every run while a storm is active.
 * Tomorrow's-precipitation stays a separate once-a-day job.
 */
public class WidgetRefreshWorker extends Worker {
    /** Background runs log one line each under this tag: adb logcat -s GeauxWeather */
    static final String TAG = "GeauxWeather";
    static final String WORK_NAME = "geauxweather_widget_refresh";
    private static final String PREFS = "CapacitorStorage";
    private static final String KEY_WIDGET = "geauxweather_widget"; // same snapshot the web app writes
    /** Skip the download if the app wrote the snapshot this recently. */
    static final long FRESH_MS = 15 * 60 * 1000L;
    /** Alert checks this close together are skipped (hourly while quiet; runs are 30 min apart). */
    static final long ALERT_GAP_MS = 50 * 60 * 1000L;
    private static final String KEY_INTERVAL = "geauxweather_bg_interval"; // minutes last scheduled

    public WidgetRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        ensureScheduled(ctx); // re-times after a widget was removed etc.; cancels if nothing needs it
        if (!needed(ctx)) return Result.success();
        if (refreshNeeded(ctx)) refresh(ctx);
        boolean alerts = HurricaneAlertHelper.isEnabled(ctx);
        if (alerts && alertDue(System.currentTimeMillis(), HurricaneAlertHelper.lastCheckMs(ctx), HurricaneAlertHelper.isFast(ctx))) {
            HurricaneAlertHelper.check(ctx, false); // may switch fast mode, which reschedules this job
        } else if (alerts) {
            Log.i(TAG, "alerts: skipped, checked under 50 min ago");
        }
        return Result.success(); // a failed fetch waits for the next run
    }

    /** Every run while a storm is active; otherwise when the last check (here or on app open) is 50+ min old. */
    static boolean alertDue(long nowMs, long lastCheckMs, boolean fast) {
        return fast || nowMs - lastCheckMs >= ALERT_GAP_MS;
    }

    /** 15 min while alerts are in storm mode, 30 for widgets / next-hour, else hourly for alerts alone. */
    static int intervalMinutes(boolean alerts, boolean fast, boolean refresh) {
        if (alerts && fast) return 15;
        return refresh ? 30 : 60;
    }

    /**
     * Starts, re-times or stops the job to match what is switched on. Safe to call repeatedly: an unchanged
     * interval keeps the existing schedule (KEEP); a changed one updates it in place (UPDATE).
     */
    static void ensureScheduled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        WorkManager wm = WorkManager.getInstance(context);
        if (!needed(context)) {
            wm.cancelUniqueWork(WORK_NAME);
            prefs.edit().remove(KEY_INTERVAL).apply();
            return;
        }
        int minutes = intervalMinutes(HurricaneAlertHelper.isEnabled(context), HurricaneAlertHelper.isFast(context),
                refreshNeeded(context));
        boolean changed = !String.valueOf(minutes).equals(prefs.getString(KEY_INTERVAL, null));
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                WidgetRefreshWorker.class, minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        wm.enqueueUniquePeriodicWork(WORK_NAME,
                changed ? ExistingPeriodicWorkPolicy.UPDATE : ExistingPeriodicWorkPolicy.KEEP, req);
        if (changed) {
            prefs.edit().putString(KEY_INTERVAL, String.valueOf(minutes)).apply();
            Log.i(TAG, "background job every " + minutes + " min");
        }
    }

    static boolean needed(Context context) {
        return refreshNeeded(context) || HurricaneAlertHelper.isEnabled(context);
    }

    /** Widgets or the next-hour check need the Open-Meteo download. */
    static boolean refreshNeeded(Context context) {
        return hasWidgets(context) || NextHourNotifyHelper.isEnabled(context);
    }

    static boolean hasWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        return count(mgr, context, GeauxWeatherWidgetProvider.class)
                + count(mgr, context, GeauxClockWidgetProvider.class) > 0;
    }

    private static int count(AppWidgetManager mgr, Context context, Class<?> cls) {
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, cls));
        return ids == null ? 0 : ids.length;
    }

    /** @return false when the forecast could not be fetched or parsed. */
    static boolean refresh(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        double[] loc = HurricaneAlertHelper.resolveLocation(prefs);
        if (loc == null) {
            Log.i(TAG, "widget refresh: no saved location yet");
            return true; // the app has never loaded weather
        }

        JSONObject prev = null;
        try {
            String raw = prefs.getString(KEY_WIDGET, null);
            if (raw != null) prev = new JSONObject(raw);
        } catch (Exception ignored) {
        }
        // The next-hour check needs this run's 15-minute forecast, so it never skips.
        if (!NextHourNotifyHelper.isEnabled(context) && prev != null
                && System.currentTimeMillis() - prev.optLong("updatedAt", 0) < FRESH_MS) {
            Log.i(TAG, "widget refresh: skipped, the app saved weather under 15 min ago");
            return true;
        }
        String units = prev != null ? prev.optString("units", "imperial") : "imperial";

        JSONObject json;
        try {
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day,precipitation"
                            + "&daily=temperature_2m_max,temperature_2m_min"
                            + "&minutely_15=precipitation,snowfall&forecast_minutely_15=6"
                            + "&forecast_days=1&timezone=auto&timeformat=unixtime%s",
                    loc[0], loc[1], "metric".equals(units) ? "" : "&temperature_unit=fahrenheit");
            String body = HurricaneAlertHelper.httpGet(url, "application/json");
            if (body == null) {
                Log.w(TAG, "widget refresh: download failed");
                return false;
            }
            json = new JSONObject(body);
            JSONObject c = json.getJSONObject("current");
            JSONObject d = json.optJSONObject("daily");
            int code = c.optInt("weather_code", -1);
            int isDay = c.optInt("is_day", 1) == 0 ? 0 : 1;

            JSONObject snap = new JSONObject();
            snap.put("label", HurricaneAlertHelper.resolveLocationLabel(prefs));
            snap.put("temp", Math.round(c.getDouble("temperature_2m")) + "°");
            snap.put("condition", condition(code, isDay == 1));
            snap.put("weatherCode", code);
            snap.put("isDay", isDay);
            snap.put("high", dailyTemp(d, "temperature_2m_max"));
            snap.put("low", dailyTemp(d, "temperature_2m_min"));
            snap.put("units", units);
            snap.put("updatedAt", System.currentTimeMillis());
            prefs.edit().putString(KEY_WIDGET, snap.toString()).apply();
            Log.i(TAG, "widget refresh: " + snap.optString("temp") + " " + snap.optString("condition")
                    + (isDay == 1 ? " (day)" : " (night)"));
        } catch (Exception e) {
            Log.w(TAG, "widget refresh: failed: " + e);
            return false;
        }
        GeauxWeatherWidgetProvider.refreshAll(context); // also redraws the clock widget
        WeatherNotificationHelper.update(context);
        try {
            // After the redraw, so a notification failure can't leave the widgets stale
            NextHourNotifyHelper.check(context, json, HurricaneAlertHelper.resolveLocationLabel(prefs));
        } catch (Exception e) {
            Log.w(TAG, "next-hour check failed: " + e);
        }
        return true;
    }

    private static String dailyTemp(JSONObject daily, String key) {
        if (daily == null || daily.optJSONArray(key) == null) return "—°";
        double v = daily.optJSONArray(key).optDouble(0, Double.NaN);
        return Double.isNaN(v) ? "—°" : Math.round(v) + "°";
    }

    /** Icon + text, matching codeToCondition() in www/js/weather.js. */
    static String condition(int code, boolean isDay) {
        String icon;
        String text;
        switch (code) {
            case 0: icon = "☀️"; text = "Clear"; break;
            case 1: icon = "🌤"; text = "Mainly clear"; break;
            case 2: icon = "⛅"; text = "Partly cloudy"; break;
            case 3: icon = "☁️"; text = "Overcast"; break;
            case 45: icon = "🌫"; text = "Fog"; break;
            case 48: icon = "🌫"; text = "Rime fog"; break;
            case 51: icon = "🌦"; text = "Light drizzle"; break;
            case 53: icon = "🌦"; text = "Drizzle"; break;
            case 55: icon = "🌧"; text = "Heavy drizzle"; break;
            case 61: icon = "🌧"; text = "Light rain"; break;
            case 63: icon = "🌧"; text = "Rain"; break;
            case 65: icon = "🌧"; text = "Heavy rain"; break;
            case 71: icon = "🌨"; text = "Light snow"; break;
            case 73: icon = "❄️"; text = "Snow"; break;
            case 75: icon = "❄️"; text = "Heavy snow"; break;
            case 80: icon = "🌦"; text = "Rain showers"; break;
            case 81: icon = "🌧"; text = "Showers"; break;
            case 82: icon = "🌧"; text = "Heavy showers"; break;
            case 95: icon = "⛈"; text = "Thunderstorm"; break;
            case 96: icon = "⛈"; text = "T-storm + hail"; break;
            case 99: icon = "⛈"; text = "T-storm + heavy hail"; break;
            default: icon = "☁️"; text = "—"; break;
        }
        if (!isDay) {
            if (code == 0 || code == 1) icon = "🌙";
            else if (code == 2) icon = "☁️";
            else if (code == 51 || code == 53 || code == 80) icon = "🌧";
        }
        return icon + " " + text;
    }
}
