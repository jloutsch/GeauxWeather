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
 * "Today: 57°/44° · Partly cloudy" / "Rain up to 70% after 3 PM · Stoneham": every morning at the chosen
 * hour (shown until noon). Same daily scheduling as UvNotifyHelper; follows the app's units, clock and forecast model.
 */
public final class SummaryNotifyHelper {

    static final String WORK_NAME = "geauxweather_summary_notify";
    private static final String CHANNEL_ID = "geauxweather_summary";
    private static final int NOTIF_ID = 8500;
    private static final String KEY_ENABLED = "geauxweather_summary_notify"; // "on" / "off"
    private static final String KEY_HOUR = "geauxweather_summary_hour";      // "6" … "9"
    private static final String KEY_LAST = "geauxweather_summary_last";      // date already notified for
    static final int DEFAULT_HOUR = 7;
    /** No summary from noon on: a run held back by Doze until the phone is first used still counts before then. */
    static final int LAST_HOUR = 12;
    /** Rain chance worth mentioning, and the start of "after 3 PM". */
    static final int MENTION = 30;

    private SummaryNotifyHelper() {}

    public static boolean isEnabled(Context context) {
        return "on".equals(prefs(context).getString(KEY_ENABLED, "off"));
    }

    /** Called from Settings whenever the switch or time changes. */
    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putString(KEY_ENABLED, enabled ? "on" : "off").apply();
        if (enabled) {
            scheduleNext(context, ExistingWorkPolicy.REPLACE);
            new Thread(() -> check(context)).start();
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        }
    }

    /** App start: make sure a run is queued without moving an existing one. */
    public static void ensureScheduled(Context context) {
        if (isEnabled(context)) scheduleNext(context, ExistingWorkPolicy.KEEP);
    }

    static Operation scheduleNext(Context context, ExistingWorkPolicy policy) {
        return DailyNotifyWorker.enqueue(context, DailyNotifyWorker.SUMMARY, WORK_NAME,
                DailyNotifyWorker.intPref(prefs(context), KEY_HOUR, DEFAULT_HOUR), policy);
    }

    /** @return false only when the forecast could not be fetched (worth a retry). */
    public static boolean check(Context context) {
        if (!isEnabled(context)) return true;
        SharedPreferences prefs = prefs(context);
        int hourNow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (!inWindow(hourNow, DailyNotifyWorker.intPref(prefs, KEY_HOUR, DEFAULT_HOUR))) return true;

        double[] loc = HurricaneAlertHelper.resolveLocation(prefs);
        if (loc == null) return true;
        String model = WidgetRefreshWorker.forecastModel(prefs.getString("geauxweather_model", null));
        try {
            String body = HurricaneAlertHelper.httpGet(String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
                            + "&hourly=precipitation_probability,snowfall"
                            + "&forecast_days=1&timezone=auto&timeformat=unixtime%s%s",
                    loc[0], loc[1], Notify.imperial(prefs) ? "&temperature_unit=fahrenheit" : "",
                    model == null ? "" : "&models=best_match," + model), "application/json");
            if (body == null) {
                Log.w(WidgetRefreshWorker.TAG, "summary: download failed");
                return false;
            }
            JSONObject json = new JSONObject(body);
            if (model != null) {
                // Values the model lacks (JMA/Météo-France rain chance) come from Best match, as in the app
                WidgetRefreshWorker.pickModel(json, "daily", model, "best_match");
                WidgetRefreshWorker.pickModel(json, "hourly", model, "best_match");
            }
            JSONObject daily = json.getJSONObject("daily");
            long offset = json.optLong("utc_offset_seconds", 0);
            java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            day.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String today = day.format(new Date((daily.getJSONArray("time").getLong(0) + offset) * 1000));
            if (today.equals(prefs.getString(KEY_LAST, null))) return true;

            String title = title(daily.getJSONArray("temperature_2m_max").getDouble(0),
                    daily.getJSONArray("temperature_2m_min").getDouble(0),
                    daily.getJSONArray("weather_code").optInt(0, -1));
            JSONObject hourly = json.getJSONObject("hourly");
            JSONArray t = hourly.getJSONArray("time");
            JSONArray p = hourly.getJSONArray("precipitation_probability");
            JSONArray s = hourly.optJSONArray("snowfall");
            long[] times = new long[t.length()];
            int[] prob = new int[t.length()];
            double[] snow = new double[t.length()];
            for (int i = 0; i < times.length; i++) {
                times[i] = t.getLong(i);
                prob[i] = p.isNull(i) ? -1 : p.getInt(i);
                snow[i] = s == null || s.isNull(i) ? 0 : s.getDouble(i);
            }
            String rain = precipText(times, prob, snow, System.currentTimeMillis() / 1000, offset,
                    NextHourNotifyHelper.timeFormat(context, prefs.getString("geauxweather_clock", "auto")));
            String label = HurricaneAlertHelper.resolveLocationLabel(prefs);
            String text = rain == null ? label : rain + " · " + label;
            Log.i(WidgetRefreshWorker.TAG, "summary: " + title + " / " + text);
            if (Notify.post(context, CHANNEL_ID, "Daily summary", "Today's forecast, once a day at the time you choose",
                    NOTIF_ID, icon(daily.getJSONArray("weather_code").optInt(0, -1)), title, text, new Intent(context, MainActivity.class))) {
                prefs.edit().putString(KEY_LAST, today).apply();
            }
            return true;
        } catch (Exception e) {
            Log.w(WidgetRefreshWorker.TAG, "summary: failed: " + e);
            return false;
        }
    }

    /** From the chosen hour until noon. */
    static boolean inWindow(int hourNow, int hour) {
        return hourNow >= hour && hourNow < LAST_HOUR;
    }

    /** "Today: 57°/44° · Partly cloudy" (the condition without its emoji; none for an unknown code). */
    static String title(double high, double low, int code) {
        String t = "Today: " + Math.round(high) + "°/" + Math.round(low) + "°";
        String cond = WidgetRefreshWorker.condition(code, true);
        int space = cond.indexOf(' ');
        String text = space > 0 ? cond.substring(space + 1) : "";
        return text.isEmpty() || "—".equals(text) ? t : t + " · " + text;
    }

    /** Status-bar icon for the day's weather code (WMO): rain/snow/storms, clouds/fog, or sun. */
    static int icon(int code) {
        if (code >= 51) return R.drawable.ic_stat_rain;
        if (code == 2 || code == 3 || code == 45 || code == 48) return R.drawable.ic_stat_cloudy;
        return R.drawable.ic_stat_sunny;
    }

    /**
     * The rest of today's rain chance: "Rain unlikely", "Rain up to 70%" (already likely) or "Snow up to 60%
     * after 3 PM". Hours are the location's local time. Null when the forecast has no chances.
     * @param prob per hour, -1 when missing
     */
    static String precipText(long[] times, int[] prob, double[] snow, long nowSec, long offsetSec, java.text.DateFormat clock) {
        int peak = -1;
        int start = -1;
        int first = -1;
        boolean snowy = false;
        boolean rainy = false;
        for (int i = 0; i < times.length; i++) {
            if (times[i] + 3600 <= nowSec || prob[i] < 0) continue; // hours already over
            if (first < 0) first = i;
            peak = Math.max(peak, prob[i]);
            if (prob[i] < MENTION) continue;
            if (start < 0) start = i;
            if (snow[i] > 0) snowy = true;
            else rainy = true;
        }
        if (peak < 0) return null;
        if (peak < MENTION) return "Rain unlikely";
        String word = snowy && rainy ? "Rain or snow" : snowy ? "Snow" : "Rain";
        String text = word + " up to " + peak + "%";
        if (start == first) return text;
        clock.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); // times are shifted to the location's clock
        return text + " after " + clock.format(new Date((times[start] + offsetSec) * 1000));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Notify.PREFS, Context.MODE_PRIVATE);
    }
}
