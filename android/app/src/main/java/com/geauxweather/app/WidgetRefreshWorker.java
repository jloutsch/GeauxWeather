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
 * Keeps the widgets and status-bar notification current while the app is closed, and runs the
 * next-hour rain/snow check from the same download. The snapshot the widgets draw from is otherwise
 * written only by the web app when it is open.
 */
public class WidgetRefreshWorker extends Worker {
    static final String WORK_NAME = "geauxweather_widget_refresh";
    private static final String PREFS = "CapacitorStorage";
    private static final String KEY_WIDGET = "geauxweather_widget"; // same snapshot the web app writes
    /** Skip the download if the app wrote the snapshot this recently. */
    static final long FRESH_MS = 15 * 60 * 1000L;

    public WidgetRefreshWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!needed(ctx)) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME); // last widget removed, next-hour check off
            return Result.success();
        }
        refresh(ctx);
        return Result.success(); // a failed fetch waits for the next 30-minute run
    }

    /** Schedules the refresh if a widget is on the home screen or the next-hour check is on. Safe to call repeatedly. */
    static void ensureScheduled(Context context) {
        if (!needed(context)) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                WidgetRefreshWorker.class, 30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    static boolean needed(Context context) {
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
        if (loc == null) return true; // no location yet: the app has never loaded weather

        JSONObject prev = null;
        try {
            String raw = prefs.getString(KEY_WIDGET, null);
            if (raw != null) prev = new JSONObject(raw);
        } catch (Exception ignored) {
        }
        // The next-hour check needs this run's 15-minute forecast, so it never skips.
        if (!NextHourNotifyHelper.isEnabled(context) && prev != null
                && System.currentTimeMillis() - prev.optLong("updatedAt", 0) < FRESH_MS) return true;
        String units = prev != null ? prev.optString("units", "imperial") : "imperial";

        try {
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&current=temperature_2m,weather_code,is_day,precipitation"
                            + "&daily=temperature_2m_max,temperature_2m_min"
                            + "&minutely_15=precipitation,snowfall&forecast_minutely_15=6"
                            + "&forecast_days=1&timezone=auto&timeformat=unixtime%s",
                    loc[0], loc[1], "metric".equals(units) ? "" : "&temperature_unit=fahrenheit");
            String body = HurricaneAlertHelper.httpGet(url, "application/json");
            if (body == null) return false;
            JSONObject json = new JSONObject(body);
            JSONObject c = json.getJSONObject("current");
            JSONObject d = json.optJSONObject("daily");
            int code = c.optInt("weather_code", -1);
            int isDay = c.optInt("is_day", 1) == 0 ? 0 : 1;

            JSONObject snap = new JSONObject();
            String label = HurricaneAlertHelper.resolveLocationLabel(prefs);
            snap.put("label", label);
            snap.put("temp", Math.round(c.getDouble("temperature_2m")) + "°");
            snap.put("condition", condition(code, isDay == 1));
            snap.put("weatherCode", code);
            snap.put("isDay", isDay);
            snap.put("high", dailyTemp(d, "temperature_2m_max"));
            snap.put("low", dailyTemp(d, "temperature_2m_min"));
            snap.put("units", units);
            snap.put("updatedAt", System.currentTimeMillis());
            prefs.edit().putString(KEY_WIDGET, snap.toString()).apply();
            NextHourNotifyHelper.check(context, json, label);
        } catch (Exception e) {
            return false;
        }
        GeauxWeatherWidgetProvider.refreshAll(context); // also redraws the clock widget
        WeatherNotificationHelper.update(context);
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
