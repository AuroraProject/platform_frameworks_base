/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static com.android.systemui.statusbar.StatusBarState.FULLSCREEN_USER_SWITCHER;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

import android.annotation.NonNull;
import android.graphics.PointF;
import android.graphics.RectF;

import com.android.systemui.Dumpable;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.util.ViewController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Handles:
 * 1. registering for listeners when its view is attached and unregistering on view detached
 * 2. pausing udfps when fingerprintManager may still be running but we temporarily want to hide
 * the affordance. this allows us to fade the view in and out nicely (see shouldPauseAuth)
 * 3. sending events to its view including:
 *      - illumination events
 *      - sensor position changes
 *      - doze time event
 */
abstract class UdfpsAnimationViewController<T extends UdfpsAnimationView>
        extends ViewController<T> implements Dumpable {
    @NonNull final StatusBarStateController mStatusBarStateController;
    @NonNull final StatusBar mStatusBar;
    @NonNull final DumpManager mDumpManger;

    private boolean mNotificationShadeExpanded;
    private int mStatusBarState;

    protected UdfpsAnimationViewController(
            T view,
            @NonNull StatusBarStateController statusBarStateController,
            @NonNull StatusBar statusBar,
            @NonNull DumpManager dumpManager) {
        super(view);
        mStatusBarStateController = statusBarStateController;
        mStatusBar = statusBar;
        mDumpManger = dumpManager;
    }

    abstract @NonNull String getTag();

    @Override
    protected void onViewAttached() {
        mStatusBarStateController.addCallback(mStateListener);
        mStateListener.onStateChanged(mStatusBarStateController.getState());
        mStatusBar.addExpansionChangedListener(mStatusBarExpansionChangedListener);

        mDumpManger.registerDumpable(getTag(), this);
    }

    @Override
    protected void onViewDetached() {
        mStatusBarStateController.removeCallback(mStateListener);
        mStatusBar.removeExpansionChangedListener(mStatusBarExpansionChangedListener);

        mDumpManger.unregisterDumpable(getTag());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mStatusBarState=" + StatusBarState.toShortString(mStatusBarState));
        pw.println("mNotificationShadeExpanded=" + mNotificationShadeExpanded);
        pw.println("shouldPauseAuth()=" + shouldPauseAuth());
        pw.println("isPauseAuth=" + mView.isPauseAuth());
    }

    /**
     * Returns true if the fingerprint manager is running but we want to temporarily pause
     * authentication.
     */
    boolean shouldPauseAuth() {
        return (mNotificationShadeExpanded && mStatusBarState != KEYGUARD)
                || mStatusBarState == SHADE_LOCKED
                || mStatusBarState == FULLSCREEN_USER_SWITCHER;
    }

    /**
     * Send pause auth update to our view.
     */
    void updatePauseAuth() {
        if (mView.setPauseAuth(shouldPauseAuth())) {
            mView.postInvalidate();
        }
    }

    /**
     * Send sensor position change to our view. This rect contains paddingX and paddingY.
     */
    void onSensorRectUpdated(RectF sensorRect) {
        mView.onSensorRectUpdated(sensorRect);
    }

    /**
     * Send dozeTimeTick to view in case it wants to handle its burn-in offset.
     */
    void dozeTimeTick() {
        if (mView.dozeTimeTick()) {
            mView.postInvalidate();
        }
    }

    /**
     * @return the amount of translation needed if the view currently requires the user to touch
     *         somewhere other than the exact center of the sensor. For example, this can happen
     *         during guided enrollment.
     */
    PointF getTouchTranslation() {
        return new PointF(0, 0);
    }

    /**
     * X-Padding to add to left and right of the sensor rectangle area to increase the size of our
     * window to draw within.
     * @return
     */
    int getPaddingX() {
        return 0;
    }

    /**
     * Y-Padding to add to top and bottom of the sensor rectangle area to increase the size of our
     * window to draw within.
     */
    int getPaddingY() {
        return 0;
    }

    /**
     * Udfps has started illuminating and the fingerprint manager is working on authenticating.
     */
    void onIlluminationStarting() {
        mView.onIlluminationStarting();
        mView.postInvalidate();
    }

    /**
     * Udfps has stopped illuminating and the fingerprint manager is no longer attempting to
     * authenticate.
     */
    void onIlluminationStopped() {
        mView.onIlluminationStopped();
        mView.postInvalidate();
    }

    private final StatusBar.ExpansionChangedListener mStatusBarExpansionChangedListener =
            new StatusBar.ExpansionChangedListener() {
                @Override
                public void onExpansionChanged(float expansion, boolean expanded) {
                    mNotificationShadeExpanded = expanded;
                    mView.onExpansionChanged(expansion, expanded);
                    updatePauseAuth();
                }
            };

    private final StatusBarStateController.StateListener mStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    mStatusBarState = newState;
                    updatePauseAuth();
                }
            };
}
