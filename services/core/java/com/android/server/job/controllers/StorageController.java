/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;
import com.android.server.storage.DeviceStorageMonitorService;

import java.io.PrintWriter;

/**
 * Simple controller that tracks the status of the device's storage.
 */
public final class StorageController extends StateController {
    private static final String TAG = "JobScheduler.Stor";

    private static final Object sCreationLock = new Object();
    private static volatile StorageController sController;

    private final ArraySet<JobStatus> mTrackedTasks = new ArraySet<JobStatus>();
    private StorageTracker mStorageTracker;

    public static StorageController get(JobSchedulerService taskManagerService) {
        synchronized (sCreationLock) {
            if (sController == null) {
                sController = new StorageController(taskManagerService,
                        taskManagerService.getContext(), taskManagerService.getLock());
            }
        }
        return sController;
    }

    @VisibleForTesting
    public StorageTracker getTracker() {
        return mStorageTracker;
    }

    @VisibleForTesting
    public static StorageController getForTesting(StateChangedListener stateChangedListener,
            Context context) {
        return new StorageController(stateChangedListener, context, new Object());
    }

    private StorageController(StateChangedListener stateChangedListener, Context context,
            Object lock) {
        super(stateChangedListener, context, lock);
        mStorageTracker = new StorageTracker();
        mStorageTracker.startTracking();
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus taskStatus, JobStatus lastJob) {
        if (taskStatus.hasStorageNotLowConstraint()) {
            mTrackedTasks.add(taskStatus);
            taskStatus.setTrackingController(JobStatus.TRACKING_STORAGE);
            taskStatus.setStorageNotLowConstraintSatisfied(mStorageTracker.isStorageNotLow());
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus taskStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (taskStatus.clearTrackingController(JobStatus.TRACKING_STORAGE)) {
            mTrackedTasks.remove(taskStatus);
        }
    }

    private void maybeReportNewStorageState() {
        final boolean storageNotLow = mStorageTracker.isStorageNotLow();
        boolean reportChange = false;
        synchronized (mLock) {
            for (int i = mTrackedTasks.size() - 1; i >= 0; i--) {
                final JobStatus ts = mTrackedTasks.valueAt(i);
                reportChange |= ts.setStorageNotLowConstraintSatisfied(storageNotLow);
            }
        }
        // Let the scheduler know that state has changed. This may or may not result in an
        // execution.
        if (reportChange) {
            mStateChangedListener.onControllerStateChanged();
        }
        // Also tell the scheduler that any ready jobs should be flushed.
        if (storageNotLow) {
            mStateChangedListener.onRunJobNow(null);
        }
    }

    public final class StorageTracker extends BroadcastReceiver {
        /**
         * Track whether storage is low.
         */
        private boolean mStorageLow;
        /** Sequence number of last broadcast. */
        private int mLastBatterySeq = -1;

        public StorageTracker() {
        }

        public void startTracking() {
            IntentFilter filter = new IntentFilter();

            // Storage status.  Just need to register, since STORAGE_LOW is a sticky
            // broadcast we will receive that if it is currently active.
            filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
            filter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
            mContext.registerReceiver(this, filter);
        }

        public boolean isStorageNotLow() {
            return !mStorageLow;
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
            final String action = intent.getAction();
            mLastBatterySeq = intent.getIntExtra(DeviceStorageMonitorService.EXTRA_SEQUENCE,
                    mLastBatterySeq);
            if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Available storage too low to do work. @ "
                            + SystemClock.elapsedRealtime());
                }
                mStorageLow = true;
            } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                if (DEBUG) {
                    Slog.d(TAG, "Available stoage high enough to do work. @ "
                            + SystemClock.elapsedRealtime());
                }
                mStorageLow = false;
                maybeReportNewStorageState();
            }
        }
    }

    @Override
    public void dumpControllerStateLocked(PrintWriter pw, int filterUid) {
        pw.print("Storage: not low = ");
        pw.print(mStorageTracker.isStorageNotLow());
        pw.print(", seq=");
        pw.println(mStorageTracker.getSeq());
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
