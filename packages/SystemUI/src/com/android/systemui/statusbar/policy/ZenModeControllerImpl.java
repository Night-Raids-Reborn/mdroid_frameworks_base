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
 * limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.service.notification.Condition;
import android.service.notification.IConditionListener;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ZenRule;
import android.support.annotation.VisibleForTesting;
import android.util.Log;
import android.util.Slog;

import com.android.systemui.qs.GlobalSetting;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.util.Utils;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Platform implementation of the zen mode controller. **/
public class ZenModeControllerImpl extends CurrentUserTracker implements ZenModeController {
    private static final String TAG = "ZenModeController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private List<Callback> mCallbacks = new CopyOnWriteArrayList<Callback>();
    private final Context mContext;
    private final GlobalSetting mModeSetting;
    private final GlobalSetting mConfigSetting;
    private final NotificationManager mNoMan;
    private final LinkedHashMap<Uri, Condition> mConditions = new LinkedHashMap<Uri, Condition>();
    private final AlarmManager mAlarmManager;
    private final SetupObserver mSetupObserver;
    private final UserManager mUserManager;

    private int mUserId;
    private boolean mRequesting;
    private boolean mRegistered;
    private ZenModeConfig mConfig;

    public ZenModeControllerImpl(Context context, Handler handler) {
        super(context);
        mContext = context;
        mModeSetting = new GlobalSetting(mContext, handler, Global.ZEN_MODE) {
            @Override
            protected void handleValueChanged(int value) {
                fireZenChanged(value);
            }
        };
        mConfigSetting = new GlobalSetting(mContext, handler, Global.ZEN_MODE_CONFIG_ETAG) {
            @Override
            protected void handleValueChanged(int value) {
                updateZenModeConfig();
            }
        };
        mNoMan = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mConfig = mNoMan.getZenModeConfig();
        mModeSetting.setListening(true);
        mConfigSetting.setListening(true);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mSetupObserver = new SetupObserver(handler);
        mSetupObserver.register();
        mUserManager = context.getSystemService(UserManager.class);
        startTracking();
    }

    @Override
    public boolean isVolumeRestricted() {
        return mUserManager.hasUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME,
                new UserHandle(mUserId));
    }

    @Override
    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    @Override
    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    @Override
    public int getZen() {
        return mModeSetting.getValue();
    }

    @Override
    public void setZen(int zen, Uri conditionId, String reason) {
        mNoMan.setZenMode(zen, conditionId, reason);
    }

    @Override
    public boolean isZenAvailable() {
        return mSetupObserver.isDeviceProvisioned() && mSetupObserver.isUserSetup();
    }

    @Override
    public ZenRule getManualRule() {
        return mConfig == null ? null : mConfig.manualRule;
    }

    @Override
    public ZenModeConfig getConfig() {
        return mConfig;
    }

    @Override
    public long getNextAlarm() {
        final AlarmManager.AlarmClockInfo info = mAlarmManager.getNextAlarmClock(mUserId);
        return info != null ? info.getTriggerTime() : 0;
    }

    @Override
    public void onUserSwitched(int userId) {
        mUserId = userId;
        if (mRegistered) {
            mContext.unregisterReceiver(mReceiver);
        }
        final IntentFilter filter = new IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        filter.addAction(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
        mContext.registerReceiverAsUser(mReceiver, new UserHandle(mUserId), filter, null, null);
        mRegistered = true;
        mSetupObserver.register();
    }

    @Override
    public ComponentName getEffectsSuppressor() {
        return NotificationManager.from(mContext).getEffectsSuppressor();
    }

    @Override
    public boolean isCountdownConditionSupported() {
        return NotificationManager.from(mContext)
                .isSystemConditionProviderEnabled(ZenModeConfig.COUNTDOWN_PATH);
    }

    @Override
    public int getCurrentUser() {
        return ActivityManager.getCurrentUser();
    }

    private void fireNextAlarmChanged() {
        mCallbacks.forEach(c -> c.onNextAlarmChanged());
    }

    private void fireEffectsSuppressorChanged() {
        mCallbacks.forEach(c -> c.onEffectsSupressorChanged());
    }

    private void fireZenChanged(int zen) {
        mCallbacks.forEach(c -> c.onZenChanged(zen));
    }

    private void fireZenAvailableChanged(boolean available) {
        mCallbacks.forEach(c -> c.onZenAvailableChanged(available));
    }

    private void fireConditionsChanged(Condition[] conditions) {
        Utils.safeForeach(mCallbacks, c -> c.onConditionsChanged(conditions));
    }

    private void fireManualRuleChanged(ZenRule rule) {
        mCallbacks.forEach(c -> c.onManualRuleChanged(rule));
    }

    @VisibleForTesting
    protected void fireConfigChanged(ZenModeConfig config) {
        mCallbacks.forEach(c -> c.onConfigChanged(config));
    }

    private void updateConditions(Condition[] conditions) {
        if (conditions == null || conditions.length == 0) return;
        for (Condition c : conditions) {
            if ((c.flags & Condition.FLAG_RELEVANT_NOW) == 0) continue;
            mConditions.put(c.id, c);
        }
        fireConditionsChanged(
                mConditions.values().toArray(new Condition[mConditions.values().size()]));
    }

    private void updateZenModeConfig() {
        final ZenModeConfig config = mNoMan.getZenModeConfig();
        if (Objects.equals(config, mConfig)) return;
        final ZenRule oldRule = mConfig != null ? mConfig.manualRule : null;
        mConfig = config;
        fireConfigChanged(config);
        final ZenRule newRule = config != null ? config.manualRule : null;
        if (Objects.equals(oldRule, newRule)) return;
        fireManualRuleChanged(newRule);
    }

    private final IConditionListener mListener = new IConditionListener.Stub() {
        @Override
        public void onConditionsReceived(Condition[] conditions) {
            if (DEBUG) Slog.d(TAG, "onConditionsReceived "
                    + (conditions == null ? 0 : conditions.length) + " mRequesting=" + mRequesting);
            if (!mRequesting) return;
            updateConditions(conditions);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED.equals(intent.getAction())) {
                fireNextAlarmChanged();
            }
            if (NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED.equals(intent.getAction())) {
                fireEffectsSuppressorChanged();
            }
        }
    };

    private final class SetupObserver extends ContentObserver {
        private final ContentResolver mResolver;

        private boolean mRegistered;

        public SetupObserver(Handler handler) {
            super(handler);
            mResolver = mContext.getContentResolver();
        }

        public boolean isUserSetup() {
            return Secure.getIntForUser(mResolver, Secure.USER_SETUP_COMPLETE, 0, mUserId) != 0;
        }

        public boolean isDeviceProvisioned() {
            return Global.getInt(mResolver, Global.DEVICE_PROVISIONED, 0) != 0;
        }

        public void register() {
            if (mRegistered) {
                mResolver.unregisterContentObserver(this);
            }
            mResolver.registerContentObserver(
                    Global.getUriFor(Global.DEVICE_PROVISIONED), false, this);
            mResolver.registerContentObserver(
                    Secure.getUriFor(Secure.USER_SETUP_COMPLETE), false, this, mUserId);
            fireZenAvailableChanged(isZenAvailable());
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (Global.getUriFor(Global.DEVICE_PROVISIONED).equals(uri)
                    || Secure.getUriFor(Secure.USER_SETUP_COMPLETE).equals(uri)) {
                fireZenAvailableChanged(isZenAvailable());
            }
        }
    }
}
