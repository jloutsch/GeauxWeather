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

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lets the user choose which app the clock widget opens. Runs as the widget's
 * configure activity and again when the saved app is no longer installed.
 * Clock apps (anything handling SHOW_ALARMS) are listed first.
 */
public class ClockAppPickerActivity extends Activity {

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    static final class AppEntry {
        final String pkg;   // null = system default clock
        final String label;
        final Drawable icon;
        final boolean isClock;

        AppEntry(String pkg, String label, Drawable icon, boolean isClock) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.isClock = isClock;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID
                || !GeauxClockWidgetProvider.isOwnWidget(this, appWidgetId)) {
            finish();
            return;
        }
        // Dismissing the picker still places the widget; the clock then opens the system default.
        setResult(RESULT_OK, resultData());

        List<AppEntry> apps = loadApps();
        String current = GeauxClockWidgetProvider.getClockPackage(this, appWidgetId);

        ArrayAdapter<AppEntry> adapter = new ArrayAdapter<AppEntry>(this, android.R.layout.simple_list_item_1, apps) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                AppEntry e = getItem(position);
                boolean selected = e.pkg == null ? current == null : e.pkg.equals(current);
                row.setText(selected ? e.label + "  ✓" : e.label);
                int size = Math.round(32 * getResources().getDisplayMetrics().density);
                if (e.icon != null) e.icon.setBounds(0, 0, size, size);
                row.setCompoundDrawables(e.icon, null, null, null);
                row.setCompoundDrawablePadding(Math.round(16 * getResources().getDisplayMetrics().density));
                return row;
            }
        };

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.clock_picker_title)
                .setAdapter(adapter, (dialog, which) -> choose(apps.get(which).pkg))
                .setOnCancelListener(dialog -> finishAndUpdate())
                .show();
    }

    private void choose(String pkg) {
        GeauxClockWidgetProvider.setClockPackage(this, appWidgetId, pkg);
        finishAndUpdate();
    }

    private void finishAndUpdate() {
        GeauxClockWidgetProvider.updateAppWidget(this, AppWidgetManager.getInstance(this), appWidgetId);
        setResult(RESULT_OK, resultData());
        finish();
    }

    private Intent resultData() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
    }

    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();

        Set<String> clockPkgs = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)) {
            clockPkgs.add(ri.activityInfo.packageName);
        }

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<AppEntry> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            apps.add(new AppEntry(pkg, String.valueOf(ri.loadLabel(pm)), ri.loadIcon(pm), clockPkgs.contains(pkg)));
        }
        sortApps(apps);
        apps.add(0, new AppEntry(null, getString(R.string.clock_picker_default), null, true));
        return apps;
    }

    /** Clock apps first, then alphabetical by label. */
    static void sortApps(List<AppEntry> apps) {
        Collections.sort(apps, (a, b) -> {
            if (a.isClock != b.isClock) return a.isClock ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
    }
}
