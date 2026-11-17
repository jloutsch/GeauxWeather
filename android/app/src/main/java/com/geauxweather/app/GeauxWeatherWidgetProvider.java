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
import android.widget.RemoteViews;

import org.json.JSONObject;

/**
 * Home / lock-screen widget. Reads snapshot + style from Capacitor Preferences
 * (SharedPreferences file "CapacitorStorage").
 *
 * Keys:
 *   geauxweather_widget       — weather snapshot JSON
 *   geauxweather_widget_style — { bgOpacity 0–100, bgColor "#rrggbb", textColor "#rrggbb" }
 */
public class GeauxWeatherWidgetProvider extends AppWidgetProvider {

    private static final String PREFS_FILE = Notify.PREFS;
    private static final String KEY_WIDGET = "geauxweather_widget";
    private static final String KEY_STYLE = "geauxweather_widget_style";

    /** Where UV and air quality go, by widget size. */
    static final int EXTRAS_NONE = 0;
    static final int EXTRAS_SIDE = 1;
    static final int EXTRAS_BELOW = 2;

    /** Two rows tall: underneath. One row but wide (about 4 cells): to the right. Otherwise hidden. */
    static int extrasMode(int widthDp, int heightDp) {
        if (heightDp >= 100) return EXTRAS_BELOW;
        if (widthDp >= 220) return EXTRAS_SIDE;
        return EXTRAS_NONE;
    }

    /** Today-tile colors (www/css/app.css .aqi-*): good, moderate, usg, unhealthy, very, hazardous (tested). */
    static final int[] LEVEL_COLORS = {0xFF3DCC8C, 0xFFF0C14A, 0xFFF0A040, 0xFFE85D4C, 0xFFC44DFF, 0xFFA02040};
    /** EU AQI bands 0–5 share the Today tile's colors this way (euAqiLabel in www/js/weather.js). */
    private static final int[] EU_LEVEL = {0, 0, 1, 2, 3, 5};

    /** WHO UV band as a LEVEL_COLORS index (uvLabel in www/js/weather.js); -1 if unknown. */
    static int uvLevel(double uv) {
        if (Double.isNaN(uv)) return -1;
        long n = Math.round(uv);
        return n >= 11 ? 4 : n >= 8 ? 3 : n >= 6 ? 2 : n >= 3 ? 1 : 0;
    }

    /** US AQI for °F, EU AQI for °C, as a LEVEL_COLORS index; -1 if unknown. */
    static int aqiLevel(double aqi, boolean us) {
        if (Double.isNaN(aqi)) return -1;
        int band = AqiNotifyHelper.band(aqi, us);
        return us ? band : EU_LEVEL[band];
    }

    static String numberText(double v) {
        return Double.isNaN(v) ? "—" : String.valueOf(Math.round(v));
    }

    /** "UV 3" with the number in its level's color, like the Today tiles. */
    static CharSequence levelText(String label, double value, int level) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(label + " ");
        int start = b.length();
        b.append(numberText(value));
        if (level >= 0) {
            b.setSpan(new android.text.style.ForegroundColorSpan(LEVEL_COLORS[level]), start, b.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, b.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    static CharSequence uvText(Snapshot data) {
        return levelText("UV", data.uv, uvLevel(data.uv));
    }

    static CharSequence aqiText(Snapshot data) {
        return levelText("AQI", data.aqi, aqiLevel(data.aqi, data.usAqi));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId,
                                          android.os.Bundle newOptions) {
        updateAppWidget(context, appWidgetManager, appWidgetId); // resized: show or hide UV and air quality
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id);
        }
        WidgetRefreshWorker.ensureScheduled(context);
        SunClock.scheduleNext(context); // alarms are cleared on reboot; widgets get onUpdate after it
    }

