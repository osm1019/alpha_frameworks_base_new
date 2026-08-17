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

package com.android.systemui.statusbar;

import static android.app.admin.DevicePolicyResources.Strings.SystemUi.KEYGUARD_MANAGEMENT_DISCLOSURE;
import static android.app.admin.DevicePolicyResources.Strings.SystemUi.KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_START;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ACQUIRED_TOO_DARK;
import static android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_TIMEOUT;
import static android.hardware.biometrics.BiometricSourceType.FACE;
import static android.hardware.biometrics.BiometricSourceType.FINGERPRINT;
import static android.security.Flags.secureLockDevice;
import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_AVAILABLE;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FACE_NOT_RECOGNIZED;
import static com.android.keyguard.KeyguardUpdateMonitor.BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED;
import static com.android.systemui.DejankUtils.whitelistIpcs;
import static com.android.systemui.Flags.showLockedByYourWatchKeyguardIndicator;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.IMPORTANT_MSG_MIN_DURATION;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_IS_DISMISSIBLE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_ADAPTIVE_AUTH;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_ALIGNMENT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BATTERY;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_DISCLOSURE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_LOGOUT;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_NOW_PLAYING;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_OWNER_INFO;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_SECURE_LOCK_DEVICE;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_TRUST;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_USER_LOCKED;
import static com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController.INDICATION_TYPE_WATCH_DISCONNECTED;
import static com.android.systemui.keyguard.ScreenLifecycle.SCREEN_ON;
import static com.android.systemui.log.core.LogLevel.ERROR;
import static com.android.systemui.plugins.FalsingManager.LOW_PENALTY;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.hardware.biometrics.BiometricSourceType;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBatteryPropertiesRegistrar;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.keyguard.TrustGrantFlags;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.settingslib.Utils;
import com.android.settingslib.fuelgauge.BatteryStatus;
import com.android.systemui.Flags;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.FaceHelpMessageDeferral;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.biometrics.FaceHelpMessageDeferralFactory;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.BouncerMessageInteractor;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.domain.interactor.BiometricMessageInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryBiometricSettingsInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFingerprintAuthInteractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.keyguard.KeyguardIndication;
import com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.shared.model.AuthenticationFlags;
import com.android.systemui.keyguard.util.IndicationHelper;
import com.android.systemui.log.core.LogLevel;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.res.R;
import com.android.systemui.securelockdevice.domain.interactor.SecureLockDeviceInteractor;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.FaceUnlockImageView;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.KeyguardIndicationTextView;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.UserLogoutInteractor;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;
import com.android.systemui.util.ScrimUtils;

import dagger.Lazy;

import java.io.PrintWriter;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Controls the indications and error messages shown on the Keyguard
 *
 * On AoD, only one message shows with the following priorities:
 *   1. Biometric
 *   2. Transient
 *   3. Charging alignment
 *   4. Battery information
 *
 * On the lock screen, message rotate through different message types.
 *   See {@link KeyguardIndicationRotateTextViewController.IndicationType} for the list of types.
 */
@SysUISingleton
public class KeyguardIndicationController {

    public static final String TAG = "KeyguardIndication";
    private static final boolean DEBUG_CHARGING_SPEED = false;

    private static final String ACTION_AMBIENT_INDICATION_SHOW =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_SHOW";
    private static final String ACTION_AMBIENT_INDICATION_HIDE =
            "com.google.android.ambientindication.action.AMBIENT_INDICATION_HIDE";
    private static final String PERMISSION_AMBIENT_INDICATION =
            "com.google.android.ambientindication.permission.AMBIENT_INDICATION";
    /** ASI ambient-music channel + standalone Pixel Now Playing package. */
    private static final String PKG_ASI = "com.google.android.as";
    private static final String PKG_NOW_PLAYING = "com.google.android.apps.pixel.nowplaying";
    /** Alpha software poller FGS — not a song match notification. */
    private static final String PKG_NP_SOFTWARE = "com.alpha.nowplaying.software";
    private static final String CHANNEL_AMBIENT_MUSIC = "ambientmusic";
    private static final String EXTRA_AMBIENT_TEXT =
            "com.google.android.ambientindication.extra.TEXT";
    private static final String EXTRA_AMBIENT_SONG_TITLE =
            "com.google.android.ambientindication.extra.SONG_TITLE";
    private static final String EXTRA_AMBIENT_ARTIST_NAME =
            "com.google.android.ambientindication.extra.ARTIST_NAME";
    /** Stock Pixel AmbientIndication album cover (string URI). */
    private static final String EXTRA_AMBIENT_ALBUM_ART_URI =
            "com.google.android.ambientindication.extra.ALBUM_ART_URI";
    /** Inline album-art size next to Now Playing text (replaces music-note when loaded). */
    private static final float NOW_PLAYING_ART_SIZE_DP = 20f;
    /** iTunes Search — ASI often omits ALBUM_ART_URI on non-Pixel; covers still resolve by title/artist. */
    private static final String ITUNES_SEARCH_URL =
            "https://itunes.apple.com/search?term=%s&media=music&entity=song&limit=1";
    private static final int ART_HTTP_CONNECT_MS = 4000;
    private static final int ART_HTTP_READ_MS = 6000;

    private static final int MSG_SHOW_ACTION_TO_UNLOCK = 1;
    private static final int MSG_RESET_ERROR_MESSAGE_ON_SCREEN_ON = 2;
    private static final int MSG_SHOW_RECOGNIZING_FACE = 3;
    private static final int MSG_HIDE_RECOGNIZING_FACE = 4;
    private static final long TRANSIENT_BIOMETRIC_ERROR_TIMEOUT = 1300;
    public static final long DEFAULT_MESSAGE_TIME = 3500;
    public static final long DEFAULT_HIDE_DELAY_MS =
            DEFAULT_MESSAGE_TIME + KeyguardIndicationTextView.Y_IN_DURATION;

    private final Context mContext;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final KeyguardStateController mKeyguardStateController;
    protected final StatusBarStateController mStatusBarStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final AuthController mAuthController;
    private final KeyguardLogger mKeyguardLogger;
    private final UserTracker mUserTracker;
    private final BouncerMessageInteractor mBouncerMessageInteractor;
    private final Lazy<SecureLockDeviceInteractor> mSecureLockDeviceInteractor;

    private ViewGroup mIndicationArea;
    private FaceUnlockImageView mFaceIconView;
    private KeyguardIndicationTextView mTopIndicationView;
    private KeyguardIndicationTextView mLockScreenIndicationView;
    /** Dedicated Now Playing row, so the song never rotates with charging messages. */
    @Nullable private ViewGroup mNowPlayingPill;
    @Nullable private android.widget.ImageView mNowPlayingArtView;
    @Nullable private android.widget.TextView mNowPlayingTextView;
    private final IBatteryStats mBatteryInfo;
    private final SettableWakeLock mWakeLock;
    private final DockManager mDockManager;
    private final DevicePolicyManager mDevicePolicyManager;
    private final UserManager mUserManager;
    protected final @Main DelayableExecutor mExecutor;
    protected final @Background DelayableExecutor mBackgroundExecutor;
    private final LockPatternUtils mLockPatternUtils;
    private final FalsingManager mFalsingManager;
    private final KeyguardBypassController mKeyguardBypassController;
    private final AccessibilityManager mAccessibilityManager;
    private final Handler mHandler;
    private final AlternateBouncerInteractor mAlternateBouncerInteractor;

    @VisibleForTesting
    public KeyguardIndicationRotateTextViewController mRotateTextViewController;
    private BroadcastReceiver mBroadcastReceiver;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private KeyguardInteractor mKeyguardInteractor;
    private final BiometricMessageInteractor mBiometricMessageInteractor;
    private DeviceEntryFingerprintAuthInteractor mDeviceEntryFingerprintAuthInteractor;
    private DeviceEntryFaceAuthInteractor mDeviceEntryFaceAuthInteractor;
    private final UserLogoutInteractor mUserLogoutInteractor;
    private String mPersistentUnlockMessage;
    private String mAlignmentIndication;
    private boolean mForceIsDismissible;
    private CharSequence mTrustGrantedIndication;
    private CharSequence mTransientIndication;
    /** Song title for Ambient Now Playing, shown in the keyguard indication area (Charged). */
    private CharSequence mNowPlayingText;
    /**
     * Album art for the current song. When non-null, shown instead of {@code ic_now_playing_note}.
     * Loaded async from SHOW {@link #EXTRA_AMBIENT_ALBUM_ART_URI} or notification largeIcon.
     */
    @Nullable private Bitmap mNowPlayingAlbumArt;
    /** Bumps on each new song / hide so stale art loads are dropped. */
    private int mNowPlayingArtGeneration;
    private BroadcastReceiver mAmbientIndicationReceiver;
    private CommonNotifCollection mNotifCollection;
    private NotifCollectionListener mNowPlayingNotifListener;
    /** Active Now Playing notification keys — strip clears when this set empties after dismiss. */
    private final java.util.HashSet<String> mNowPlayingNotifKeys = new java.util.HashSet<>();
    private CharSequence mTrustAgentErrorMessage;
    private CharSequence mBiometricMessage;
    private CharSequence mBiometricMessageFollowUp;
    private BiometricSourceType mBiometricMessageSource;
    private ColorStateList mInitialTextColorState;
    private boolean mVisible;
    private boolean mSuppressIndication;
    private boolean mOrganizationOwnedDevice;

    // these all assume the device is plugged in (wired/wireless/docked) AND chargingOrFull:
    protected boolean mPowerPluggedIn;
    protected boolean mPowerPluggedInWired;
    protected boolean mPowerPluggedInWireless;
    protected boolean mPowerPluggedInDock;
    protected int mChargingSpeed;
    protected boolean mPowerCharged;
    protected int mChargingStatus;

    /** Whether the battery defender is triggered. */
    private boolean mBatteryDefender;
    /** Whether the battery defender is triggered with the device plugged. */
    private boolean mEnableBatteryDefender;
    private boolean mBatteryDead;
    private boolean mIncompatibleCharger;
    private float mChargingWattage;
    private int mBatteryLevel = -1;
    private boolean mBatteryPresent = true;
    protected long mChargingTimeRemaining;
    private long mLastChargeTimeComputeMs;
    private float mChargingCurrent;
    private float mChargingVoltage;
    private int mOemRatedWatts;
    private float mTemperature;
    private Pair<String, BiometricSourceType> mBiometricErrorMessageToShowOnScreenOn;
    private Set<Integer> mCoExFaceAcquisitionMsgIdsToShow;
    private final FaceHelpMessageDeferral mFaceAcquiredMessageDeferral;
    private boolean mInited;

    private boolean mFaceDetectionRunning;

    private int mCurrentDivider;

    private boolean mHasDashCharger;
    private boolean mHasWarpCharger;
    private boolean mHasVoocCharger;

    private IBatteryPropertiesRegistrar mBatteryPropertiesRegistrar;
    private boolean mAlternateFastchargeInfoUpdate;
    private boolean mFastchargeInfoPolling;

    private KeyguardUpdateMonitorCallback mUpdateMonitorCallback;

