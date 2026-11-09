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
import androidx.work.Operation;
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
    private static final String PREFS = Notify.PREFS;
    private static final String KEY_WIDGET = "geauxweather_widget"; // same snapshot the web app writes
    /** Skip the download if the app wrote the snapshot this recently. */
    static final long FRESH_MS = 15 * 60 * 1000L;
    /** Alert checks this close together are skipped (hourly while quiet; runs are 30 min apart). */
    static final long ALERT_GAP_MS = 50 * 60 * 1000L;
    private static final String KEY_INTERVAL = "geauxweather_bg_interval"; // minutes last scheduled
    /** Epoch ms of the last background run, shown in Settings so the user can see the job is alive. */
    private static final String KEY_LAST_RUN = "geauxweather_bg_last_run";
    private static final String KEY_MODEL = "geauxweather_model"; // Settings → Forecast model

    public WidgetRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        ensureScheduled(ctx); // re-times after a widget was removed etc.; cancels if nothing needs it
        if (!needed(ctx)) return Result.success();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_RUN, String.valueOf(System.currentTimeMillis())).apply();
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
    static Operation ensureScheduled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        WorkManager wm = WorkManager.getInstance(context);
        if (!needed(context)) {
            prefs.edit().remove(KEY_INTERVAL).apply();
            return wm.cancelUniqueWork(WORK_NAME);
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
        Operation op = wm.enqueueUniquePeriodicWork(WORK_NAME,
                changed ? ExistingPeriodicWorkPolicy.UPDATE : ExistingPeriodicWorkPolicy.KEEP, req);
        if (changed) {
            prefs.edit().putString(KEY_INTERVAL, String.valueOf(minutes)).apply();
            Log.i(TAG, "background job every " + minutes + " min");
        }
        return op;
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

        String model = forecastModel(prefs.getString(KEY_MODEL, null));
        JSONObject json;
        try {
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day,precipitation"
                            + "&daily=temperature_2m_max,temperature_2m_min"
                            + "&minutely_15=precipitation,snowfall&forecast_minutely_15=6"
                            + "&forecast_days=1&timezone=auto&timeformat=unixtime%s%s",
                    loc[0], loc[1], "metric".equals(units) ? "" : "&temperature_unit=fahrenheit",
                    model == null ? "" : "&models=best_match," + model);
            String body = HurricaneAlertHelper.httpGet(url, "application/json");
            if (body == null) {
                Log.w(TAG, "widget refresh: download failed");
                return false;
            }
            json = new JSONObject(body);
            if (model != null) {
                // Same split as the app: today's high/low from the chosen model, "now" and next hour from Best match
                pickModel(json, "daily", model, "best_match");
                pickModel(json, "minutely_15", "best_match", null);
            }
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

    /** Settings → Forecast model choices besides Best match; must match MODELS in www/js/weather.js (tested). */
    static final String[] MODELS = {"ecmwf_ifs025", "gfs_seamless", "gem_seamless", "icon_seamless", "ukmo_seamless",
            "meteofrance_seamless", "jma_seamless"};

    /** @return the chosen Open-Meteo model, or null for Best match (and for anything unexpected). */
    static String forecastModel(String pref) {
        for (String m : MODELS) if (m.equals(pref)) return m;
        return null;
    }

    /**
     * Multi-model responses suffix each column with its model; keep one model's under the plain names.
     * Values that model lacks (null) come from {@code fallback}'s column when given, like pickModel in
     * www/js/weather.js (e.g. JMA and Météo-France have no rain chance, none of the extras has UV).
     */
    static void pickModel(JSONObject json, String key, String model, String fallback) throws org.json.JSONException {
        JSONObject block = json.optJSONObject(key);
        if (block == null) return;
        String suffix = "_" + model;
        JSONObject out = new JSONObject();
        out.put("time", block.opt("time"));
        java.util.Iterator<String> it = block.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!k.endsWith(suffix)) continue;
            String name = k.substring(0, k.length() - suffix.length());
            Object v = block.get(k);
            org.json.JSONArray alt = fallback == null ? null : block.optJSONArray(name + "_" + fallback);
            if (v instanceof org.json.JSONArray && alt != null) {
                org.json.JSONArray own = (org.json.JSONArray) v;
                org.json.JSONArray merged = new org.json.JSONArray();
                for (int i = 0; i < own.length(); i++) merged.put(own.isNull(i) ? alt.opt(i) : own.opt(i));
                v = merged;
            }
            out.put(name, v);
        }
        json.put(key, out);
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
