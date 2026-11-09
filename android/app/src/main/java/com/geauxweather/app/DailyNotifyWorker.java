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

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.Operation;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * The once-a-day notifications: tomorrow's precipitation (evening) and high UV (morning). Each kind
 * runs at its own chosen hour, then queues its next day's run.
 */
public class DailyNotifyWorker extends Worker {

    static final String KIND = "kind";
    static final String PRECIP = "precip";
    static final String UV = "uv";
    static final String AQI = "aqi";
    /** Fetch failures: retry up to this many times (15-min linear backoff), then wait for tomorrow. */
    static final int MAX_RETRIES = 2;

    public DailyNotifyWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static Data input(String kind) {
        return new Data.Builder().putString(KIND, kind).build();
    }

    /** Queues one daily kind for the next HH:00 (tomorrow if past): one wake, one small request a day. */
    static Operation enqueue(Context context, String kind, String workName, int hour, ExistingWorkPolicy policy) {
        long delayMs = PrecipNotifyHelper.millisUntilNext(System.currentTimeMillis(), hour);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DailyNotifyWorker.class)
                .setInputData(input(kind))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build();
        return WorkManager.getInstance(context).enqueueUniqueWork(workName, policy, req);
    }

    /** Settings are stored as strings ("8", "70"); a missing or bad value gives the default. */
    static int intPref(SharedPreferences prefs, String key, int fallback) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isEnabled(Context ctx, String kind) {
        if (UV.equals(kind)) return UvNotifyHelper.isEnabled(ctx);
        if (AQI.equals(kind)) return AqiNotifyHelper.isEnabled(ctx);
        return PrecipNotifyHelper.isEnabled(ctx);
    }

    private static boolean check(Context ctx, String kind) {
        if (UV.equals(kind)) return UvNotifyHelper.check(ctx);
        if (AQI.equals(kind)) return AqiNotifyHelper.check(ctx);
        return PrecipNotifyHelper.check(ctx);
    }

    private static Operation scheduleNext(Context ctx, String kind) {
        if (UV.equals(kind)) return UvNotifyHelper.scheduleNext(ctx, ExistingWorkPolicy.REPLACE);
        if (AQI.equals(kind)) return AqiNotifyHelper.scheduleNext(ctx, ExistingWorkPolicy.REPLACE);
        return PrecipNotifyHelper.scheduleNext(ctx, ExistingWorkPolicy.REPLACE);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String kind = getInputData().getString(KIND);
        if (kind == null) kind = PRECIP;
        if (!isEnabled(ctx, kind)) return Result.success();

        boolean ok = check(ctx, kind);
        if (!ok && getRunAttemptCount() < MAX_RETRIES) return Result.retry();

        // Last step: REPLACE swaps this finished run for tomorrow's. Wait until it is stored, since the
        // process may be killed as soon as this worker returns.
        try {
            scheduleNext(ctx, kind).getResult().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            android.util.Log.w(WidgetRefreshWorker.TAG, "daily " + kind + ": next run not stored: " + e);
        }
        return Result.success();
    }
}
