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

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.provider.AlarmClock;
import android.widget.RemoteViews;

import java.util.Calendar;

/**
 * Clock + weather widget. Left half: live clock, date and location; tapping it
 * opens the clock app chosen in {@link ClockAppPickerActivity}. Right half:
 * sun/moon/condition icon, current temp and high/low; tapping it opens
 * GeauxWeather. Weather and style come from the same snapshot the 2x1 widget reads.
 */
public class GeauxClockWidgetProvider extends AppWidgetProvider {

    static final String PREFS_FILE = "geauxweather_clock_widget";
    private static final String KEY_CLOCK_PKG_PREFIX = "clock_pkg_";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id);
        }
        WidgetRefreshWorker.ensureScheduled(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int id : appWidgetIds) {
            editor.remove(KEY_CLOCK_PKG_PREFIX + id);
        }
        editor.apply();
    }

    public static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, GeauxClockWidgetProvider.class));
        if (ids == null) return;
        for (int id : ids) {
            updateAppWidget(context, mgr, id);
        }
    }

    static boolean isOwnWidget(Context context, int appWidgetId) {
        android.appwidget.AppWidgetProviderInfo info =
                AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId);
        return info != null
                && info.provider.equals(new ComponentName(context, GeauxClockWidgetProvider.class));
    }

    static String getClockPackage(Context context, int appWidgetId) {
        return prefs(context).getString(KEY_CLOCK_PKG_PREFIX + appWidgetId, null);
    }

    static void setClockPackage(Context context, int appWidgetId, String pkg) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (pkg == null) {
            editor.remove(KEY_CLOCK_PKG_PREFIX + appWidgetId);
        } else {
            editor.putString(KEY_CLOCK_PKG_PREFIX + appWidgetId, pkg);
        }
        editor.apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.geaux_clock_widget);
        GeauxWeatherWidgetProvider.Snapshot data = GeauxWeatherWidgetProvider.readSnapshot(context);
        GeauxWeatherWidgetProvider.Style style = GeauxWeatherWidgetProvider.readStyle(context);

        boolean isDay = data.isDay >= 0 ? data.isDay == 1 : isDaytimeNow();
        views.setTextViewText(R.id.widget_location, data.location);
        views.setTextViewText(R.id.widget_icon, iconFor(data.weatherCode, data.condition, isDay));
        views.setTextViewText(R.id.widget_temp, data.temp);
        views.setTextViewText(R.id.widget_hilow, data.hilow);

        views.setInt(R.id.widget_root, "setBackgroundColor",
                GeauxWeatherWidgetProvider.withAlpha(style.bgColor, style.bgOpacity));
        int text = style.textColor;
        int muted = Color.argb(0xC0, Color.red(text), Color.green(text), Color.blue(text));
        int dim = Color.argb(0x99, Color.red(text), Color.green(text), Color.blue(text));
        views.setTextColor(R.id.clock_time, text);
        views.setTextColor(R.id.clock_date, muted);
        views.setTextColor(R.id.widget_location, dim);
        views.setTextColor(R.id.widget_temp, text);
        views.setTextColor(R.id.widget_hilow, dim);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Intent weather = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (weather != null) {
            weather.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            views.setOnClickPendingIntent(R.id.weather_area,
                    PendingIntent.getActivity(context, appWidgetId * 2, weather, flags));
        }

        views.setOnClickPendingIntent(R.id.clock_area,
                PendingIntent.getActivity(context, appWidgetId * 2 + 1,
                        clockIntent(context, appWidgetId), flags));

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    /**
     * Condition emoji from the snapshot ("☀️ Clear" → "☀️"), with a moon for
     * clear / mainly clear skies at night and a plain cloud for partly cloudy nights.
     */
    static String iconFor(int weatherCode, String condition, boolean isDay) {
        if (!isDay && (weatherCode == 0 || weatherCode == 1)) return "🌙";
        if (!isDay && weatherCode == 2) return "☁️";
        if (condition != null) {
            int space = condition.indexOf(' ');
            String first = space > 0 ? condition.substring(0, space) : "";
            if (!first.isEmpty() && !Character.isLetterOrDigit(first.codePointAt(0))) return first;
        }
        return "☁️";
    }

    private static boolean isDaytimeNow() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 6 && hour < 19;
    }

    /** Chosen clock app → system default clock → picker, in that order. */
    private static Intent clockIntent(Context context, int appWidgetId) {
        String pkg = getClockPackage(context, appWidgetId);
        if (pkg != null) {
            Intent chosen = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (chosen != null) {
                chosen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return chosen;
            }
        }
        Intent alarms = new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg == null && alarms.resolveActivity(context.getPackageManager()) != null) {
            return alarms;
        }
        return new Intent(context, ClockAppPickerActivity.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
