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
import android.content.SharedPreferences;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.Operation;
import androidx.work.WorkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Re-queues every background job with this version's worker classes, so jobs queued by an older
 * version (whose workers may since have been removed) never run. Called from the update/boot
 * receiver and, as a fallback for apps that were force-stopped (Android skips their update
 * broadcast), on the first app open after each install.
 */
final class BackgroundJobs {

    private static final String KEY_INSTALL = "geauxweather_jobs_install_time"; // install the jobs were queued for

    private BackgroundJobs() {}

    /**
     * Blocks until WorkManager has stored every change (call off the main thread): after an update
     * the app may run only for its broadcast, and Android can kill it within a second, which lost an
     * un-awaited REPLACE on the S23. The install is marked done only after all of it is stored.
     */
    static void rescheduleAll(Context context) {
        WorkManager wm = WorkManager.getInstance(context);
        List<Operation> ops = new ArrayList<>();
        ops.add(wm.cancelUniqueWork(HurricaneAlertHelper.WORK_NAME)); // pre-#25 alert-only jobs
        ops.add(wm.cancelUniqueWork("geauxweather_hurricane_alerts"));
        ops.add(WidgetRefreshWorker.ensureScheduled(context)); // also runs the alert checks
        if (PrecipNotifyHelper.isEnabled(context)) ops.add(PrecipNotifyHelper.scheduleNext(context, ExistingWorkPolicy.REPLACE));
        if (UvNotifyHelper.isEnabled(context)) ops.add(UvNotifyHelper.scheduleNext(context, ExistingWorkPolicy.REPLACE));
        try {
            for (Operation op : ops) op.getResult().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(WidgetRefreshWorker.TAG, "background jobs not all re-queued; will retry on next open: " + e);
            return;
        }
        prefs(context).edit().putString(KEY_INSTALL, String.valueOf(installTime(context))).commit();
        Log.i(WidgetRefreshWorker.TAG, "background jobs re-queued for this install");
    }

    /** App open: re-queue once per install, in case the update broadcast never arrived. */
    static void afterInstall(Context context) {
        if (!String.valueOf(installTime(context)).equals(prefs(context).getString(KEY_INSTALL, null))) {
            rescheduleAll(context);
        }
    }

    private static long installTime(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            return 0;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(Notify.PREFS, Context.MODE_PRIVATE);
    }
}
