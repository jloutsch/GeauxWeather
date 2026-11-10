// SPDX-License-Identifier: GPL-3.0-or-later
package com.geauxweather.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Day or night from the sun's position (same formulas as isDaytime() in www/js/weather.js), and a
 * redraw of the widgets at the next sunrise or sunset. Without it the widgets keep the day/night icon
 * of their last download, which Android can delay by an hour or more at night.
 */
public final class SunClock extends BroadcastReceiver {

    private static final long MIN = 60_000L;
    /** Look this far ahead for the next sunrise or sunset; none in that window means polar day or night. */
    private static final long HORIZON_MS = 36 * 60 * MIN;
    private static final long STEP_MS = 10 * MIN;

    /** Whether the sun is up (upper limb above the horizon, with refraction); about a minute of error. */
    static boolean isDaytime(long ms, double lat, double lon) {
        double rad = Math.PI / 180;
        double n = ms / 86400000.0 + 2440587.5 - 2451545.0; // days since J2000
        double meanLon = 280.46 + 0.9856474 * n;
        double anomaly = (357.528 + 0.9856003 * n) * rad;
        double eclLon = (meanLon + 1.915 * Math.sin(anomaly) + 0.02 * Math.sin(2 * anomaly)) * rad;
        double obliquity = (23.439 - 0.0000004 * n) * rad;
        double dec = Math.asin(Math.sin(obliquity) * Math.sin(eclLon));
        double ra = Math.atan2(Math.cos(obliquity) * Math.sin(eclLon), Math.cos(eclLon));
        double hourAngle = (280.46061837 + 360.98564736629 * n + lon) * rad - ra;
        double elevation = Math.asin(Math.sin(lat * rad) * Math.sin(dec)
                + Math.cos(lat * rad) * Math.cos(dec) * Math.cos(hourAngle));
        return elevation > -0.833 * rad;
    }

    /** Epoch ms of the next sunrise or sunset after fromMs (to the minute), or -1 if none within 36 h. */
    static long nextChange(long fromMs, double lat, double lon) {
        boolean now = isDaytime(fromMs, lat, lon);
        for (long t = fromMs + STEP_MS; t <= fromMs + HORIZON_MS; t += STEP_MS) {
            if (isDaytime(t, lat, lon) != now) {
                long lo = t - STEP_MS;
                long hi = t;
                while (hi - lo > MIN) {
                    long mid = (lo + hi) / 2;
                    if (isDaytime(mid, lat, lon) == now) lo = mid; else hi = mid;
                }
                return hi;
            }
        }
        return -1;
    }

    /** Day or night now at the home location, or null when no location is saved. */
    static Boolean isDaytimeAtHome(Context context) {
        double[] loc = HurricaneAlertHelper.resolveLocation(
                context.getSharedPreferences(Notify.PREFS, Context.MODE_PRIVATE));
        return loc == null ? null : isDaytime(System.currentTimeMillis(), loc[0], loc[1]);
    }

    /**
     * One non-waking alarm at the next sunrise or sunset (a minute after, so the sun has crossed). A
     * sleeping phone gets it when the screen comes on, which is when the widget is seen. Replaces any
     * earlier alarm; costs no network.
     */
    static void scheduleNext(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Notify.PREFS, Context.MODE_PRIVATE);
        double[] loc = HurricaneAlertHelper.resolveLocation(prefs);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(context, SunClock.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (loc == null) {
            am.cancel(pi);
            return;
        }
        long now = System.currentTimeMillis();
        long next = nextChange(now, loc[0], loc[1]);
        // Polar day or night: look again in 12 hours
        long at = next > 0 ? next + MIN : now + 12 * 60 * MIN;
        am.setWindow(AlarmManager.RTC, at, 5 * MIN, pi);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        GeauxWeatherWidgetProvider.refreshAll(context); // redraws both widgets and schedules the next change
    }
}
