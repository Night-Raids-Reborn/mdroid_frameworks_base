/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.job.controllers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;

import java.io.PrintWriter;

/**
 * Simple controller that tracks whether the phone is charging or not. The phone is considered to
 * be charging when it's been plugged in for more than two minutes, and the system has broadcast
 * ACTION_BATTERY_OK.
 */
public final class BatteryController extends StateController {
    private static final String TAG = "JobScheduler.Batt";

    private static final Object sCreationLock = new Object();
    private static volatile BatteryController sController;

    private final ArraySet<JobStatus> mTrackedTasks = new ArraySet<>();
    private ChargingTracker mChargeTracker;

    public static BatteryController get(JobSchedulerService taskManagerService) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new BatteryController(taskManagerService,
                        taskManagerService.getContext(), taskManagerService.getLock());
            }
        }
        return sController;
    }

    @VisibleForTesting
    public ChargingTracker getTracker() {
        return mChargeTracker;
    }

    @VisibleForTesting
    public static BatteryController getForTesting(StateChangedListener stateChangedListener,
                                           Context context) {
        return new BatteryController(stateChangedListener, context, new Object());
    }

    private BatteryController(StateChangedListener stateChangedListener, Context context,
            Object lock) {
        super(stateChangedListener, context, lock);
        mChargeTracker = new ChargingTracker();
        mChargeTracker.startTracking();
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasPowerConstraint()) {
            mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(JobStatus.TRACKING_BATTERY);
            taskStatus.setChargingConstraintSatisfied(mChargeTracker.isOnStablePower());
            taskStatus.setBatteryNotLowConstraintSatisfied(mChargeTracker.isBatteryNotLow());
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob, boolean forUpdate) {
        if (taskStatus.clearTrackingController(JobStatus.TRACKING_BATTERY)) {
            mTrackedTasks.remove(taskStatus);
        }
    }

    private void maybeReportNewChargingStateLocked() {
        final boolean stablePower = mChargeTracker.isOnStablePower();
        final boolean batteryNotLow = mChargeTracker.isBatteryNotLow();
        if (DEBUG) {
            Slog.d(TAG, "maybeReportNewChargingStateLocked: " + stablePower);
        }
        boolean reportChange = false;
        for (int i = mTrackedTasks.size() - 1; i >= 0; i--) {
            final JobStatus ts = mTrackedTasks.valueAt(i);
            reportChange |= ts.setChargingConstraintSatisfied(stablePower);
            reportChange |= ts.setBatteryNotLowConstraintSatisfied(batteryNotLow);
        }

        if (stablePower || batteryNotLow) {
            // If one of our conditions has been satisfied, always schedule any newly ready jobs.
            mStateChangedListener.onRunJobNow(null);
        } else if (reportChange) {
            // Otherwise, just let the job scheduler know the state has changed and take care of it
            // as it thinks is best.
            mStateChangedListener.onControllerStateChanged();
        }
    }

    public final class ChargingTracker extends BroadcastReceiver {
        /**
         * Track whether we're "charging", where charging means that we're ready to commit to
         * doing work.
         */
        private boolean mCharging;
        /** Keep track of whether the battery is charged enough that we want to do work. */
        private boolean mBatteryHealthy;
        /** Sequence number of last broadcast. */
        private int mLastBatterySeq = -1;

        private BroadcastReceiver mMonitor;

        public ChargingTracker() {
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();

            // Battery health.
            filter.addAction(Intent.ACTION_BATTERY_LOW);
            filter.addAction(Intent.ACTION_BATTERY_OKAY);
            // Charging/not charging.
            filter.addAction(BatteryManager.ACTION_CHARGING);
            filter.addAction(BatteryManager.ACTION_DISCHARGING);
            mContext.registerReceiver(this, filter);

            // Initialise tracker state.
            BatteryManagerInternal batteryManagerInternal =
                    LocalServices.getService(BatteryManagerInternal.class);
            mBatteryHealthy = !batteryManagerInternal.getBatteryLevelLow();
            mCharging = batteryManagerInternal.isPowered(BatteryManager.BATTERY_PLUGGED_ANY);
        }

        public void setMonitorBatteryLocked(boolean enabled) {
            if (enabled) {
                if (mMonitor == null) {
                    mMonitor = new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) {
                            ChargingTracker.this.onReceive(context, intent);
                        }
                    };
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(Intent.ACTION_BATTERY_CHANGED);
                    mContext.registerReceiver(mMonitor, filter);
                }
            } else {
                if (mMonitor != null) {
                    mContext.unregisterReceiver(mMonitor);
                    mMonitor = null;
                }
            }
        }

        public boolean isOnStablePower() {
            return mCharging && mBatteryHealthy;
        }

        public boolean isBatteryNotLow() {
            return mBatteryHealthy;
        }

        public boolean isMonitoring() {
            return mMonitor != null;
        }

        public int getSeq() {
            return mLastBatterySeq;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            onReceiveInternal(intent);
        }

        @VisibleForTesting
        public void onReceiveInternal(Intent intent) {
            synchronized (mLock) {
                final String action = intent.getAction();
                if (Intent.ACTION_BATTERY_LOW.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Battery life too low to do work. @ "
                                + SystemClock.elapsedRealtime());
                    }
                    // If we get this action, the battery is discharging => it isn't plugged in so
                    // there's no work to cancel. We track this variable for the case where it is
                    // charging, but hasn't been for long enough to be healthy.
                    mBatteryHealthy = false;
                    maybeReportNewChargingStateLocked();
                } else if (Intent.ACTION_BATTERY_OKAY.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Battery life healthy enough to do work. @ "
                                + SystemClock.elapsedRealtime());
                    }
                    mBatteryHealthy = true;
                    maybeReportNewChargingStateLocked();
                } else if (BatteryManager.ACTION_CHARGING.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Received charging intent, fired @ "
                                + SystemClock.elapsedRealtime());
                    }
                    mCharging = true;
                    maybeReportNewChargingStateLocked();
                } else if (BatteryManager.ACTION_DISCHARGING.equals(action)) {
                    if (DEBUG) {
                        Slog.d(TAG, "Disconnected from power.");
                    }
                    mCharging = false;
                    maybeReportNewChargingStateLocked();
                }
                mLastBatterySeq = intent.getIntExtra(BatteryManager.EXTRA_SEQUENCE,
                        mLastBatterySeq);
            }
        }
    }

    @Override
    public void dumpControllerStateLocked(PrintWriter pw, int filterUid) {
        pw.print("Battery: stable power = ");
        pw.print(mChargeTracker.isOnStablePower());
        pw.print(", not low = ");
        pw.println(mChargeTracker.isBatteryNotLow());
        if (mChargeTracker.isMonitoring()) {
            pw.print("MONITORING: seq=");
            pw.println(mChargeTracker.getSeq());
        }
        pw.print("Tracking ");
        pw.print(mTrackedTasks.size());
        pw.println(":");
        for (int i = 0; i < mTrackedTasks.size(); i++) {
            final JobStatus js = mTrackedTasks.valueAt(i);
            if (!js.shouldDump(filterUid)) {
                continue;
            }
            pw.print("  #");
            js.printUniqueId(pw);
            pw.print(" from ");
            UserHandle.formatUid(pw, js.getSourceUid());
            pw.println();
        }
    }
}