    private boolean mDozing;
    private final ScreenLifecycle mScreenLifecycle;
    @VisibleForTesting
    final Consumer<Set<Integer>> mCoExAcquisitionMsgIdsToShowCallback =
            (Set<Integer> coExFaceAcquisitionMsgIdsToShow) -> mCoExFaceAcquisitionMsgIdsToShow =
                    coExFaceAcquisitionMsgIdsToShow;
    @VisibleForTesting
    final Consumer<Boolean> mIsFingerprintEngagedCallback =
            (Boolean isEngaged) -> {
                if (!isEngaged) {
                    showTrustAgentErrorMessage(mTrustAgentErrorMessage);
                }
            };
    @VisibleForTesting
    final Consumer<Boolean> mIsLogoutEnabledCallback =
            (Boolean isLogoutEnabled) -> {
                if (mVisible) {
                    updateDeviceEntryIndication(false);
                }
            };
    @VisibleForTesting
    final Consumer<AuthenticationFlags> mDeviceEntryBiometricSettingsInteractorCallback =
            (AuthenticationFlags flags) -> mAuthenticationFlags = flags;

    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            mHandler.removeMessages(MSG_RESET_ERROR_MESSAGE_ON_SCREEN_ON);
            if (mBiometricErrorMessageToShowOnScreenOn != null) {
                String followUpMessage = mFaceLockedOutThisAuthSession
                        ? faceLockedOutFollowupMessage() : null;
                showBiometricMessage(
                        mBiometricErrorMessageToShowOnScreenOn.first,
                        followUpMessage,
                        mBiometricErrorMessageToShowOnScreenOn.second
                );
                // We want to keep this message around in case the screen was off
                hideBiometricMessageDelayed(DEFAULT_HIDE_DELAY_MS);
                mBiometricErrorMessageToShowOnScreenOn = null;
            }
        }

        @Override
        public void onScreenTurnedOff() {
            if (mFaceDetectionRunning) {
                mFaceDetectionRunning = false;
                mBiometricErrorMessageToShowOnScreenOn = null;
                hideFaceUnlockRecognizingMessage();
            }
        }
    };
    private boolean mFaceLockedOutThisAuthSession;

    // Use AlarmTimeouts to guarantee that the events are handled even if scheduled and
    // triggered while the device is asleep
    private final AlarmTimeout mHideTransientMessageHandler;
    private final AlarmTimeout mHideBiometricMessageHandler;
    private final IndicationHelper mIndicationHelper;

    private final DeviceEntryBiometricSettingsInteractor mDeviceEntryBiometricSettingsInteractor;
    private AuthenticationFlags mAuthenticationFlags;

    public interface IndicationListener {
        void onIndicationUpdated(int type, @Nullable CharSequence text);
    }
    public static final int AX_TYPE_BIOMETRIC = 0;
    public static final int AX_TYPE_TRANSIENT = 1;
    public static final int AX_TYPE_TRUST = 2;
    public static final int AX_TYPE_DISCLOSURE = 3;
    public static final int AX_TYPE_OWNER_INFO = 4;
    public static final int AX_TYPE_ALIGNMENT = 5;
    public static final int AX_TYPE_PERSISTENT_UNLOCK = 6;
    private final java.util.List<IndicationListener> mIndicationListeners =
            new java.util.ArrayList<>();
    public void addIndicationListener(IndicationListener listener) {
        mIndicationListeners.add(listener);
    }
    public void removeIndicationListener(IndicationListener listener) {
        mIndicationListeners.remove(listener);
    }
    private void notifyIndicationListeners(int type, @Nullable CharSequence text) {
        for (IndicationListener listener : mIndicationListeners) {
            listener.onIndicationUpdated(type, text);
        }
    }
    /**
     * Creates a new KeyguardIndicationController and registers callbacks.
     */
    @Inject
    public KeyguardIndicationController(
            Context context,
            @Main Looper mainLooper,
            WakeLock.Builder wakeLockBuilder,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            DockManager dockManager,
            BroadcastDispatcher broadcastDispatcher,
            DevicePolicyManager devicePolicyManager,
            IBatteryStats iBatteryStats,
            UserManager userManager,
            @Main DelayableExecutor executor,
            @Background DelayableExecutor bgExecutor,
            FalsingManager falsingManager,
            AuthController authController,
            LockPatternUtils lockPatternUtils,
            ScreenLifecycle screenLifecycle,
            KeyguardBypassController keyguardBypassController,
            AccessibilityManager accessibilityManager,
            FaceHelpMessageDeferralFactory faceHelpMessageDeferral,
            KeyguardLogger keyguardLogger,
            AlternateBouncerInteractor alternateBouncerInteractor,
            AlarmManager alarmManager,
            UserTracker userTracker,
            BouncerMessageInteractor bouncerMessageInteractor,
            IndicationHelper indicationHelper,
            DeviceEntryBiometricSettingsInteractor deviceEntryBiometricSettingsInteractor,
            KeyguardInteractor keyguardInteractor,
            BiometricMessageInteractor biometricMessageInteractor,
            DeviceEntryFingerprintAuthInteractor deviceEntryFingerprintAuthInteractor,
            DeviceEntryFaceAuthInteractor deviceEntryFaceAuthInteractor,
            UserLogoutInteractor userLogoutInteractor,
            Lazy<SecureLockDeviceInteractor> secureLockDeviceInteractor,
            CommonNotifCollection notifCollection
    ) {
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mNotifCollection = notifCollection;
        mDevicePolicyManager = devicePolicyManager;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDockManager = dockManager;
        mWakeLock = new SettableWakeLock(
                wakeLockBuilder.setTag("Doze:KeyguardIndication").build(), TAG);
        mBatteryInfo = iBatteryStats;
        mUserManager = userManager;
        mExecutor = executor;
        mBackgroundExecutor = bgExecutor;
        mLockPatternUtils = lockPatternUtils;
        mAuthController = authController;
        mFalsingManager = falsingManager;
        mKeyguardBypassController = keyguardBypassController;
        mAccessibilityManager = accessibilityManager;
        mScreenLifecycle = screenLifecycle;
        mKeyguardLogger = keyguardLogger;
        mScreenLifecycle.addObserver(mScreenObserver);
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        mUserTracker = userTracker;
        mBouncerMessageInteractor = bouncerMessageInteractor;
        mIndicationHelper = indicationHelper;
        mDeviceEntryBiometricSettingsInteractor = deviceEntryBiometricSettingsInteractor;
        mKeyguardInteractor = keyguardInteractor;
        mBiometricMessageInteractor = biometricMessageInteractor;
        mDeviceEntryFingerprintAuthInteractor = deviceEntryFingerprintAuthInteractor;
        mDeviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor;
        mUserLogoutInteractor = userLogoutInteractor;
        mSecureLockDeviceInteractor = secureLockDeviceInteractor;

        mFaceAcquiredMessageDeferral = faceHelpMessageDeferral.create();

        mHandler = new Handler(mainLooper) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == MSG_SHOW_ACTION_TO_UNLOCK) {
                    showActionToUnlock();
                } else if (msg.what == MSG_RESET_ERROR_MESSAGE_ON_SCREEN_ON) {
                    mBiometricErrorMessageToShowOnScreenOn = null;
                } else if (msg.what == MSG_SHOW_RECOGNIZING_FACE) {
                    mBiometricErrorMessageToShowOnScreenOn = null;
                    showFaceUnlockRecognizingMessage();
                } else if (msg.what == MSG_HIDE_RECOGNIZING_FACE) {
                    hideFaceUnlockRecognizingMessage();
                }
            }
        };

        mHideTransientMessageHandler = new AlarmTimeout(
                alarmManager,
                this::hideTransientIndication,
                TAG,
                mHandler
        );
        mHideBiometricMessageHandler = new AlarmTimeout(
                alarmManager,
                this::hideBiometricMessage,
                TAG,
                mHandler
        );

        mHasDashCharger = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_hasDashCharger);
        mHasWarpCharger = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_hasWarpCharger);
        mHasVoocCharger = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_hasVoocCharger);
    }

    /** Call this after construction to finish setting up the instance. */
    public void init() {
        if (mInited) {
            return;
        }
        mInited = true;

        mDockManager.addAlignmentStateListener(
                alignState -> mHandler.post(() -> handleAlignStateChanged(alignState)));
        mKeyguardUpdateMonitor.registerCallback(getKeyguardCallback());
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);

        mStatusBarStateListener.onDozingChanged(mStatusBarStateController.isDozing());

        mCurrentDivider = mContext.getResources().getInteger(R.integer.config_currentInfoDivider);

        mAlternateFastchargeInfoUpdate =
                    mContext.getResources().getBoolean(R.bool.config_alternateFastchargeInfoUpdate);
        if (mAlternateFastchargeInfoUpdate) {
            mBatteryPropertiesRegistrar =
                    IBatteryPropertiesRegistrar.Stub.asInterface(
                    ServiceManager.getService("batteryproperties"));
        }
    }

    @Nullable
    public ViewGroup getIndicationArea() {
        return mIndicationArea;
    }

    /**
     * Notify controller about configuration changes.
     */
    public void onConfigurationChanged() {
        // Get new text color in case theme has changed
        if (Flags.indicationTextA11yFix()) {
            setIndicationColorToThemeColor();
        }
    }

    public void setIndicationAreaTop(ViewGroup indicationAreaTop) {
        mFaceIconView = indicationAreaTop.findViewById(R.id.face_unlock_icon);
        if (mFaceIconView != null) {
            mFaceIconView.updateColor();
        }
    }

    public void setIndicationArea(ViewGroup indicationArea) {
        mIndicationArea = indicationArea;
        mTopIndicationView = indicationArea.findViewById(R.id.keyguard_indication_text);
        mLockScreenIndicationView = indicationArea.findViewById(
                R.id.keyguard_indication_text_bottom);
        mNowPlayingPill = indicationArea.findViewById(R.id.keyguard_now_playing_pill);
        mNowPlayingArtView = indicationArea.findViewById(R.id.keyguard_now_playing_art);
        mNowPlayingTextView = indicationArea.findViewById(R.id.keyguard_now_playing_text);
        if (Flags.indicationTextA11yFix()) {
            setIndicationColorToThemeColor();
        } else {
            setIndicationTextColor(mTopIndicationView != null
                    ? mTopIndicationView.getTextColors() : ColorStateList.valueOf(Color.WHITE));
        }
        if (mRotateTextViewController != null) {
            mRotateTextViewController.destroy();
        }
        mRotateTextViewController = new KeyguardIndicationRotateTextViewController(
                mLockScreenIndicationView,
                mExecutor,
                mStatusBarStateController,
                mKeyguardLogger
        );
        updateDeviceEntryIndication(false /* animate */);
        updateOrganizedOwnedDevice();
        if (mBroadcastReceiver == null) {
            // Update the disclosure proactively to avoid IPC on the critical path.
            mBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateOrganizedOwnedDevice();
                }
            };
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
            intentFilter.addAction(Intent.ACTION_USER_REMOVED);
            mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, intentFilter);
        }
        registerAmbientIndicationReceiver();
        registerNowPlayingNotifListener();

        collectFlow(mIndicationArea,
                mBiometricMessageInteractor.getCoExFaceAcquisitionMsgIdsToShow(),
                mCoExAcquisitionMsgIdsToShowCallback);
        collectFlow(mIndicationArea, mDeviceEntryFingerprintAuthInteractor.isEngaged(),
                mIsFingerprintEngagedCallback);
        collectFlow(mIndicationArea,
                mUserLogoutInteractor.isLogoutEnabled(),
                mIsLogoutEnabledCallback);
        collectFlow(mIndicationArea,
                mDeviceEntryBiometricSettingsInteractor.getAuthenticationFlags(),
                mDeviceEntryBiometricSettingsInteractorCallback);
    }

    @NonNull
    private ColorStateList wallpaperTextColor() {
        return ColorStateList.valueOf(
                Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor));
    }

    /**
     * Cleanup
     */
    public void destroy() {
        mHandler.removeCallbacksAndMessages(null);
        mHideBiometricMessageHandler.cancel();
        mHideTransientMessageHandler.cancel();
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        if (mAmbientIndicationReceiver != null) {
            try {
                mContext.unregisterReceiver(mAmbientIndicationReceiver);
            } catch (IllegalArgumentException ignored) {
                // already unregistered
            }
            mAmbientIndicationReceiver = null;
        }
        if (mNowPlayingNotifListener != null && mNotifCollection != null) {
            mNotifCollection.removeCollectionListener(mNowPlayingNotifListener);
            mNowPlayingNotifListener = null;
        }
        mNowPlayingNotifKeys.clear();
    }

    /**
     * When the user swipes away the Now Playing notification, ASI often does not send
     * AMBIENT_INDICATION_HIDE. Only clear the keyguard strip on explicit user cancel —
     * ranking updates/reposts use remove+add and must not wipe the strip mid-song.
     */
    private void registerNowPlayingNotifListener() {
        if (mNotifCollection == null || mNowPlayingNotifListener != null) {
            return;
        }
        mNowPlayingNotifListener = new NotifCollectionListener() {
            @Override
            public void onEntryAdded(@NonNull NotificationEntry entry) {
                trackNowPlayingNotif(entry, /* present= */ true, /* userDismissed= */ false);
            }

            @Override
            public void onEntryUpdated(NotificationEntry entry) {
                trackNowPlayingNotif(entry, /* present= */ true, /* userDismissed= */ false);
            }

            @Override
            public void onEntryRemoved(@NonNull NotificationEntry entry, int reason) {
                final boolean userDismissed =
                        reason == REASON_CANCEL || reason == REASON_CANCEL_ALL;
                trackNowPlayingNotif(entry, /* present= */ false, userDismissed);
            }
        };
        mNotifCollection.addCollectionListener(mNowPlayingNotifListener);
    }

    private void trackNowPlayingNotif(
            NotificationEntry entry, boolean present, boolean userDismissed) {
        if (entry == null || entry.getSbn() == null) {
            return;
        }
        if (!isNowPlayingNotification(entry.getSbn())) {
            return;
        }
        final String key = entry.getKey();
        final CharSequence notifText =
                present ? extractNowPlayingTextFromNotif(entry.getSbn()) : null;
        mHandler.post(() -> {
            if (present) {
                mNowPlayingNotifKeys.add(key);
                // ASI sometimes sends AMBIENT_INDICATION_SHOW with empty TEXT while
                // the song notif has a real title. Prefer notif title for the strip.
                if (!TextUtils.isEmpty(notifText)
                        && (TextUtils.isEmpty(mNowPlayingText)
                                || !TextUtils.equals(mNowPlayingText, notifText))) {
                    mNowPlayingText = notifText;
                    updateNowPlayingIndication();
                }
                // Prefer real cover art over the music-note fallback when notif has a largeIcon.
                if (present && mNowPlayingAlbumArt == null) {
                    loadNowPlayingArtFromNotifAsync(entry.getSbn(), mNowPlayingArtGeneration);
                }
            } else {
                mNowPlayingNotifKeys.remove(key);
                // System reposts/updates must not clear the strip. Only user swipe/clear-all.
                if (userDismissed
                        && mNowPlayingNotifKeys.isEmpty()
                        && !TextUtils.isEmpty(mNowPlayingText)) {
                    mNowPlayingText = null;
                    clearNowPlayingAlbumArt();
                    updateNowPlayingIndication();
                }
            }
        });
    }

    /**
     * Song match notifications only — not the software FGS placeholder.
     */
    private static boolean isNowPlayingNotification(StatusBarNotification sbn) {
        final String pkg = sbn.getPackageName();
        if (pkg == null || PKG_NP_SOFTWARE.equals(pkg)) {
            return false;
        }
        if (PKG_NOW_PLAYING.equals(pkg)) {
            return true;
        }
        if (PKG_ASI.equals(pkg)) {
            final Notification n = sbn.getNotification();
            final String channel = n != null ? n.getChannelId() : null;
            if (channel == null) {
                return false;
            }
            final String ch = channel.toLowerCase(java.util.Locale.US);
            return ch.contains(CHANNEL_AMBIENT_MUSIC)
                    || ch.contains("musicnotification")
                    || ch.contains("now_playing");
        }
        return false;
    }

    /**
     * Build "Title • Artist" (or title alone) from a Now Playing notification.
     * Handles "Song by Artist" and "Song - Artist" styles used by ASI / NP app.
     */
    @Nullable
    private static CharSequence extractNowPlayingTextFromNotif(StatusBarNotification sbn) {
        final Notification n = sbn.getNotification();
        if (n == null || n.extras == null) {
            return null;
        }
        CharSequence titleCs = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (TextUtils.isEmpty(titleCs) && TextUtils.isEmpty(textCs)) {
            return null;
        }
        final String title = titleCs != null ? titleCs.toString().trim() : "";
        final String text = textCs != null ? textCs.toString().trim() : "";
        // "Song by Artist"
        final java.util.regex.Matcher by =
                java.util.regex.Pattern.compile(
                                "(.+?)\\s+by\\s+(.+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(title);
        if (by.matches()) {
            return by.group(1).trim() + " • " + by.group(2).trim();
        }
        // "Song - Artist" / en-dash
        final String[] dash = title.split("\\s[-–]\\s", 2);
        if (dash.length == 2
                && !dash[0].isEmpty()
                && !dash[1].isEmpty()) {
            return dash[0].trim() + " • " + dash[1].trim();
        }
        if (!title.isEmpty() && !text.isEmpty() && !title.equals(text)) {
            return title + " • " + text;
        }
        if (!title.isEmpty()) {
            return title;
        }
        return text.isEmpty() ? null : text;
    }

    private void registerAmbientIndicationReceiver() {
        if (mAmbientIndicationReceiver != null) {
            return;
        }
        mAmbientIndicationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) {
                    return;
                }
                final String action = intent.getAction();
                mHandler.post(() -> handleAmbientIndication(action, intent));
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_AMBIENT_INDICATION_SHOW);
        filter.addAction(ACTION_AMBIENT_INDICATION_HIDE);
        mContext.registerReceiverAsUser(
                mAmbientIndicationReceiver,
                UserHandle.ALL,
                filter,
                PERMISSION_AMBIENT_INDICATION,
                mHandler,
                Context.RECEIVER_EXPORTED);
    }

    private void handleAmbientIndication(String action, Intent intent) {
        if (ACTION_AMBIENT_INDICATION_HIDE.equals(action)) {
            mNowPlayingText = null;
            clearNowPlayingAlbumArt();
            updateNowPlayingIndication();
            return;
        }
        if (!ACTION_AMBIENT_INDICATION_SHOW.equals(action)) {
            return;
        }
        CharSequence text = intent.getCharSequenceExtra(EXTRA_AMBIENT_TEXT);
        CharSequence title = intent.getCharSequenceExtra(EXTRA_AMBIENT_SONG_TITLE);
        CharSequence artist = intent.getCharSequenceExtra(EXTRA_AMBIENT_ARTIST_NAME);
        CharSequence message = null;
        if (!TextUtils.isEmpty(title) && !TextUtils.isEmpty(artist)) {
            message = title + " • " + artist;
        } else if (!TextUtils.isEmpty(title)) {
            message = title;
        } else if (!TextUtils.isEmpty(text)) {
            message = text;
        } else if (!TextUtils.isEmpty(artist)) {
            message = artist;
        }
        if (TextUtils.isEmpty(message)) {
            mNowPlayingText = null;
            clearNowPlayingAlbumArt();
        } else {
            final boolean sameSong = TextUtils.equals(mNowPlayingText, message);
            mNowPlayingText = message;
            final String artUri = intent.getStringExtra(EXTRA_AMBIENT_ALBUM_ART_URI);
            // ASI re-broadcasts SHOW every ~30s, almost always without ALBUM_ART_URI
            // (LockScreenAlbumArtManager rarely fills albumart_exports on non-Pixel).
            // Do not wipe a cover we already resolved for the same song.
            if (!sameSong) {
                clearNowPlayingAlbumArt();
                loadNowPlayingArtAsync(artUri, title, artist, mNowPlayingArtGeneration);
            } else if (!TextUtils.isEmpty(artUri)) {
                // Same song + ASI finally provided a FileProvider URI — upgrade art.
                loadNowPlayingArtAsync(artUri, title, artist, mNowPlayingArtGeneration);
            } else if (mNowPlayingAlbumArt == null) {
                // Same song still missing art — retry notif / metadata fallbacks.
                loadNowPlayingArtAsync(/* uriString= */ null, title, artist,
                        mNowPlayingArtGeneration);
            }
        }
        updateNowPlayingIndication();
    }

    private void clearNowPlayingAlbumArt() {
        mNowPlayingArtGeneration++;
        mNowPlayingAlbumArt = null;
    }

    /**
     * Load album art on a background thread.
     * <ol>
     *   <li>ASI {@code ALBUM_ART_URI} (content FileProvider)</li>
     *   <li>ASI / Now Playing notification largeIcon</li>
     *   <li>iTunes Search by title + artist (non-Pixel ASI often never exports covers)</li>
     * </ol>
     * Only applied if {@code generation} still matches.
     */
    private void loadNowPlayingArtAsync(
            @Nullable String uriString,
            @Nullable CharSequence title,
            @Nullable CharSequence artist,
            int generation) {
        mBackgroundExecutor.execute(() -> {
            Bitmap bitmap = decodeAlbumArtUri(uriString);
            if (bitmap == null) {
                bitmap = loadAlbumArtFromActiveNotification();
            }
            if (bitmap == null) {
                bitmap = loadAlbumArtFromMetadata(title, artist);
            }
            final Bitmap result = bitmap;
            mHandler.post(() -> {
                if (generation != mNowPlayingArtGeneration) {
                    return;
                }
                if (result == null || result.isRecycled()) {
                    Log.i(TAG, "Now Playing album art unavailable"
                            + " uri=" + (TextUtils.isEmpty(uriString) ? "null" : "set")
                            + " title=" + title);
                    return;
                }
                mNowPlayingAlbumArt = result;
                Log.i(TAG, "Now Playing album art applied "
                        + result.getWidth() + "x" + result.getHeight());
                updateNowPlayingIndication();
            });
        });
    }

    private void loadNowPlayingArtFromNotifAsync(StatusBarNotification sbn, int generation) {
        mBackgroundExecutor.execute(() -> {
            Bitmap bitmap = extractAlbumArtFromNotif(sbn);
            if (bitmap == null) {
                // ASI ambient notifs almost never ship largeIcon; resolve by title line.
                final CharSequence notifText = extractNowPlayingTextFromNotif(sbn);
                if (!TextUtils.isEmpty(notifText)) {
                    bitmap = loadAlbumArtFromSearchTerm(
                            notifText.toString().replace('•', ' ').trim());
                }
            }
            if (bitmap == null) {
                return;
            }
            final Bitmap result = bitmap;
            mHandler.post(() -> {
                if (generation != mNowPlayingArtGeneration || mNowPlayingAlbumArt != null) {
                    return;
                }
                mNowPlayingAlbumArt = result;
                Log.i(TAG, "Now Playing album art from notification/metadata "
                        + result.getWidth() + "x" + result.getHeight());
                updateNowPlayingIndication();
            });
        });
    }

    @Nullable
    private Bitmap decodeAlbumArtUri(@Nullable String uriString) {
        if (TextUtils.isEmpty(uriString)) {
            return null;
        }
        try {
            final Uri uri = Uri.parse(uriString.trim());
            // HTTP(S) covers (rare in SHOW; used if ASI ever passes a network URL).
            final String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return downloadBitmap(uriString.trim());
            }
            ImageDecoder.Source source =
                    ImageDecoder.createSource(mContext.getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                final int max = Math.round(TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, NOW_PLAYING_ART_SIZE_DP * 3,
                        mContext.getResources().getDisplayMetrics()));
                decoder.setTargetSize(
                        Math.min(info.getSize().getWidth(), max),
                        Math.min(info.getSize().getHeight(), max));
            });
        } catch (Exception e) {
            Log.w(TAG, "Failed to decode ALBUM_ART_URI: " + uriString, e);
            return null;
        }
    }

    /**
     * Fallback when ASI never exports a lockscreen FileProvider cover: resolve artwork
     * by song title + artist via iTunes Search (same metadata NP already has in history).
     */
    @Nullable
    private Bitmap loadAlbumArtFromMetadata(
            @Nullable CharSequence title, @Nullable CharSequence artist) {
        final String t = title != null ? title.toString().trim() : "";
        final String a = artist != null ? artist.toString().trim() : "";
        if (t.isEmpty() && a.isEmpty()) {
            // Fall back to the combined strip text ("Title • Artist") if split extras missing.
            if (!TextUtils.isEmpty(mNowPlayingText)) {
                return loadAlbumArtFromSearchTerm(mNowPlayingText.toString()
                        .replace('•', ' ').trim());
            }
            return null;
        }
        final String term = t.isEmpty() ? a : (a.isEmpty() ? t : (t + " " + a));
        return loadAlbumArtFromSearchTerm(term);
    }

    @Nullable
    private Bitmap loadAlbumArtFromSearchTerm(String term) {
        if (TextUtils.isEmpty(term)) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            final String encoded = URLEncoder.encode(term, StandardCharsets.UTF_8.name());
            final URL url = new URL(String.format(ITUNES_SEARCH_URL, encoded));
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(ART_HTTP_CONNECT_MS);
            conn.setReadTimeout(ART_HTTP_READ_MS);
            conn.setRequestProperty("User-Agent", "AlphaDroid-SystemUI-NowPlaying");
            conn.setInstanceFollowRedirects(true);
            final int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "iTunes search HTTP " + code + " for: " + term);
                return null;
            }
            final StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    json.append(line);
                }
            }
            final JSONObject root = new JSONObject(json.toString());
            final JSONArray results = root.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.i(TAG, "iTunes search: no results for: " + term);
                return null;
            }
            String artUrl = results.getJSONObject(0).optString("artworkUrl100", null);
            if (TextUtils.isEmpty(artUrl)) {
                return null;
            }
            // Prefer a larger asset when Apple serves the 100px placeholder size in the path.
            artUrl = artUrl.replace("100x100bb", "300x300bb").replace("100x100", "300x300");
            final Bitmap bmp = downloadBitmap(artUrl);
            if (bmp != null) {
                Log.i(TAG, "iTunes album art for: " + term);
            }
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "iTunes album art lookup failed for: " + term, e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Nullable
    private static Bitmap downloadBitmap(String urlString) {
        HttpURLConnection conn = null;
        try {
            final URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(ART_HTTP_CONNECT_MS);
            conn.setReadTimeout(ART_HTTP_READ_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "AlphaDroid-SystemUI-NowPlaying");
            final int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return android.graphics.BitmapFactory.decodeStream(in);
            }
        } catch (Exception e) {
            Log.w(TAG, "downloadBitmap failed: " + urlString, e);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Nullable
    private Bitmap loadAlbumArtFromActiveNotification() {
        try {
            android.app.NotificationManager nm =
                    mContext.getSystemService(android.app.NotificationManager.class);
            if (nm == null) {
                return null;
            }
            StatusBarNotification[] notifs = nm.getActiveNotifications();
            if (notifs == null) {
                return null;
            }
            for (StatusBarNotification sbn : notifs) {
                if (sbn != null && isNowPlayingNotification(sbn)) {
                    Bitmap bmp = extractAlbumArtFromNotif(sbn);
                    if (bmp != null) {
                        return bmp;
                    }
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "notif album art scan failed", e);
        }
        return null;
    }

    @Nullable
    private Bitmap extractAlbumArtFromNotif(@Nullable StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) {
            return null;
        }
        final Notification n = sbn.getNotification();
        // Prefer largeIcon (album cover). Small icon is usually the music-note resource.
        Bitmap bmp = iconToBitmap(n.getLargeIcon());
        if (bmp == null && n.extras != null) {
            Object o = n.extras.get(Notification.EXTRA_LARGE_ICON);
            if (o instanceof Bitmap) {
                bmp = (Bitmap) o;
            } else if (o instanceof Icon) {
                bmp = iconToBitmap((Icon) o);
            }
            if (bmp == null) {
                o = n.extras.get(Notification.EXTRA_LARGE_ICON_BIG);
                if (o instanceof Bitmap) {
                    bmp = (Bitmap) o;
                } else if (o instanceof Icon) {
                    bmp = iconToBitmap((Icon) o);
                }
            }
        }
        return bmp;
    }

    @Nullable
    private Bitmap iconToBitmap(@Nullable Icon icon) {
        if (icon == null) {
            return null;
        }
        // Skip package resource icons (music note / app icon) — not album art.
        if (icon.getType() == Icon.TYPE_RESOURCE) {
            return null;
        }
        try {
            Drawable d = icon.loadDrawable(mContext);
            if (d == null) {
                return null;
            }
            int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 128;
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 128;
            w = Math.min(Math.max(w, 1), 256);
            h = Math.min(Math.max(h, 1), 256);
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            d.draw(canvas);
            return bmp;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Square center-crop with rounded corners for the Now Playing pill. */
    private static Bitmap createRoundedBitmap(Bitmap src, int sizePx) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        final float scale = Math.max((float) sizePx / w, (float) sizePx / h);
        final int scaledW = Math.max(1, Math.round(w * scale));
        final int scaledH = Math.max(1, Math.round(h * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);
        final int x = Math.max(0, (scaledW - sizePx) / 2);
        final int y = Math.max(0, (scaledH - sizePx) / 2);
        Bitmap square = Bitmap.createBitmap(scaled, x, y,
                Math.min(sizePx, scaled.getWidth()),
                Math.min(sizePx, scaled.getHeight()));
        if (square.getWidth() != sizePx || square.getHeight() != sizePx) {
            square = Bitmap.createScaledBitmap(square, sizePx, sizePx, true);
        }
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        final float radius = sizePx * 0.2f;
        canvas.drawRoundRect(new RectF(0, 0, sizePx, sizePx), radius, radius, paint);
        return out;
    }

    private void updateNowPlayingIndication() {
        // The pill owns Now Playing on both the lock screen and AOD, so the song never
        // takes turns with charging or owner info in the indication strip.
        if (mRotateTextViewController != null) {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_NOW_PLAYING);
        }
        if (TextUtils.isEmpty(mNowPlayingText) || mNowPlayingPill == null
                || mNowPlayingTextView == null) {
            hideNowPlayingPill();
            return;
        }
        final ColorStateList tint = mDozing
                ? ColorStateList.valueOf(Color.WHITE)
                : getInitialTextColorState();
        mNowPlayingTextView.setText(mNowPlayingText);
        mNowPlayingTextView.setTextColor(tint);
        updateNowPlayingPillArt(tint);
        mNowPlayingPill.setVisibility(View.VISIBLE);
    }

    /** Rounded album art when loaded, else the tinted music note. */
    private void updateNowPlayingPillArt(ColorStateList tint) {
        if (mNowPlayingArtView == null) {
            return;
        }
        if (mNowPlayingAlbumArt != null && !mNowPlayingAlbumArt.isRecycled()) {
            final int sizePx = Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    NOW_PLAYING_ART_SIZE_DP,
                    mContext.getResources().getDisplayMetrics()));
            mNowPlayingArtView.setImageTintList(null);
            mNowPlayingArtView.setImageBitmap(
                    createRoundedBitmap(mNowPlayingAlbumArt, sizePx));
        } else {
            mNowPlayingArtView.setImageTintList(tint);
            mNowPlayingArtView.setImageResource(R.drawable.ic_now_playing_note);
        }
    }

    private void hideNowPlayingPill() {
        if (mNowPlayingPill != null) {
            mNowPlayingPill.setVisibility(View.GONE);
        }
    }

    public String getPowerChargingString() {
        return computePowerChargingStringIndication();
    }

    private void handleAlignStateChanged(int alignState) {
        String alignmentIndication = "";
        if (alignState == DockManager.ALIGN_STATE_POOR) {
            alignmentIndication =
                    mContext.getResources().getString(R.string.dock_alignment_slow_charging);
        } else if (alignState == DockManager.ALIGN_STATE_TERRIBLE) {
            alignmentIndication =
                    mContext.getResources().getString(R.string.dock_alignment_not_charging);
        }
        if (!alignmentIndication.equals(mAlignmentIndication)) {
            mAlignmentIndication = alignmentIndication;
            updateDeviceEntryIndication(false);
        }
    }

    /**
     * Gets the {@link KeyguardUpdateMonitorCallback} instance associated with this
     * {@link KeyguardIndicationController}.
     *
     * <p>Subclasses may override this method to extend or change the callback behavior by extending
     * the {@link BaseKeyguardCallback}.
     *
     * @return A KeyguardUpdateMonitorCallback. Multiple calls to this method <b>must</b> return the
     * same instance.
     */
    protected KeyguardUpdateMonitorCallback getKeyguardCallback() {
        if (mUpdateMonitorCallback == null) {
            mUpdateMonitorCallback = new BaseKeyguardCallback();
        }
        return mUpdateMonitorCallback;
    }

    private void updateLockScreenIndications(boolean animate, int userId) {
        // update transient messages:
        updateBiometricMessage();
        updateTransient();
        updateNowPlayingIndication();

        // Update persistent messages. The following methods should only be called if we're on the
        // lock screen:
        updateForceIsDimissibileChanged();
        updateLockScreenDisclosureMsg();
        updateLockScreenOwnerInfo();
        updateLockScreenBatteryMsg(animate);
        updateLockScreenUserLockedMsg(userId);
        updateLockScreenTrustMsg(userId, getTrustGrantedIndication(), getTrustManagedIndication());
        updateLockScreenAlignmentMsg();
        updateLockScreenLogoutView();
        updateLockScreenPersistentUnlockMsg();
        updateLockScreenAdaptiveAuthMsg(userId);
        if (showLockedByYourWatchKeyguardIndicator()) {
            updateLockScreenWatchDisconnectedMsg(userId);
        }
        if (secureLockDevice()) {
            updateLockScreenSecureLockDeviceMsg();
        }
    }

    private void updateOrganizedOwnedDevice() {
        // avoid calling this method since it has an IPC
        mOrganizationOwnedDevice = whitelistIpcs(this::isOrganizationOwnedDevice);
        updateDeviceEntryIndication(false);
    }

    private void updateForceIsDimissibileChanged() {
        if (mForceIsDismissible) {
            mRotateTextViewController.updateIndication(
                    INDICATION_IS_DISMISSIBLE,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getResources().getString(
                                    com.android.systemui.res.R.string.dismissible_keyguard_swipe)
                            )
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    /* updateImmediately */ true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_IS_DISMISSIBLE);
        }
    }

    private void updateLockScreenDisclosureMsg() {
        if (mOrganizationOwnedDevice) {
            mBackgroundExecutor.execute(() -> {
                final CharSequence organizationName = getOrganizationOwnedDeviceOrganizationName();
                final CharSequence disclosure = getDisclosureText(organizationName);

                mExecutor.execute(() -> {
                    if (mKeyguardStateController.isShowing()) {
                        mRotateTextViewController.updateIndication(
                              INDICATION_TYPE_DISCLOSURE,
                              new KeyguardIndication.Builder()
                                      .setMessage(disclosure)
                                      .setTextColor(getInitialTextColorState())
                                      .build(),
                              /* updateImmediately */ false);
                        notifyIndicationListeners(AX_TYPE_DISCLOSURE, disclosure);
                    }
                });
            });
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_DISCLOSURE);
            notifyIndicationListeners(AX_TYPE_DISCLOSURE, null);
        }
    }

    private CharSequence getDisclosureText(@Nullable CharSequence organizationName) {
        final Resources packageResources = mContext.getResources();

        if (organizationName == null) {
            return mDevicePolicyManager.getResources().getString(
                    KEYGUARD_MANAGEMENT_DISCLOSURE,
                    () -> packageResources.getString(R.string.do_disclosure_generic));
        } else if (mDevicePolicyManager.isFinancedDevice()) {
            return packageResources.getString(R.string.do_financed_disclosure_with_name,
                    organizationName);
        } else {
            return mDevicePolicyManager.getResources().getString(
                    KEYGUARD_NAMED_MANAGEMENT_DISCLOSURE,
                    () -> packageResources.getString(
                            R.string.do_disclosure_with_name, organizationName),
                    organizationName);
        }
    }

    private int getCurrentUser() {
        return mUserTracker.getUserId();
    }

    private void updateLockScreenOwnerInfo() {
        // Check device owner info on a bg thread.
        // It makes multiple IPCs that could block the thread it's run on.
        mBackgroundExecutor.execute(() -> {
            String info = mLockPatternUtils.getDeviceOwnerInfo();
            if (info == null) {
                // Use the current user owner information if enabled.
                final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                        getCurrentUser());
                if (ownerInfoEnabled) {
                    info = mLockPatternUtils.getOwnerInfo(getCurrentUser());
                }
            }

            // Update the UI on the main thread.
            final String finalInfo = info;
            mExecutor.execute(() -> {
                if (!TextUtils.isEmpty(finalInfo) && mKeyguardStateController.isShowing()) {
                    mRotateTextViewController.updateIndication(
                            INDICATION_TYPE_OWNER_INFO,
                            new KeyguardIndication.Builder()
                                    .setMessage(finalInfo)
                                    .setTextColor(getInitialTextColorState())
                                    .build(),
                            false);
                    notifyIndicationListeners(AX_TYPE_OWNER_INFO, finalInfo);
                } else {
                    mRotateTextViewController.hideIndication(INDICATION_TYPE_OWNER_INFO);
                    notifyIndicationListeners(AX_TYPE_OWNER_INFO, null);
                }
            });
        });
    }

    private void updateLockScreenBatteryMsg(boolean animate) {
        if (mBatteryPresent && (mPowerPluggedIn || mEnableBatteryDefender)) {
            String powerIndication = computePowerIndication();
            if (DEBUG_CHARGING_SPEED) {
                powerIndication += ",  " + (mChargingWattage / mCurrentDivider) + " mW";
            }

            mKeyguardLogger.logUpdateBatteryIndication(powerIndication, mPowerPluggedIn);
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_BATTERY,
                    new KeyguardIndication.Builder()
                            .setMessage(powerIndication)
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    animate);
        } else {
            mKeyguardLogger.log(TAG, LogLevel.DEBUG, "hide battery indication");
            // don't show the charging information
            mRotateTextViewController.hideIndication(INDICATION_TYPE_BATTERY);
        }
    }

    private void updateLockScreenUserLockedMsg(int userId) {
        boolean userStorageUnlocked = mKeyguardUpdateMonitor.isUserUnlocked(userId);
        boolean encryptedOrLockdown = mKeyguardUpdateMonitor.isEncryptedOrLockdown(userId);
        mKeyguardLogger.logUpdateLockScreenUserLockedMsg(userId, userStorageUnlocked,
                encryptedOrLockdown);
        if (!userStorageUnlocked || encryptedOrLockdown) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_USER_LOCKED,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getResources().getText(
                                    com.android.internal.R.string.lockscreen_storage_locked))
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_USER_LOCKED);
        }
    }

    private void updateBiometricMessage() {
        if (mDozing) {
            updateDeviceEntryIndication(false);
            return;
        }

        if (!TextUtils.isEmpty(mBiometricMessage)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE,
                    new KeyguardIndication.Builder()
                            .setMessage(mBiometricMessage)
                            .setForceAccessibilityLiveRegionAssertive()
                            .setMinVisibilityMillis(IMPORTANT_MSG_MIN_DURATION)
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    true
            );
            notifyIndicationListeners(AX_TYPE_BIOMETRIC, mBiometricMessage);
        } else {
            mRotateTextViewController.hideIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE);
            notifyIndicationListeners(AX_TYPE_BIOMETRIC, null);
        }
        if (!TextUtils.isEmpty(mBiometricMessageFollowUp)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP,
                    new KeyguardIndication.Builder()
                            .setMessage(mBiometricMessageFollowUp)
                            .setMinVisibilityMillis(IMPORTANT_MSG_MIN_DURATION)
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    true
            );
        } else {
            mRotateTextViewController.hideIndication(
                    INDICATION_TYPE_BIOMETRIC_MESSAGE_FOLLOW_UP);
        }
    }

    private void updateTransient() {
        if (mDozing) {
            updateDeviceEntryIndication(false);
            return;
        }

        if (!TextUtils.isEmpty(mTransientIndication)) {
            mRotateTextViewController.showTransient(mTransientIndication);
            notifyIndicationListeners(AX_TYPE_TRANSIENT, mTransientIndication);
        } else {
            mRotateTextViewController.hideTransient();
            notifyIndicationListeners(AX_TYPE_TRANSIENT, null);
        }
    }

    private void updateLockScreenTrustMsg(int userId, CharSequence trustGrantedIndication,
            CharSequence trustManagedIndication) {
        final boolean userHasTrust = mKeyguardUpdateMonitor.getUserHasTrust(userId);
        if (!TextUtils.isEmpty(trustGrantedIndication) && userHasTrust) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_TRUST,
                    new KeyguardIndication.Builder()
                            .setMessage(trustGrantedIndication)
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    true);
            notifyIndicationListeners(AX_TYPE_TRUST, trustGrantedIndication);
            hideBiometricMessage();
        } else if (!TextUtils.isEmpty(trustManagedIndication)
                && mKeyguardUpdateMonitor.getUserTrustIsManaged(userId)
                && !userHasTrust) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_TRUST,
                    new KeyguardIndication.Builder()
                            .setMessage(trustManagedIndication)
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    false);
            notifyIndicationListeners(AX_TYPE_TRUST, trustManagedIndication);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_TRUST);
            notifyIndicationListeners(AX_TYPE_TRUST, null);
        }
    }

    private void updateLockScreenAlignmentMsg() {
        if (!TextUtils.isEmpty(mAlignmentIndication)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_ALIGNMENT,
                    new KeyguardIndication.Builder()
                            .setMessage(mAlignmentIndication)
                            .setTextColor(ColorStateList.valueOf(
                                    mContext.getColor(R.color.misalignment_text_color)))
                            .build(),
                    true);
            notifyIndicationListeners(AX_TYPE_ALIGNMENT, mAlignmentIndication);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_ALIGNMENT);
            notifyIndicationListeners(AX_TYPE_ALIGNMENT, null);
        }
    }

    private void updateLockScreenPersistentUnlockMsg() {
        if (!TextUtils.isEmpty(mPersistentUnlockMessage)) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE,
                    new KeyguardIndication.Builder()
                            .setMessage(mPersistentUnlockMessage)
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    true);
            notifyIndicationListeners(AX_TYPE_PERSISTENT_UNLOCK, mPersistentUnlockMessage);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_PERSISTENT_UNLOCK_MESSAGE);
            notifyIndicationListeners(AX_TYPE_PERSISTENT_UNLOCK, null);
        }
    }

    private void updateLockScreenLogoutView() {
        if (mUserLogoutInteractor.isLogoutEnabled().getValue()) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_LOGOUT,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getResources().getString(
                                    com.android.internal.R.string.global_action_logout))
                            .setTextColor(Utils.getColorAttr(
                                    mContext, com.android.internal.R.attr.textColorOnAccent))
                            .setBackground(mContext.getDrawable(
                                    com.android.systemui.res.R.drawable.logout_button_background))
                            .setClickListener((view) -> {
                                if (mFalsingManager.isFalseTap(LOW_PENALTY)) {
                                    return;
                                }
                                mUserLogoutInteractor.logOut();
                            })
                            .build(),
                    false);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_LOGOUT);
        }
    }

    private void updateLockScreenAdaptiveAuthMsg(int userId) {
        final boolean deviceLocked = mKeyguardUpdateMonitor.isDeviceLockedByAdaptiveAuth(userId);
        final boolean canSkipBouncer = mKeyguardUpdateMonitor.getUserCanSkipBouncer(userId);
        if (deviceLocked && !canSkipBouncer) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_ADAPTIVE_AUTH,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getString(
                                    R.string.keyguard_indication_after_adaptive_auth_lock))
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_ADAPTIVE_AUTH);
        }
    }
    private void updateLockScreenWatchDisconnectedMsg(int userId) {
        final boolean deviceLocked = mAuthenticationFlags != null
                && mAuthenticationFlags.isSomeAuthRequiredAfterWatchDisconnected();
        final boolean canSkipBouncer = mKeyguardUpdateMonitor.getUserCanSkipBouncer(userId);
        if (deviceLocked && !canSkipBouncer) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_WATCH_DISCONNECTED,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getString(
                                    R.string.keyguard_indication_after_watch_disconnected))
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_WATCH_DISCONNECTED);
        }
    }

    private void updateLockScreenSecureLockDeviceMsg() {
        boolean isSecureLockDeviceEnabled = secureLockDevice()
                && mSecureLockDeviceInteractor.get().isSecureLockDeviceEnabled().getValue();
        if (isSecureLockDeviceEnabled) {
            mRotateTextViewController.updateIndication(
                    INDICATION_TYPE_SECURE_LOCK_DEVICE,
                    new KeyguardIndication.Builder()
                            .setMessage(mContext.getString(
                                    R.string.keyguard_indication_after_secure_lock_device))
                            .setTextColor(getInitialTextColorState())
                            .build(),
                    true);
        } else {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_SECURE_LOCK_DEVICE);
        }
    }

    private boolean isOrganizationOwnedDevice() {
        return mDevicePolicyManager.isDeviceManaged()
                || mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile();
    }

    @Nullable
    private CharSequence getOrganizationOwnedDeviceOrganizationName() {
        if (mDevicePolicyManager.isDeviceManaged()) {
            return mDevicePolicyManager.getDeviceOwnerOrganizationName();
        } else if (mDevicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile()) {
            return getWorkProfileOrganizationName();
        }
        return null;
    }

    private CharSequence getWorkProfileOrganizationName() {
        final int profileId = getWorkProfileUserId(UserHandle.myUserId());
        if (profileId == UserHandle.USER_NULL) {
            return null;
        }
        return mDevicePolicyManager.getOrganizationNameForUser(profileId);
    }

    private int getWorkProfileUserId(int userId) {
        for (final UserInfo userInfo : mUserManager.getProfiles(userId)) {
            if (userInfo.isManagedProfile()) {
                return userInfo.id;
            }
        }
        return UserHandle.USER_NULL;
    }

    public void setSuppressIndication(boolean suppress) {
        mSuppressIndication = suppress;
        if (mTopIndicationView != null) {
            mTopIndicationView.setSuppressVisibility(suppress);
        }
        if (mLockScreenIndicationView != null) {
            mLockScreenIndicationView.setSuppressVisibility(suppress);
        }
        if (!suppress && mVisible) {
            updateDeviceEntryIndication(false);
        }
    }

    /**
     * Sets the visibility of keyguard bottom area, and if the indications are updatable.
     *
     * @param visible true to make the area visible and update the indication, false otherwise.
     */
    public void setVisible(boolean visible) {
        mVisible = visible;
        mIndicationArea.setVisibility(visible ? VISIBLE : GONE);
        updateFastchargeInfoPolling();
        if (visible) {
            // If this is called after an error message was already shown, we should not clear it.
            // Otherwise the error message won't be shown
            if (!mHideTransientMessageHandler.isScheduled()) {
                hideTransientIndication();
            }
            // NP was cleared on unlock. A still-active recognition reappears via a
            // fresh AMBIENT_INDICATION_SHOW (or a SHOW that arrived while unlocked).
            // Do not rehydrate from sticky NP notifications — those outlive HIDE.
            updateDeviceEntryIndication(false);
            updateNowPlayingIndication();
        } else {
            // If we unlock and return to keyguard quickly, previous error should not be shown
            hideTransientIndication();
            clearNowPlayingOnUnlock();
        }
    }

    /**
     * Drop keyguard Now Playing when leaving the lock screen so unlock → relock
     * does not resurrect a song after ASI has stopped recognizing (often no HIDE).
     */
    private void clearNowPlayingOnUnlock() {
        if (TextUtils.isEmpty(mNowPlayingText) && mNowPlayingAlbumArt == null) {
            if (mRotateTextViewController != null) {
                mRotateTextViewController.hideIndication(INDICATION_TYPE_NOW_PLAYING);
            }
            hideNowPlayingPill();
            return;
        }
        mNowPlayingText = null;
        clearNowPlayingAlbumArt();
        if (mRotateTextViewController != null) {
            mRotateTextViewController.hideIndication(INDICATION_TYPE_NOW_PLAYING);
        }
        hideNowPlayingPill();
    }

    private void setPersistentUnlockMessage(String persistentUnlockMessage) {
        mPersistentUnlockMessage = persistentUnlockMessage;
        updateDeviceEntryIndication(false);
    }

    /**
     * Returns the indication text indicating that trust has been granted.
     *
     * @return an empty string if a trust indication text should not be shown.
     */
    @VisibleForTesting
    String getTrustGrantedIndication() {
        return mTrustGrantedIndication == null
                ? mContext.getString(R.string.keyguard_indication_trust_unlocked)
                : mTrustGrantedIndication.toString();
    }

    /**
     * Sets if the device is plugged in
     */
    @VisibleForTesting
    void setPowerPluggedIn(boolean plugged) {
        mPowerPluggedIn = plugged;
    }

    /**
     * Returns the indication text indicating that trust is currently being managed.
     *
     * @return {@code null} or an empty string if a trust managed text should not be shown.
     */
    private String getTrustManagedIndication() {
        return null;
    }

    /**
     * Hides transient indication in {@param delayMs}.
     */
    public void hideTransientIndicationDelayed(long delayMs) {
        mHideTransientMessageHandler.schedule(delayMs, AlarmTimeout.MODE_RESCHEDULE_IF_SCHEDULED);
    }

    /**
     * Hides biometric indication in {@param delayMs}.
     */
    public void hideBiometricMessageDelayed(long delayMs) {
        mHideBiometricMessageHandler.schedule(delayMs, AlarmTimeout.MODE_RESCHEDULE_IF_SCHEDULED);
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    public void showTransientIndication(int transientIndication) {
        showTransientIndication(mContext.getResources().getString(transientIndication));
    }

    /**
     * Shows {@param transientIndication} until it is hidden by {@link #hideTransientIndication}.
     */
    private void showTransientIndication(CharSequence transientIndication) {
        mTransientIndication = transientIndication;
        hideTransientIndicationDelayed(DEFAULT_HIDE_DELAY_MS);

        updateTransient();
    }

    private void showSuccessBiometricMessage(
            CharSequence biometricMessage,
            @Nullable CharSequence biometricMessageFollowUp,
            BiometricSourceType biometricSourceType
    ) {
        showBiometricMessage(biometricMessage, biometricMessageFollowUp, biometricSourceType, true);
    }

    private void showSuccessBiometricMessage(CharSequence biometricMessage,
            BiometricSourceType biometricSourceType) {
        showSuccessBiometricMessage(biometricMessage, null, biometricSourceType);
    }

    private void showBiometricMessage(CharSequence biometricMessage,
            BiometricSourceType biometricSourceType) {
        showBiometricMessage(biometricMessage, null, biometricSourceType, false);
    }

    private void showBiometricMessage(
            CharSequence biometricMessage,
            @Nullable CharSequence biometricMessageFollowUp,
            BiometricSourceType biometricSourceType
    ) {
        showBiometricMessage(
                biometricMessage,
                biometricMessageFollowUp,
                biometricSourceType,
                false
        );
    }

    /**
     * Shows {@param biometricMessage} and {@param biometricMessageFollowUp}
     * until they are hidden by {@link #hideBiometricMessage}. Messages are rotated through
     * by {@link KeyguardIndicationRotateTextViewController}, see class for rotating message
     * logic.
     */
    private void showBiometricMessage(
            CharSequence biometricMessage,
            @Nullable CharSequence biometricMessageFollowUp,
            BiometricSourceType biometricSourceType,
            boolean isSuccessMessage
    ) {
        if (TextUtils.equals(biometricMessage, mBiometricMessage)
                && biometricSourceType == mBiometricMessageSource
                && TextUtils.equals(biometricMessageFollowUp, mBiometricMessageFollowUp)) {
            return;
        }

        if (!isSuccessMessage
                && mBiometricMessageSource == FINGERPRINT
                && biometricSourceType == FACE) {
            // drop any face messages if there's a fingerprint message showing
            mKeyguardLogger.logDropFaceMessage(
                    biometricMessage,
                    biometricMessageFollowUp
            );
            return;
        }

        if (TextUtils.equals(biometricMessage, mContext.getString(R.string.keyguard_face_successful_unlock))) {
            updateFaceIconViewState(FaceUnlockImageView.State.SUCCESS);
        } else if (TextUtils.equals(biometricMessage, mContext.getString(R.string.keyguard_face_failed))) {
            updateFaceIconViewState(FaceUnlockImageView.State.NOT_VERIFIED);
        } else if (TextUtils.equals(biometricMessage, mContext.getString(R.string.face_unlock_recognizing))) {
           updateFaceIconViewState(FaceUnlockImageView.State.SCANNING);
        }

        if (mBiometricMessageSource != null && biometricSourceType == null) {
            // If there's a current biometric message showing and a non-biometric message
            // arrives, update the followup message with the non-biometric message.
            // Keep the biometricMessage and biometricMessageSource the same.
            mBiometricMessageFollowUp = biometricMessage;
        } else {
            mBiometricMessage = biometricMessage;
            mBiometricMessageFollowUp = biometricMessageFollowUp;
            mBiometricMessageSource = biometricSourceType;
        }

        mHandler.removeMessages(MSG_SHOW_ACTION_TO_UNLOCK);
        hideBiometricMessageDelayed(
                !TextUtils.isEmpty(mBiometricMessage)
                        && !TextUtils.isEmpty(mBiometricMessageFollowUp)
                        ? IMPORTANT_MSG_MIN_DURATION * 2
                        : DEFAULT_HIDE_DELAY_MS
        );

        updateBiometricMessage();
    }

    private void hideBiometricMessage() {
        if (mBiometricMessage != null || mBiometricMessageFollowUp != null) {
            mBiometricMessage = null;
            mBiometricMessageFollowUp = null;
            mBiometricMessageSource = null;
            mHideBiometricMessageHandler.cancel();
            updateBiometricMessage();
        }
    }

    private void showFaceUnlockRecognizingMessage() {
        String faceUnlockMessage = mContext.getResources().getString(
            R.string.face_unlock_recognizing);
        showBiometricMessage(faceUnlockMessage, FACE);
    }

    private void hideFaceUnlockRecognizingMessage() {
        if (mFaceIconView != null) {
            mFaceIconView.setVisibility(View.GONE);
        }
        String faceUnlockMessage = mContext.getResources().getString(
            R.string.face_unlock_recognizing);
        if (mBiometricMessage != null && mBiometricMessage.equals(faceUnlockMessage)) {
            mBiometricMessage = null;
            hideBiometricMessage();
            updateFaceIconViewState(FaceUnlockImageView.State.HIDDEN);
        }
    }

    /**
     * Hides transient indication.
     */
    public void hideTransientIndication() {
        if (mTransientIndication != null) {
            mTransientIndication = null;
            mHideTransientMessageHandler.cancel();
            updateTransient();
        }
    }

    /**
     * Updates message shown to the user. If the device is dozing, a single message with the highest
     * precedence is shown. If the device is not dozing (on the lock screen), then several messages
     * may continuously be cycled through.
     */
    protected final void updateDeviceEntryIndication(boolean animate) {
        mKeyguardLogger.logUpdateDeviceEntryIndication(animate, mVisible, mDozing);
        if (!mVisible) {
            return;
        }

        // A few places might need to hide the indication, so always start by making it visible
        mIndicationArea.setVisibility(VISIBLE);

        // Walk down a precedence-ordered list of what indication
        // should be shown based on device state
        if (mDozing) {
            boolean useMisalignmentColor = false;
            mLockScreenIndicationView.setVisibility(View.GONE);
            mTopIndicationView.setVisibility(VISIBLE);
            CharSequence newIndication;
            if (!TextUtils.isEmpty(mBiometricMessage)) {
                newIndication = mBiometricMessage; // note: doesn't show mBiometricMessageFollowUp
            } else if (!TextUtils.isEmpty(mTransientIndication)) {
                newIndication = mTransientIndication;
            } else if (!mBatteryPresent) {
                // If there is no battery detected, hide the indication area and bail
                mIndicationArea.setVisibility(GONE);
                return;
            } else if (!TextUtils.isEmpty(mAlignmentIndication)) {
                useMisalignmentColor = true;
                newIndication = mAlignmentIndication;
            } else if (mBatteryLevel == -1) {
                // If the battery level is not initialized, hide the indication area
                mIndicationArea.setVisibility(GONE);
                return;
            } else if (mPowerPluggedIn || mEnableBatteryDefender) {
                newIndication = computePowerIndication();
            } else {
                newIndication = NumberFormat.getPercentInstance()
                        .format(mBatteryLevel / 100f);
            }

            if (!TextUtils.equals(mTopIndicationView.getText(), newIndication)) {
                mWakeLock.setAcquired(true);
                final KeyguardIndication.Builder builder = new KeyguardIndication.Builder()
                        .setMessage(newIndication)
                        .setTextColor(ColorStateList.valueOf(
                                useMisalignmentColor
                                        ? mContext.getColor(R.color.misalignment_text_color)
                                        : Color.WHITE));
                if (mBiometricMessage != null && newIndication == mBiometricMessage) {
                    builder.setForceAccessibilityLiveRegionAssertive();
                }

                mTopIndicationView.switchIndication(newIndication,
                        builder.build(),
                        animate, () -> mWakeLock.setAcquired(false));
            }
            return;
        }

        // LOCK SCREEN
        mTopIndicationView.setVisibility(GONE);
        mTopIndicationView.setText(null);
        mLockScreenIndicationView.setVisibility(View.VISIBLE);
        updateLockScreenIndications(animate, getCurrentUser());
    }

    /**
     * Assumption: device is charging
     */
    protected String computePowerIndication() {
        if (mBatteryDefender) {
            String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);
            return mContext.getResources().getString(
                    R.string.keyguard_plugged_in_charging_limited, percentage);
        } else if (mPowerPluggedIn && mIncompatibleCharger) {
            String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);
            return mContext.getResources().getString(
                    R.string.keyguard_plugged_in_incompatible_charger, percentage);
        }

        return computePowerChargingStringIndication();
    }

    protected String computePowerChargingStringIndication() {
        if (mPowerCharged) {
            return mContext.getResources().getString(R.string.keyguard_charged);
        }

        String percentage = NumberFormat.getPercentInstance().format(mBatteryLevel / 100f);
        if (mBatteryDead) {
            return mContext.getResources().getString(R.string.keyguard_plugged_in, percentage);
        }

        final boolean hasChargingTime = mChargingTimeRemaining > 0;
        int chargingId;
        if (mPowerPluggedInWired) {
            switch (mChargingSpeed) {
                case BatteryStatus.CHARGING_OEM:
                    if (mHasDashCharger) {
                        chargingId = hasChargingTime
                                ? R.string.keyguard_indication_dash_charging_time
                                : R.string.keyguard_plugged_in_dash_charging;
                    } else if (mHasWarpCharger) {
                        chargingId = hasChargingTime
                                ? R.string.keyguard_indication_warp_charging_time
                                : R.string.keyguard_plugged_in_warp_charging;
                    } else if (mHasVoocCharger) {
                        chargingId = hasChargingTime
                                ? R.string.keyguard_indication_vooc_charging_time
                                : R.string.keyguard_plugged_in_vooc_charging;
                    } else {
                        chargingId = hasChargingTime
                                ? R.string.keyguard_indication_turbo_power_time
                                : R.string.keyguard_plugged_in_turbo_charging;
                    }
                    break;
                case BatteryStatus.CHARGING_FAST:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_fast
                            : R.string.keyguard_plugged_in_charging_fast;
                    break;
                case BatteryStatus.CHARGING_SLOWLY:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time_slowly
                            : R.string.keyguard_plugged_in_charging_slowly;
                    break;
                default:
                    chargingId = hasChargingTime
                            ? R.string.keyguard_indication_charging_time
                            : R.string.keyguard_plugged_in;
                    break;
            }
        } else if (mPowerPluggedInWireless) {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_wireless
                    : R.string.keyguard_plugged_in_wireless;
        } else if (mPowerPluggedInDock) {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time_dock
                    : R.string.keyguard_plugged_in_dock;
        } else {
            chargingId = hasChargingTime
                    ? R.string.keyguard_indication_charging_time
                    : R.string.keyguard_plugged_in;
        }

        String batteryInfo = "";
        boolean showbatteryInfo = Settings.System.getIntForUser(mContext.getContentResolver(),
            Settings.System.LOCKSCREEN_BATTERY_INFO, 1, UserHandle.USER_CURRENT) == 1;
         if (showbatteryInfo) {
            List<String> chargingDetails = new ArrayList<>();
            // mChargingCurrent is in uA and mChargingVoltage in uV (adapter-side values
            // injected by BatteryService). Compute wattage directly from A * V instead of
            // relying on the pre-scaled mChargingWattage, which a single divider can't
            // reconcile with current (linear) and wattage (quadratic) at once.
            float amps = mChargingCurrent / 1000000f;
            float volts = mChargingVoltage / 1000000f;
            float watts = amps * volts;
            if (mChargingCurrent > 0) {
                if (amps >= 1f) {
                    chargingDetails.add(String.format(Locale.US, "%.1f", amps) + "A");
                } else {
                    chargingDetails.add(String.format(Locale.US, "%.0f", amps * 1000f) + "mA");
                }
            }
            if (watts > 0f) {
                chargingDetails.add(String.format(Locale.US, "%.1f", watts) + "W");
            }
            if (mChargingVoltage > 0) {
                chargingDetails.add(String.format(Locale.US, "%.1f", volts) + "V");
            }
            if (mTemperature > 0) {
                chargingDetails.add(String.format(Locale.US, "%.1f",
                        (mTemperature / 10f)) + "°C");
            }
            if (!chargingDetails.isEmpty()) {
                batteryInfo = "\n" + TextUtils.join(" · ", chargingDetails);
            }
        }

        if (hasChargingTime) {
            String chargingTimeFormatted = Formatter.formatShortElapsedTimeRoundingUpToMinutes(
                    mContext, mChargingTimeRemaining);
            String chargingText = mContext.getResources().getString(chargingId, chargingTimeFormatted,
                    percentage);
            return applyOemRatedWatts(chargingText) + batteryInfo;
        } else {
            String chargingText =  mContext.getResources().getString(chargingId, percentage);
            return applyOemRatedWatts(chargingText) + batteryInfo;
        }
    }

    /**
     * Stock-OOS-style headline: turn "VOOC Charging" into e.g. "100W SuperVOOC Charging"
     * when the adapter's rated wattage is known. The real input current is never exposed
     * during VOOC, so like stock this shows the adapter rating as part of the label while
     * the live battery-side details remain on the line below. No-op for other languages
     * or chargers (string untouched when the marker or rating is absent).
     */
    private String applyOemRatedWatts(String chargingText) {
        if (mOemRatedWatts > 0 && chargingText.contains("VOOC Charging")) {
            return chargingText.replaceFirst("VOOC Charging",
                    mOemRatedWatts + "W SuperVOOC Charging");
        }
        return chargingText;
    }

    public void setStatusBarKeyguardViewManager(
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    /**
     * Show message on the keyguard for how the user can unlock/enter their device.
     */
    public void showActionToUnlock() {
        if (mDozing
                && !mKeyguardUpdateMonitor.getUserCanSkipBouncer(
                        getCurrentUser())) {
            return;
        }

        if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
            if (mAlternateBouncerInteractor.isVisibleState()) {
                return; // udfps affordance is highlighted, no need to show action to unlock
            } else if (mKeyguardUpdateMonitor.isFaceEnabledAndEnrolled()
                    && !mKeyguardUpdateMonitor.getIsFaceAuthenticated()) {
                String message;
                if (mAccessibilityManager.isEnabled()
                        || mAccessibilityManager.isTouchExplorationEnabled()) {
                    message = mContext.getString(R.string.accesssibility_keyguard_retry);
                } else {
                    message = mContext.getString(R.string.keyguard_retry);
                }
                mStatusBarKeyguardViewManager.setKeyguardMessage(message,
                        getInitialTextColorState(),
                        null);
            }
        } else {
            final boolean canSkipBouncer = mKeyguardUpdateMonitor.getUserCanSkipBouncer(
                    getCurrentUser());
            if (canSkipBouncer) {
                final boolean faceAuthenticated = mKeyguardUpdateMonitor.getIsFaceAuthenticated();
                final boolean udfpsSupported = mKeyguardUpdateMonitor.isUdfpsSupported();
                final boolean a11yEnabled = mAccessibilityManager.isEnabled()
                        || mAccessibilityManager.isTouchExplorationEnabled();
                if (udfpsSupported && faceAuthenticated) { // co-ex
                    if (a11yEnabled) {
                        showSuccessBiometricMessage(
                                mContext.getString(R.string.keyguard_face_successful_unlock),
                                mContext.getString(R.string.keyguard_unlock),
                                FACE
                        );
                    } else {
                        showSuccessBiometricMessage(
                                mContext.getString(R.string.keyguard_face_successful_unlock),
                                mContext.getString(R.string.keyguard_unlock_press),
                                FACE
                        );
                    }
                } else if (faceAuthenticated) { // face-only
                    showSuccessBiometricMessage(
                            mContext.getString(R.string.keyguard_face_successful_unlock),
                            mContext.getString(R.string.keyguard_unlock),
                            FACE
                    );
                } else if (udfpsSupported) { // udfps-only
                    if (a11yEnabled) {
                        showSuccessBiometricMessage(
                                mContext.getString(R.string.keyguard_unlock),
                                null
                        );
                    } else {
                        showSuccessBiometricMessage(mContext.getString(
                                R.string.keyguard_unlock_press), null);
                    }
                } else { // no security or unlocked by a trust agent
                    showSuccessBiometricMessage(mContext.getString(R.string.keyguard_unlock), null);
                }
            } else {
                // suggest swiping up for the primary authentication bouncer
                showBiometricMessage(mContext.getString(R.string.keyguard_unlock), null);
            }
        }
    }

    public void dump(PrintWriter pw, String[] args) {
        pw.println("KeyguardIndicationController:");
        pw.println("  mInitialTextColorState: " + getInitialTextColorState());
        pw.println("  mPowerPluggedInWired: " + mPowerPluggedInWired);
        pw.println("  mPowerPluggedIn: " + mPowerPluggedIn);
        pw.println("  mPowerCharged: " + mPowerCharged);
        pw.println("  mChargingSpeed: " + mChargingSpeed);
        pw.println("  mChargingWattage: " + mChargingWattage);
        pw.println("  mChargingStatus: " + mChargingStatus);
        pw.println("  mMessageToShowOnScreenOn: " + mBiometricErrorMessageToShowOnScreenOn);
        pw.println("  mDozing: " + mDozing);
        pw.println("  mTransientIndication: " + mTransientIndication);
        pw.println("  mBiometricMessage: " + mBiometricMessage);
        pw.println("  mBiometricMessageFollowUp: " + mBiometricMessageFollowUp);
        pw.println("  mBatteryLevel: " + mBatteryLevel);
        pw.println("  mBatteryPresent: " + mBatteryPresent);
        pw.println("  AOD text: " + (
                mTopIndicationView == null ? null : mTopIndicationView.getText()));
        pw.println("  computePowerIndication(): " + computePowerIndication());
        pw.println("  trustGrantedIndication: " + getTrustGrantedIndication());
        pw.println("    mCoExFaceHelpMsgIdsToShow=" + mCoExFaceAcquisitionMsgIdsToShow);
        mRotateTextViewController.dump(pw, args);
    }

    protected ColorStateList getInitialTextColorState() {
        return mInitialTextColorState;
    }

    private void setIndicationColorToThemeColor() {
        mInitialTextColorState = wallpaperTextColor();
    }

    /**
     * @deprecated Use {@link #setIndicationColorToThemeColor}
     */
    @Deprecated
    private void setIndicationTextColor(ColorStateList color) {
        mInitialTextColorState = color;
    }

    private final Runnable mUpdateInfo = new Runnable() {
        public void run() {
            long now = SystemClock.uptimeMillis();
            long next = now + (1000 - now % 1000);
            try {
                mBatteryPropertiesRegistrar.scheduleUpdate();
            } catch (RemoteException e) {
            }
            if (mHandler != null) {
                mHandler.postAtTime(mUpdateInfo, next);
            }
        }
    };

    /**
     * The 1 Hz {@link #mUpdateInfo} poll exists so the charging wattage reads live while the
     * user is looking at it. Every tick fans an ACTION_BATTERY_CHANGED broadcast out to each
     * registered receiver, so only run it while the indication is on screen and the device is
     * awake -- a whole screen-off charge session would otherwise cost thousands of needless
     * broadcasts. While dozing the text still refreshes on the charger's own battery updates.
     */
    private void updateFastchargeInfoPolling() {
        final boolean shouldPoll = mAlternateFastchargeInfoUpdate
                && mBatteryPropertiesRegistrar != null
                && mPowerPluggedIn && mVisible && !mDozing;
        if (shouldPoll == mFastchargeInfoPolling) {
            return;
        }
        mFastchargeInfoPolling = shouldPoll;
        if (shouldPoll) {
            mUpdateInfo.run();
        } else if (mHandler != null) {
            mHandler.removeCallbacks(mUpdateInfo);
        }
    }

    protected class BaseKeyguardCallback extends KeyguardUpdateMonitorCallback {
        @Override
        public void onTimeChanged() {
            if (mVisible) {
                updateDeviceEntryIndication(false /* animate */);
            }
        }

        /**
         * KeyguardUpdateMonitor only sends "interesting" battery updates
         * {@link KeyguardUpdateMonitor#isBatteryUpdateInteresting}.
         * Therefore, make sure to always check plugged in state along with any charging status
         * change, or else we could end up with stale state.
         */
        @Override
        public void onRefreshBatteryInfo(BatteryStatus status) {
            boolean isChargingOrFull = status.status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status.isCharged();
            boolean wasPluggedIn = mPowerPluggedIn;
            mPowerPluggedInWired = status.isPluggedInWired() && isChargingOrFull;
            mPowerPluggedInWireless = status.isPluggedInWireless() && isChargingOrFull;
            mPowerPluggedInDock = status.isPluggedInDock() && isChargingOrFull;
            mPowerPluggedIn = isPowerPluggedIn(status, isChargingOrFull);
            mPowerCharged = status.isCharged();
            mChargingCurrent = status.maxChargingCurrent;
            mChargingVoltage = status.maxChargingVoltage;
            mChargingWattage = status.maxChargingWattage;
            mChargingSpeed = status.getChargingSpeed(mContext);
            mChargingStatus = status.chargingStatus;
            mBatteryLevel = status.level;
            mBatteryPresent = status.present;
            mTemperature = status.temperature;
            final Intent stickyBattery = mContext.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            mOemRatedWatts = stickyBattery != null
                    ? stickyBattery.getIntExtra(BatteryManager.EXTRA_OEM_CHARGER_WATTS, 0) : 0;
            mBatteryDefender = isBatteryDefender(status);
            mBatteryDead = status.isDead();
            // when the battery is overheated, device doesn't charge so only guard on pluggedIn:
            mEnableBatteryDefender = mBatteryDefender && status.isPluggedIn();
            mIncompatibleCharger = status.incompatibleCharger.orElse(false);
            if (ScrimUtils.get().isKeyguardShowing()) {
                try {
                    if (mPowerPluggedIn) {
                        long now = SystemClock.elapsedRealtime();
                        if (mChargingTimeRemaining < 0 || !wasPluggedIn
                                || now - mLastChargeTimeComputeMs >= 3000) {
                            mChargingTimeRemaining = mBatteryInfo.computeChargeTimeRemaining();
                            mLastChargeTimeComputeMs = now;
                        }
                    } else {
                        mChargingTimeRemaining = -1;
                    }
                } catch (RemoteException e) {
                    mKeyguardLogger.log(TAG, ERROR, "Error calling IBatteryStats", e);
                    mChargingTimeRemaining = -1;
                }
            }
            updateFastchargeInfoPolling();

            mKeyguardLogger.logRefreshBatteryInfo(isChargingOrFull, mPowerPluggedIn, mBatteryLevel,
                    mBatteryDefender);
            if (ScrimUtils.get().isKeyguardShowing()) {
                updateDeviceEntryIndication(!wasPluggedIn && mPowerPluggedInWired);
            }
        }

        @Override
        public void onBiometricAcquired(BiometricSourceType biometricSourceType, int acquireInfo) {
            if (biometricSourceType == FACE) {
                if (acquireInfo == FACE_ACQUIRED_START) {
                    // Let's hide any previous messages when authentication starts, otherwise
                    // multiple auth attempts would overlap.
                    hideBiometricMessage();
                    mBiometricErrorMessageToShowOnScreenOn = null;
                }
                mFaceAcquiredMessageDeferral.processFrame(acquireInfo);
            }
        }

        @Override
        public void onBiometricHelp(int msgId, String helpString,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE) {
                mFaceAcquiredMessageDeferral.updateMessage(msgId, helpString);
                if (mFaceAcquiredMessageDeferral.shouldDefer(msgId)) {
                    return;
                }
            }

            final boolean faceAuthUnavailable = biometricSourceType == FACE
                    && msgId == BIOMETRIC_HELP_FACE_NOT_AVAILABLE;

            if (isPrimaryAuthRequired()
                    && !faceAuthUnavailable) {
                return;
            }

            final boolean faceAuthSoftError = biometricSourceType == FACE
                    && msgId != BIOMETRIC_HELP_FACE_NOT_RECOGNIZED
                    && msgId != BIOMETRIC_HELP_FACE_NOT_AVAILABLE;
            final boolean faceAuthFailed = biometricSourceType == FACE
                    && msgId == BIOMETRIC_HELP_FACE_NOT_RECOGNIZED; // ran through matcher & failed
            if (faceAuthFailed && mFaceLockedOutThisAuthSession) {
                mKeyguardLogger.logBiometricMessage(
                        "skipped showing faceAuthFailed message due to lockout",
                        msgId,
                        helpString);
                return;
            }
            final boolean fpAuthFailed = biometricSourceType == FINGERPRINT
                    && msgId == BIOMETRIC_HELP_FINGERPRINT_NOT_RECOGNIZED; // ran matcher & failed
            final boolean isUnlockWithFingerprintPossible = canUnlockWithFingerprint();
            final boolean isCoExFaceAcquisitionMessage =
                    faceAuthSoftError && isUnlockWithFingerprintPossible;
            if (isCoExFaceAcquisitionMessage && !mCoExFaceAcquisitionMsgIdsToShow.contains(msgId)) {
                mKeyguardLogger.logBiometricMessage(
                        "skipped showing help message due to co-ex logic",
                        msgId,
                        helpString);
            } else if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
                if (biometricSourceType == FINGERPRINT && !fpAuthFailed) {
                    mBouncerMessageInteractor.setFingerprintAcquisitionMessage(helpString);
                } else if (faceAuthSoftError) {
                    mBouncerMessageInteractor.setFaceAcquisitionMessage(helpString);
                }
                mStatusBarKeyguardViewManager.setKeyguardMessage(helpString,
                        getInitialTextColorState(), biometricSourceType);
            } else if (mScreenLifecycle.getScreenState() == SCREEN_ON) {
                if (isCoExFaceAcquisitionMessage && msgId == FACE_ACQUIRED_TOO_DARK) {
                    showBiometricMessage(
                            helpString,
                            mContext.getString(R.string.keyguard_suggest_fingerprint),
                            biometricSourceType
                    );
                } else if (faceAuthFailed && isUnlockWithFingerprintPossible) {
                    showBiometricMessage(
                            mContext.getString(R.string.keyguard_face_failed),
                            mContext.getString(R.string.keyguard_suggest_fingerprint),
                            biometricSourceType
                    );
                } else if (fpAuthFailed
                        && mKeyguardUpdateMonitor.isCurrentUserUnlockedWithFace()) {
                    // face had already previously unlocked the device, so instead of showing a
                    // fingerprint error, tell them they have already unlocked with face auth
                    // and how to enter their device
                    showSuccessBiometricMessage(
                            mContext.getString(R.string.keyguard_face_successful_unlock),
                            mContext.getString(R.string.keyguard_unlock),
                            null
                    );
                } else if (fpAuthFailed
                        && mKeyguardUpdateMonitor.getUserHasTrust(getCurrentUser())) {
                    showSuccessBiometricMessage(
                            getTrustGrantedIndication(),
                            mContext.getString(R.string.keyguard_unlock),
                            null
                    );
                } else if (faceAuthUnavailable) {
                    showBiometricMessage(
                            helpString,
                            isUnlockWithFingerprintPossible
                                    ? mContext.getString(R.string.keyguard_suggest_fingerprint)
                                    : mContext.getString(R.string.keyguard_unlock),
                            biometricSourceType
                    );
                } else {
                    showBiometricMessage(helpString, biometricSourceType);
                }
            } else if (faceAuthFailed) {
                // show action to unlock
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_SHOW_ACTION_TO_UNLOCK),
                        TRANSIENT_BIOMETRIC_ERROR_TIMEOUT);
            } else {
                mBiometricErrorMessageToShowOnScreenOn =
                        new Pair<>(helpString, biometricSourceType);
                mHandler.sendMessageDelayed(
                        mHandler.obtainMessage(MSG_RESET_ERROR_MESSAGE_ON_SCREEN_ON),
                        1000);
            }
        }

        @Override
        public void onBiometricAuthFailed(BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE) {
                mFaceAcquiredMessageDeferral.reset();
            }
        }

        @Override
        public void onLockedOutStateChanged(BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE && !mKeyguardUpdateMonitor.isFaceLockedOut()) {
                mFaceLockedOutThisAuthSession = false;
            } else if (biometricSourceType == FINGERPRINT) {
                setPersistentUnlockMessage(mKeyguardUpdateMonitor.isFingerprintLockedOut()
                        ? mContext.getString(R.string.keyguard_unlock) : "");
            }
        }

        @Override
        public void onBiometricError(int msgId, String errString,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == FACE) {
                onFaceAuthError(msgId, errString);
            } else if (biometricSourceType == FINGERPRINT) {
                onFingerprintAuthError(msgId, errString);
            }
        }

        private void onFaceAuthError(int msgId, String errString) {
            CharSequence deferredFaceMessage = mFaceAcquiredMessageDeferral.getDeferredMessage();
            mFaceAcquiredMessageDeferral.reset();
            if (mIndicationHelper.shouldSuppressErrorMsg(FACE, msgId)) {
                mKeyguardLogger.logBiometricMessage("KIC suppressingFaceError", msgId, errString);
                return;
            }
            if (msgId == FACE_ERROR_TIMEOUT) {
                handleFaceAuthTimeoutError(deferredFaceMessage);
            } else if (mIndicationHelper.isFaceLockoutErrorMsg(msgId)) {
                handleFaceLockoutError(errString);
            } else {
                showErrorMessageNowOrLater(errString, null, FACE);
            }
        }

        private void onFingerprintAuthError(int msgId, String errString) {
            if (mIndicationHelper.shouldSuppressErrorMsg(FINGERPRINT, msgId)) {
                mKeyguardLogger.logBiometricMessage("KIC suppressingFingerprintError",
                        msgId,
                        errString);
            } else {
                showErrorMessageNowOrLater(errString, null, FINGERPRINT);
            }
        }

        @Override
        public void onTrustChanged(int userId) {
            if (!isCurrentUser(userId)) return;
            updateDeviceEntryIndication(false);
        }

        @Override
        public void onForceIsDismissibleChanged(boolean forceIsDismissible) {
            mForceIsDismissible = forceIsDismissible;
            updateDeviceEntryIndication(false);
        }

        @Override
        public void onTrustGrantedForCurrentUser(
                boolean dismissKeyguard,
                boolean newlyUnlocked,
                @NonNull TrustGrantFlags flags,
                @Nullable String message
        ) {
            showTrustGrantedMessage(dismissKeyguard, message);
        }

        @Override
        public void onTrustAgentErrorMessage(CharSequence message) {
            showTrustAgentErrorMessage(message);
        }

        @Override
        public void onBiometricRunningStateChanged(boolean running,
                BiometricSourceType biometricSourceType) {
            if (biometricSourceType == BiometricSourceType.FACE) {
                mFaceDetectionRunning = running;
                if (running) {
                    mHandler.removeMessages(MSG_HIDE_RECOGNIZING_FACE);
                    mHandler.removeMessages(MSG_SHOW_RECOGNIZING_FACE);
                    mHandler.sendEmptyMessageDelayed(MSG_SHOW_RECOGNIZING_FACE, 100);
                } else {
                    mHandler.removeMessages(MSG_SHOW_RECOGNIZING_FACE);
                    mHandler.removeMessages(MSG_HIDE_RECOGNIZING_FACE);
                    mHandler.sendEmptyMessageDelayed(MSG_HIDE_RECOGNIZING_FACE, 100);
                }
            }
        }

        @Override
        public void onBiometricAuthenticated(int userId, BiometricSourceType biometricSourceType,
                boolean isStrongBiometric) {
            super.onBiometricAuthenticated(userId, biometricSourceType, isStrongBiometric);
            hideBiometricMessage();
            if (biometricSourceType == FACE) {
                mFaceAcquiredMessageDeferral.reset();
                if (!mKeyguardBypassController.canBypass()) {
                    showActionToUnlock();
                }
            }
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            if (mVisible) {
                updateDeviceEntryIndication(false);
            }
        }

        @Override
        public void onUserUnlocked() {
            if (mVisible) {
                updateDeviceEntryIndication(false);
            }
        }

        @Override
        public void onRequireUnlockForNfc() {
            showTransientIndication(mContext.getString(R.string.require_unlock_for_nfc));
            hideTransientIndicationDelayed(DEFAULT_HIDE_DELAY_MS);
        }
    }

    private boolean isPrimaryAuthRequired() {
        // Only checking if unlocking with Biometric is allowed (no matter strong or non-strong
        // as long as primary auth, i.e. PIN/pattern/password, is required), so it's ok to
        // pass true for isStrongBiometric to isUnlockingWithBiometricAllowed() to bypass the
        // check of whether non-strong biometric is allowed since strong biometrics can still be
        // used.
        return !mKeyguardUpdateMonitor.isUnlockingWithBiometricAllowed(
                true /* isStrongBiometric */);
    }

    protected boolean isPluggedInAndCharging() {
        return mPowerPluggedIn;
    }

    /** Return true if the device is under the battery defender mode. */
    protected boolean isBatteryDefender(BatteryStatus status) {
        return status.isBatteryDefender();
    }

    /** Return true if the device has power plugged in. */
    protected boolean isPowerPluggedIn(BatteryStatus status, boolean isChargingOrFull) {
        return status.isPluggedIn() && isChargingOrFull;
    }

    private boolean isCurrentUser(int userId) {
        return getCurrentUser() == userId;
    }

    /**
     * Only show trust agent messages after biometrics are no longer active.
     */
    private void showTrustAgentErrorMessage(CharSequence message) {
        if (message == null) {
            mTrustAgentErrorMessage = null;
            return;
        }
        boolean fpEngaged = mDeviceEntryFingerprintAuthInteractor.isEngaged().getValue();
        boolean faceRunning = mDeviceEntryFaceAuthInteractor.isAuthRunning();
        if (fpEngaged || faceRunning) {
            mKeyguardLogger.delayShowingTrustAgentError(message, fpEngaged, faceRunning);
            mTrustAgentErrorMessage = message;
        } else {
            mTrustAgentErrorMessage = null;
            showBiometricMessage(message, null);
        }
    }

    protected void showTrustGrantedMessage(boolean dismissKeyguard, @Nullable String message) {
        mTrustGrantedIndication = message;
        updateDeviceEntryIndication(false);
    }

    private void handleFaceLockoutError(String errString) {
        String followupMessage = faceLockedOutFollowupMessage();
        // Lockout error can happen multiple times in a session because we trigger face auth
        // even when it is locked out so that the user is aware that face unlock would have
        // triggered but didn't because it is locked out.

        // On first lockout we show the error message from FaceManager, which tells the user they
        // had too many unsuccessful attempts.
        if (!mFaceLockedOutThisAuthSession) {
            mFaceLockedOutThisAuthSession = true;
            showErrorMessageNowOrLater(errString, followupMessage, FACE);
        } else if (!mAuthController.isUdfpsFingerDown()) {
            // On subsequent lockouts, we show a more generic locked out message.
            showErrorMessageNowOrLater(
                    mContext.getString(R.string.keyguard_face_unlock_unavailable),
                    followupMessage,
                    FACE);
        }
    }

    private String faceLockedOutFollowupMessage() {
        int followupMsgId = canUnlockWithFingerprint() ? R.string.keyguard_suggest_fingerprint
                : R.string.keyguard_unlock;
        return mContext.getString(followupMsgId);
    }

    private void handleFaceAuthTimeoutError(@Nullable CharSequence deferredFaceMessage) {
        mKeyguardLogger.logBiometricMessage("deferred message after face auth timeout",
                null, String.valueOf(deferredFaceMessage));
        if (canUnlockWithFingerprint()) {
            // Co-ex: show deferred message OR nothing
            // if we're on the lock screen (bouncer isn't showing), show the deferred msg
            if (deferredFaceMessage != null
                    && !mStatusBarKeyguardViewManager.isBouncerShowing()) {
                showBiometricMessage(
                        deferredFaceMessage,
                        mContext.getString(R.string.keyguard_suggest_fingerprint),
                        FACE
                );
            } else {
                // otherwise, don't show any message
                mKeyguardLogger.logBiometricMessage(
                        "skip showing FACE_ERROR_TIMEOUT due to co-ex logic");
            }
        } else if (deferredFaceMessage != null) {
            mBouncerMessageInteractor.setFaceAcquisitionMessage(deferredFaceMessage.toString());
            // Face-only: The face timeout message is not very actionable, let's ask the
            // user to manually retry.
            showBiometricMessage(
                    deferredFaceMessage,
                    mContext.getString(R.string.keyguard_unlock),
                    FACE
            );
        } else {
            // Face-only
            // suggest swiping up to unlock (try face auth again or swipe up to bouncer)
            showActionToUnlock();
        }
    }

    private boolean canUnlockWithFingerprint() {
        return mKeyguardUpdateMonitor.isUnlockWithFingerprintPossible(
                getCurrentUser()) && mKeyguardUpdateMonitor.isUnlockingWithFingerprintAllowed();
    }

    private void showErrorMessageNowOrLater(String errString, @Nullable String followUpMsg,
            BiometricSourceType biometricSourceType) {
        if (mStatusBarKeyguardViewManager.isBouncerShowing()) {
            mStatusBarKeyguardViewManager.setKeyguardMessage(errString, getInitialTextColorState(),
                    biometricSourceType);
        } else if (mScreenLifecycle.getScreenState() == SCREEN_ON) {
            showBiometricMessage(errString, followUpMsg, biometricSourceType);
        } else {
            mBiometricErrorMessageToShowOnScreenOn = new Pair<>(errString, biometricSourceType);
        }
    }

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onDozingChanged(boolean dozing) {
            if (mDozing == dozing) {
                return;
            }
            mDozing = dozing;

            if (mDozing) {
                hideBiometricMessage();
                hideFaceUnlockRecognizingMessage();
            }
            updateFastchargeInfoPolling();
            updateDeviceEntryIndication(false);
            // Retint the pill for the doze palette; it stays up across the transition.
            updateNowPlayingIndication();
        }
    };

    private void updateFaceIconViewState(FaceUnlockImageView.State state) {
        if (mFaceIconView != null) {
            mFaceIconView.setState(state);
        }
    }

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onUnlockedChanged() {
            mTrustAgentErrorMessage = null;
            updateDeviceEntryIndication(false);
        }

        @Override
        public void onKeyguardShowingChanged() {
            // All transient messages are gone the next time keyguard is shown
            if (!mKeyguardStateController.isShowing()) {
                mKeyguardLogger.log(TAG, LogLevel.DEBUG, "clear messages");
                mTopIndicationView.clearMessages();
                mRotateTextViewController.clearMessages();
                mTrustAgentErrorMessage = null;
            } else {
                updateDeviceEntryIndication(false);
            }
        }
    };
}