    public static void refreshAll(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, GeauxWeatherWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids != null) {
            for (int id : ids) {
                updateAppWidget(context, mgr, id);
            }
        }
        GeauxClockWidgetProvider.refreshAll(context);
        SunClock.scheduleNext(context);
    }

    static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.geaux_widget);
        Snapshot data = readSnapshot(context);
        Style style = readStyle(context);

        views.setTextViewText(R.id.widget_location, data.location);
        views.setTextViewText(R.id.widget_temp, data.temp);
        views.setTextViewText(R.id.widget_condition, data.condition);
        views.setTextViewText(R.id.widget_hilow, data.hilow);

        // Portrait size: phones report the narrow width and tall height for portrait
        android.os.Bundle opts = appWidgetManager.getAppWidgetOptions(appWidgetId);
        int mode = extrasMode(opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT));
        CharSequence uv = uvText(data);
        CharSequence aqi = aqiText(data);
        views.setTextViewText(R.id.widget_uv, uv);
        views.setTextViewText(R.id.widget_aqi, aqi);
        views.setTextViewText(R.id.widget_uv_side, uv);
        views.setTextViewText(R.id.widget_aqi_side, aqi);
        views.setViewVisibility(R.id.widget_side, mode == EXTRAS_SIDE ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setViewVisibility(R.id.widget_below, mode == EXTRAS_BELOW ? android.view.View.VISIBLE : android.view.View.GONE);

        applyStyle(views, style);

        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    context,
                    appWidgetId,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            views.setOnClickPendingIntent(R.id.widget_root, pi);
        }

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    private static void applyStyle(RemoteViews views, Style style) {
        int bg = withAlpha(style.bgColor, style.bgOpacity);
        // RemoteViews: setBackgroundColor on the root LinearLayout
        views.setInt(R.id.widget_root, "setBackgroundColor", bg);

        int text = style.textColor;
        int muted = Color.argb(
                0xC0,
                Color.red(text),
                Color.green(text),
                Color.blue(text)
        );
        int dim = Color.argb(
                0x99,
                Color.red(text),
                Color.green(text),
                Color.blue(text)
        );

        views.setTextColor(R.id.widget_temp, text);
        views.setTextColor(R.id.widget_condition, muted);
        views.setTextColor(R.id.widget_location, dim);
        views.setTextColor(R.id.widget_hilow, dim);
        views.setTextViewTextSize(R.id.widget_hilow, android.util.TypedValue.COMPLEX_UNIT_SP, 12 + style.hiLowExtraSp);
        for (int id : new int[] {R.id.widget_uv, R.id.widget_aqi, R.id.widget_uv_side, R.id.widget_aqi_side}) {
            views.setTextColor(id, muted);
        }
    }

    static int withAlpha(int color, int opacity0to100) {
        int a = Math.max(0, Math.min(100, opacity0to100));
        int alpha = Math.round(a * 255f / 100f);
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int parseHexColor(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            String h = hex.trim();
            if (!h.startsWith("#")) h = "#" + h;
            // Support #RGB
            if (h.length() == 4) {
                char r = h.charAt(1), g = h.charAt(2), b = h.charAt(3);
                h = "#" + r + r + g + g + b + b;
            }
            return Color.parseColor(h);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Settings → High / low size: sp added to each widget's own high/low size (12sp here, 13sp on the clock). */
    static float hiLowExtraSp(String size) {
        if ("medium".equals(size)) return 3;
        if ("large".equals(size)) return 6;
        return 0;
    }

    static class Style {
        final int bgOpacity; // 0–100
        final int bgColor;   // RGB
        final int textColor; // RGB
        final float hiLowExtraSp;

        Style(int bgOpacity, int bgColor, int textColor, float hiLowExtraSp) {
            this.bgOpacity = bgOpacity;
            this.bgColor = bgColor;
            this.textColor = textColor;
            this.hiLowExtraSp = hiLowExtraSp;
        }
    }

    static Style readStyle(Context context) {
        // Default: fully transparent, white text (1.0.7 look)
        Style fallback = new Style(0, Color.parseColor("#141A22"), Color.WHITE, 0);
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_STYLE, null);
            if (raw == null) return fallback;
            JSONObject o = new JSONObject(raw);
            int opacity = o.optInt("bgOpacity", 0);
            int bg = parseHexColor(o.optString("bgColor", "#141A22"), Color.parseColor("#141A22"));
            int text = parseHexColor(o.optString("textColor", "#FFFFFF"), Color.WHITE);
            return new Style(opacity, bg, text, hiLowExtraSp(o.optString("hiLowSize", "small")));
        } catch (Exception e) {
            return fallback;
        }
    }

    static class Snapshot {
        final String location;
        final String temp;
        final String condition;
        final String hilow;
        final int weatherCode; // WMO code, -1 if unknown
        final int isDay;       // 1 day, 0 night, -1 unknown (snapshot written before is_day existed)
        final double uv;       // NaN if unknown
        final double aqi;      // NaN if unknown; US or EU scale per usAqi
        final boolean usAqi;   // US AQI for °F, EU AQI for °C

        Snapshot(String location, String temp, String condition, String hilow, int weatherCode, int isDay,
                 double uv, double aqi, boolean usAqi) {
            this.location = location;
            this.temp = temp;
            this.condition = condition;
            this.hilow = hilow;
            this.weatherCode = weatherCode;
            this.isDay = isDay;
            this.uv = uv;
            this.aqi = aqi;
            this.usAqi = usAqi;
        }
    }

    static Snapshot readSnapshot(Context context) {
        Snapshot fallback = new Snapshot("GeauxWeather", "—°", "Open app to load", "H —°  L —°", -1, -1,
                Double.NaN, Double.NaN, true);
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_WIDGET, null);
            if (raw == null) return fallback;
            JSONObject o = new JSONObject(raw);
            String loc = o.optString("label", "GeauxWeather");
            String temp = o.optString("temp", "—°");
            String cond = o.optString("condition", "—");
            String high = o.optString("high", "—");
            String low = o.optString("low", "—");
            int code = o.optInt("weatherCode", -1);
            int isDay = o.optInt("isDay", -1);
            // Day or night from the clock: the snapshot keeps the one from its download, which can be hours old
            Boolean sunUp = SunClock.isDaytimeAtHome(context);
            if (sunUp != null) {
                isDay = sunUp ? 1 : 0;
                if (code >= 0) cond = WidgetRefreshWorker.condition(code, sunUp);
            }
            return new Snapshot(loc, temp, cond, "H " + high + "  L " + low, code, isDay,
                    o.optDouble("uv", Double.NaN), o.optDouble("aqi", Double.NaN),
                    !"metric".equals(o.optString("units")));
        } catch (Exception e) {
            return fallback;
        }
    }
}
