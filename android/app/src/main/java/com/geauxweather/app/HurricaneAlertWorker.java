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
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Background poll for location-based severe storm notifications. */
public class HurricaneAlertWorker extends Worker {

    public HurricaneAlertWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        // Old alert-only job still queued from an earlier version: do this check, then hand alerts
        // to the shared background job, which cancels this one.
        Context ctx = getApplicationContext();
        try {
            if (HurricaneAlertHelper.isEnabled(ctx)) HurricaneAlertHelper.check(ctx, false);
        } catch (Exception ignored) {
        }
        if (HurricaneAlertHelper.isEnabled(ctx)) HurricaneAlertHelper.schedule(ctx);
        else HurricaneAlertHelper.cancelSchedule(ctx);
        return Result.success();
    }
}
