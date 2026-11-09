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
import android.util.Log;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Date;

/**
 * "Rain (or snow) starting around 12:30 PM": checked by WidgetRefreshWorker every 30 minutes,
 * from the same Open-Meteo download, using the 15-minute forecast.
 */
public final class NextHourNotifyHelper {
    private static final String PREFS = "CapacitorStorage";
    private static final String KEY_ENABLED = "geauxweather_nexthour_notify"; // "on" / "off"
    private static final String KEY_WET = "geauxweather_nexthour_wet";        // last check saw rain now or soon
    private static final String KEY_LAST = "geauxweather_nexthour_last";      // epoch ms of last notification
    private static final String CHANNEL_ID = "geauxweather_nexthour";
    private static final int NOTIF_ID = 8200;

    /** Smallest 15-minute amount that counts (Open-Meteo reports 0.1 mm steps). */
    static final double MIN_MM = 0.1;
    /** A forecast that flips dry → wet → dry → wet must not notify twice in a short spell. */
    static final long COOLDOWN_MS = 2 * 60 * 60 * 1000L;

    private NextHourNotifyHelper() {}

    public static boolean isEnabled(Context context) {
        return "on".equals(prefs(context).getString(KEY_ENABLED, "off"));
    }

    /** Called from Settings. Starts or stops the shared 30-minute job as needed. */
    public static void setEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor edit = prefs(context).edit().putString(KEY_ENABLED, enabled ? "on" : "off");
        // A wet marker left from before it was turned off would swallow the next rain's notification
        if (enabled) edit.remove(KEY_WET);
        edit.apply();
        WidgetRefreshWorker.ensureScheduled(context); // start, re-time or stop the shared job
        if (enabled) {
            new Thread(() -> WidgetRefreshWorker.refresh(context)).start(); // check now, not in up to 30 min
        }
    }

    /**
     * @param times   epoch seconds marking the END of each 15-minute slot (Open-Meteo sums the preceding 15 min)
     * @param precip  mm per slot
     * @return epoch seconds when precipitation is expected to start within the next hour, or -1
     */
    static long startWithinHour(long[] times, double[] precip, long nowSec) {
        for (int i = 0; i < times.length && i < precip.length; i++) {
            long end = times[i];
            long start = end - 15 * 60;
            if (end <= nowSec) continue;
            if (start >= nowSec + 60 * 60) break;
            if (precip[i] >= MIN_MM) return Math.max(start, nowSec);
        }
        return -1;
    }

    /** Notify only when it turns wet soon after being dry, it is not already raining, and not twice in a spell. */
    static boolean shouldNotify(boolean wetSoon, boolean rainingNow, boolean wasWet, long nowMs, long lastMs) {
        return wetSoon && !rainingNow && !wasWet && nowMs - lastMs >= COOLDOWN_MS;
    }

    static String title(boolean snow, long startSec, long nowSec, String clock) {
        String what = snow ? "Snow" : "Rain";
        return startSec - nowSec < 10 * 60 ? what + " starting soon" : what + " starting around " + clock;
    }

    /** @param json the forecast with current.precipitation and minutely_15 precipitation/snowfall in unixtime */
    static void check(Context context, JSONObject json, String placeLabel) {
        if (!isEnabled(context)) return;
        JSONObject cur = json.optJSONObject("current");
        JSONObject m = json.optJSONObject("minutely_15");
        if (cur == null || m == null) return;
        JSONArray t = m.optJSONArray("time");
        JSONArray p = m.optJSONArray("precipitation");
        JSONArray s = m.optJSONArray("snowfall");
        if (t == null || p == null) return;
        long[] times = new long[t.length()];
        double[] precip = new double[t.length()];
        for (int i = 0; i < times.length; i++) {
            times[i] = t.optLong(i);
            precip[i] = p.optDouble(i, 0);
        }
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000;
        long start = startWithinHour(times, precip, nowSec);
        boolean wetSoon = start >= 0;
        boolean rainingNow = cur.optDouble("precipitation", 0) >= MIN_MM;

        SharedPreferences prefs = prefs(context);
        boolean wasWet = "1".equals(prefs.getString(KEY_WET, "0"));
        long last = parseLong(prefs.getString(KEY_LAST, "0"));
        boolean notify = shouldNotify(wetSoon, rainingNow, wasWet, nowMs, last);
        Log.i(WidgetRefreshWorker.TAG, "next-hour: wetSoon=" + wetSoon + " rainingNow=" + rainingNow
                + " wasWet=" + wasWet + " notify=" + notify);
        if (notify) {
            boolean snow = false;
            for (int i = 0; s != null && i < times.length; i++) {
                if (times[i] > nowSec && times[i] - 15 * 60 < nowSec + 60 * 60 && s.optDouble(i, 0) > 0) snow = true;
            }
            String clock = timeFormat(context, prefs.getString("geauxweather_clock", "auto")).format(new Date(start * 1000));
            if (post(context, title(snow, start, nowSec, clock), placeLabel)) {
                prefs.edit().putString(KEY_LAST, String.valueOf(nowMs)).apply();
            }
        }
        prefs.edit().putString(KEY_WET, (wetSoon || rainingNow) ? "1" : "0").apply();
    }

    /** App Settings → Time: "12" / "24" force a format; anything else follows the phone. */
    private static java.text.DateFormat timeFormat(Context context, String pref) {
        if ("12".equals(pref)) return new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault());
        if ("24".equals(pref)) return new java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault());
        return android.text.format.DateFormat.getTimeFormat(context);
    }

    private static boolean post(Context context, String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        ensureChannel(context);
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, NOTIF_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_rain)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        NotificationManagerCompat.from(context).notify(NOTIF_ID, b.build());
        return true;
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Rain or snow soon", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Rain or snow expected to start within the next hour");
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
