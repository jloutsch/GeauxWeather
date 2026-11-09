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
import androidx.work.ExistingWorkPolicy;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Runs once a day at the chosen hour, then queues tomorrow's run. */
public class PrecipNotifyWorker extends Worker {

    /** Fetch failures: retry up to this many times (15-min linear backoff), then wait for tomorrow. */
    static final int MAX_RETRIES = 2;

    public PrecipNotifyWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!PrecipNotifyHelper.isEnabled(ctx)) return Result.success();

        boolean ok = PrecipNotifyHelper.check(ctx);
        if (!ok && getRunAttemptCount() < MAX_RETRIES) return Result.retry();

        // Last statement: REPLACE swaps this finished run for tomorrow's.
        PrecipNotifyHelper.scheduleNext(ctx, ExistingWorkPolicy.REPLACE);
        return Result.success();
    }
}
