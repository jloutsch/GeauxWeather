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

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
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
    /** Fetch failures: retry up to this many times (15-min linear backoff), then wait for tomorrow. */
    static final int MAX_RETRIES = 2;

    public DailyNotifyWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    static Data input(String kind) {
        return new Data.Builder().putString(KIND, kind).build();
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        boolean uv = UV.equals(getInputData().getString(KIND));
        if (!(uv ? UvNotifyHelper.isEnabled(ctx) : PrecipNotifyHelper.isEnabled(ctx))) return Result.success();

        boolean ok = uv ? UvNotifyHelper.check(ctx) : PrecipNotifyHelper.check(ctx);
        if (!ok && getRunAttemptCount() < MAX_RETRIES) return Result.retry();

        // Last step: REPLACE swaps this finished run for tomorrow's. Wait until it is stored, since the
        // process may be killed as soon as this worker returns.
        try {
            (uv ? UvNotifyHelper.scheduleNext(ctx, ExistingWorkPolicy.REPLACE)
                    : PrecipNotifyHelper.scheduleNext(ctx, ExistingWorkPolicy.REPLACE)).getResult().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            android.util.Log.w(WidgetRefreshWorker.TAG, "daily " + (uv ? "uv" : "precip") + ": next run not stored: " + e);
        }
        return Result.success();
    }
}
