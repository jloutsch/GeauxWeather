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

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * "Very high UV today (9)": once a day at the chosen morning hour, when today's forecast UV
 * maximum reaches the chosen level. Same daily scheduling as PrecipNotifyHelper.
 */
public final class UvNotifyHelper {

    static final String WORK_NAME = "geauxweather_uv_notify";
    private static final String CHANNEL_ID = "geauxweather_uv";
    private static final int NOTIF_ID = 8300;
    private static final String PREFS = "CapacitorStorage";
    private static final String KEY_ENABLED = "geauxweather_uv_notify"; // "on" / "off"
    private static final String KEY_LEVEL = "geauxweather_uv_level";    // "6" / "8" / "11"
    private static final String KEY_HOUR = "geauxweather_uv_hour";      // "7" / "8" / "9"
    private static final String KEY_LAST = "geauxweather_uv_last";      // date already notified for
    /** After this hour the midday peak has passed; a late run (or switching it on at night) stays quiet. */
    static final int LAST_HOUR = 15;

    private UvNotifyHelper() {}

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

    /** App start: make sure a run is queued without moving an existing one. */
    public static void ensureScheduled(Context context) {
        if (isEnabled(context)) scheduleNext(context, ExistingWorkPolicy.KEEP);
    }

    /** One wake and one small request per day, re-queued after each run (see PrecipNotifyHelper). */
    static void scheduleNext(Context context, ExistingWorkPolicy policy) {
        int hour = parseInt(prefs(context).getString(KEY_HOUR, "8"), 8);
        long delayMs = PrecipNotifyHelper.millisUntilNext(System.currentTimeMillis(), hour);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(UvNotifyWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, req);
    }

    /** @return false only when the forecast could not be fetched (worth a retry). */
    public static boolean check(Context context) {
        if (!isEnabled(context)) return true;
        SharedPreferences prefs = prefs(context);
        int hourNow = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hourNow < parseInt(prefs.getString(KEY_HOUR, "8"), 8) || hourNow >= LAST_HOUR) return true;

        double[] loc = HurricaneAlertHelper.resolveLocation(prefs);
        if (loc == null) return true;
        try {
            // Always Best match: ECMWF and GEM have no UV forecast
            String url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                            + "&daily=uv_index_max&forecast_days=1&timezone=auto",
                    loc[0], loc[1]);
            String body = HurricaneAlertHelper.httpGet(url, "application/json");
            if (body == null) {
                Log.w(WidgetRefreshWorker.TAG, "uv: download failed");
                return false;
            }
            JSONObject daily = new JSONObject(body).getJSONObject("daily");
            String today = daily.getJSONArray("time").getString(0);
            if (today.equals(prefs.getString(KEY_LAST, null))) return true;
            double uv = daily.getJSONArray("uv_index_max").optDouble(0, 0);
            int level = parseInt(prefs.getString(KEY_LEVEL, "8"), 8);
            String title = title(uv, level);
            Log.i(WidgetRefreshWorker.TAG, "uv: max " + uv + " (level " + level + ")"
                    + (title == null ? ", no notification" : ", notifying"));
            if (title == null) return true;
            String text = HurricaneAlertHelper.resolveLocationLabel(prefs) + " · strongest around midday";
            if (post(context, title, text)) prefs.edit().putString(KEY_LAST, today).apply();
            return true;
        } catch (Exception e) {
            Log.w(WidgetRefreshWorker.TAG, "uv: failed: " + e);
            return false;
        }
    }

    /** WHO categories; null when below the chosen level. */
    static String title(double uv, int level) {
        long n = Math.round(uv);
        if (n < level) return null;
        String category = n >= 11 ? "Extreme" : n >= 8 ? "Very high" : n >= 6 ? "High" : n >= 3 ? "Moderate" : "Low";
        return category + " UV today (" + n + ")";
    }

    // shortcut: third copy of the posting code (also PrecipNotifyHelper, NextHourNotifyHelper); share it in #28
    private static boolean post(Context context, String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "High UV", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("UV index forecast to reach your chosen level today");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, NOTIF_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sunny)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManagerCompat.from(context).notify(NOTIF_ID, b.build());
        return true;
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
