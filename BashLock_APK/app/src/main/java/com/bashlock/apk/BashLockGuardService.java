package com.bashlock.apk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.annotation.SuppressLint;
import java.lang.reflect.Method;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BashLockGuardService:
 * 24/7 Persistent Foreground Service that runs in the background.
 *
 * Responsibilities:
 * 1. Checks device lock status every 10 seconds via server REST API and offline due date.
 * 2. As soon as status is 'LOCKED', immediately brings MainActivity to the front
 *    over any active app (YouTube, WhatsApp, Games, etc.) and engages Kiosk Lock.
 * 3. Survives phone reboot and app kill.
 */
public class BashLockGuardService extends Service {
    private static final String TAG = "BashLock_GuardService";
    private static final String SERVICE_CHANNEL_ID = "bashlock_guard_service_channel";
    private static final String LOCK_CHANNEL_ID = "bashlock_urgent_lock_channel_v2";
    private static final int NOTIFICATION_ID = 1001;
    private static final int LOCK_POPUP_NOTIFICATION_ID = 1002;

    private SharedPreferences mPrefs;
    private ScheduledExecutorService mScheduler;
    private PowerManager.WakeLock mWakeLock;
    private View mGuardLockOverlayView;
    private WindowManager mWindowManager;
    private final StringBuilder mOverlayEnteredPin = new StringBuilder();
    private static volatile String sLastWorkingUrl = null;
    public static final String LAN_BASE_URL = "http://10.186.85.239:5000";
    public static final String TUNNEL_BASE_URL = "https://interim-simpson-encryption-floral.trycloudflare.com";
    private static volatile String sWorkingUrl = null;
    private static volatile String sLastStatusJson = "{}";
    private static volatile String sLastAckedCommand = "";
    private final java.util.concurrent.ExecutorService mParallelExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.concurrent.atomic.AtomicBoolean mIsPollingInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
    private int mUrlFailures = 0;

    public static String getLastWorkingUrl() {
        if (sWorkingUrl != null && !sWorkingUrl.isEmpty()) {
            return sWorkingUrl;
        }
        if (sLastWorkingUrl != null && !sLastWorkingUrl.isEmpty()) {
            return sLastWorkingUrl;
        }
        return LAN_BASE_URL;
    }

    public static String getLastStatusJson() {
        return sLastStatusJson;
    }

    private boolean isUserUnlocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) getSystemService(Context.USER_SERVICE);
            return um != null && um.isUserUnlocked();
        }
        return true;
    }

    private synchronized void initCredentialPrefs() {
        if (mPrefs != null) return;
        if (isUserUnlocked()) {
            try {
                mPrefs = getSharedPreferences("BashLock_Config", MODE_PRIVATE);
                if (sLastWorkingUrl == null || sLastWorkingUrl.isEmpty()) {
                    sLastWorkingUrl = mPrefs.getString("last_working_url", "");
                }
            } catch (Exception ignored) {}
        }
    }

    private final BroadcastReceiver mUserUnlockedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                Log.i(TAG, "Device unlocked by user! Initializing credential encrypted storage...");
                initCredentialPrefs();
                try {
                    context.unregisterReceiver(this);
                } catch (Exception ignored) {}
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        if (isUserUnlocked()) {
            initCredentialPrefs();
        } else {
            try {
                IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
                registerReceiver(mUserUnlockedReceiver, filter);
            } catch (Exception ignored) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                SharedPreferences directPrefs = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE);
                if (sLastWorkingUrl == null || sLastWorkingUrl.isEmpty()) {
                    sLastWorkingUrl = directPrefs.getString("last_working_url", "");
                }
            } catch (Exception ignored) {}
        }

        createNotificationChannel();
        Notification foregroundNotif = buildForegroundNotification("BashLock Protection Active", "Securing device in background");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, foregroundNotif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE | android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, foregroundNotif);
        }

        acquireWakeLock();

        boolean isReleased = false;
        if (mPrefs != null) {
            isReleased = mPrefs.getBoolean("is_released", false);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                isReleased = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE).getBoolean("is_released", false);
            } catch (Exception ignored) {}
        }
        if (!isReleased) {
            BashLockDeviceAdminReceiver.configureDeviceOwnerPolicies(this);
        }

        // Requirement 1 & 4: Shared Lock State Sync & Auto-Lock on Boot Before Internet Connects
        boolean isLocked = false;
        String setupCode = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                SharedPreferences directPrefs = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE);
                if (directPrefs.contains("is_locked")) {
                    isLocked = directPrefs.getBoolean("is_locked", false);
                }
                setupCode = directPrefs.getString("setup_code", directPrefs.getString("device_code", "")).trim();
            } catch (Exception ignored) {}
        }

        if (!isLocked && mPrefs != null) {
            isLocked = mPrefs.getBoolean("is_locked", false);
        }
        if (setupCode.isEmpty() && mPrefs != null) {
            setupCode = mPrefs.getString("setup_code", mPrefs.getString("device_code", "")).trim();
        }
        if ("4819".equals(setupCode)) {
            setupCode = "";
        }

        // Commit both contexts synchronously (commit() not apply())
        syncLockState(isLocked);

        // Fix 2: Auto-Lock on Boot Before Internet Connects (Within 100ms of boot)
        if (isLocked) {
            Log.w(TAG, "BashLockGuardService started with active lock state! Enforcing Native Lock Overlay immediately on boot.");
            showGuardLockOverlay();
            triggerImmediateLockPopup();
        }

        startStatusPollingScheduler();
        Log.i(TAG, "BashLockGuardService created and started in foreground (isLocked: " + isLocked + ")");
    }

    // Requirement 1: Shared Lock State Sync using commit()
    public void syncLockState(boolean isLocked) {
        initCredentialPrefs();
        if (mPrefs != null) {
            try {
                mPrefs.edit().putBoolean("is_locked", isLocked).commit();
            } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                        .edit().putBoolean("is_locked", isLocked).commit();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Fix 2: Check is_locked immediately on startCommand before any network call
        initCredentialPrefs();
        boolean isLockedBoot = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                isLockedBoot = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                        .getBoolean("is_locked", false);
            } catch (Exception ignored) {}
        }
        if (!isLockedBoot && mPrefs != null) {
            isLockedBoot = mPrefs.getBoolean("is_locked", false);
        }
        if (isLockedBoot) {
            showGuardLockOverlay();
            triggerImmediateLockPopup();
        }

        if (intent != null) {
            if (intent.getBooleanExtra("RESTART_FAST_POLL", false)) {
                if (mScheduler != null && !mScheduler.isShutdown()) {
                    try {
                        mScheduler.shutdownNow();
                    } catch (Exception ignored) {}
                    mScheduler = null;
                }
                startStatusPollingScheduler();
            }
            if (intent.getBooleanExtra("CHECK_NOW", false)) {
                Executors.newSingleThreadExecutor().execute(this::checkLockStatus);
            }
            if (intent.getBooleanExtra("FORCE_GUARD_OVERLAY", false)) {
                showGuardLockOverlay();
                triggerImmediateLockPopup();
            }
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            // 1. Silent channel for permanent 24/7 background guard notification
            NotificationChannel serviceChannel = new NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "BashLock 24/7 Security Guard",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Maintains continuous background device security");
            serviceChannel.setShowBadge(false);
            nm.createNotificationChannel(serviceChannel);

            // 2. High-priority channel for urgent compliance & lock screen popups
            NotificationChannel lockChannel = new NotificationChannel(
                    LOCK_CHANNEL_ID,
                    "BashLock Kiosk Lock Alerts",
                    NotificationManager.IMPORTANCE_HIGH
            );
            lockChannel.setDescription("Urgent lock pop-up and installment compliance screen");
            lockChannel.setShowBadge(true);
            lockChannel.setBypassDnd(true);
            lockChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(lockChannel);
        }
    }

    private Notification buildForegroundNotification(String title, String message) {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, flags);

        return new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (mWakeLock == null || !mWakeLock.isHeld())) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BashLock::GuardServiceWakeLock");
                mWakeLock.acquire(10 * 60 * 1000L /* 10 minutes chunk, auto-renewed */);
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock acquisition warning: " + e.getMessage());
        }
    }

    // Requirement 1 & 4: Helper to verify if device is activated by shopkeeper
    private boolean isDeviceActivated() {
        boolean activated = false;
        String setupCode = "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                SharedPreferences directPrefs = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE);
                activated = directPrefs.getBoolean("is_activated", false);
                setupCode = directPrefs.getString("setup_code", directPrefs.getString("device_code", "")).trim();
            } catch (Exception ignored) {}
        }
        if (!activated && mPrefs != null) {
            activated = mPrefs.getBoolean("is_activated", false);
        }
        if (setupCode.isEmpty() && mPrefs != null) {
            setupCode = mPrefs.getString("setup_code", mPrefs.getString("device_code", "")).trim();
        }
        return activated && !setupCode.isEmpty() && !"4819".equals(setupCode);
    }

    // Requirement 2: Instant 2-Second Native Background Polling (No Need to Open App)
    private void startStatusPollingScheduler() {
        if (mScheduler != null && !mScheduler.isShutdown()) {
            return;
        }

        mScheduler = Executors.newSingleThreadScheduledExecutor();
        // Fix 3: Sub-second 800ms Native Parallel Polling
        mScheduler.scheduleWithFixedDelay(this::checkLockStatusSafely, 0, 800, TimeUnit.MILLISECONDS);
    }

    private void checkLockStatusSafely() {
        if (!mIsPollingInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            // Requirement 1: While turning on permissions, NEVER show lock overlay or block Settings if not activated!
            if (!isDeviceActivated()) {
                return;
            }

            initCredentialPrefs();
            boolean isReleased = false;
            if (mPrefs != null) {
                isReleased = mPrefs.getBoolean("is_released", false);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context directBootContext = createDeviceProtectedStorageContext();
                    isReleased = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE).getBoolean("is_released", false);
                } catch (Exception ignored) {}
            }

            if (!isReleased) {
                BashLockDeviceAdminReceiver.configureDeviceOwnerPolicies(this);
            }

            // Continuous Offline Lock Enforcement (Requirement 4):
            // Check lock state across DirectBoot and standard prefs
            boolean isLocked = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context directBootContext = createDeviceProtectedStorageContext();
                    isLocked = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                            .getBoolean("is_locked", false);
                } catch (Exception ignored) {}
            }
            if (!isLocked && mPrefs != null) {
                isLocked = mPrefs.getBoolean("is_locked", false);
            }
            if (isLocked) {
                showGuardLockOverlay();
            }

            checkLockStatus();
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error during background status check: " + t.getMessage());
        } finally {
            mIsPollingInProgress.set(false);
        }
    }

    /**
     * Requirement 2: Polls server every 2 seconds via native HttpURLConnection.
     * Instant Lock/Unlock response without opening the app.
     */
    private void checkLockStatus() {
        if (!isDeviceActivated()) {
            return;
        }

        initCredentialPrefs();
        String setupCode = "";
        if (mPrefs != null) {
            setupCode = mPrefs.getString("setup_code", "").trim();
            if (setupCode.isEmpty()) {
                setupCode = mPrefs.getString("device_code", "").trim();
            }
        }
        if (setupCode.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                setupCode = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                        .getString("setup_code", "").trim();
                if (setupCode.isEmpty()) {
                    setupCode = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                            .getString("device_code", "").trim();
                }
            } catch (Exception ignored) {}
        }
        if (setupCode.isEmpty() || "4819".equals(setupCode)) {
            return;
        }

        JSONObject json = null;
        String winningBaseUrl = null;

        // 1. If we have a sticky working URL, poll it directly with strict 1000ms timeout
        if (sWorkingUrl != null && !sWorkingUrl.isEmpty()) {
            json = fetchStatusHttp(sWorkingUrl, setupCode);
            if (json != null) {
                winningBaseUrl = sWorkingUrl;
            } else {
                sWorkingUrl = null; // Cleared on failure so parallel race triggers immediately!
            }
        }

        // 2. Parallel Race: Race LAN IP and Cloudflare Tunnel concurrently if no sticky URL
        if (json == null) {
            final String sc = setupCode;
            java.util.List<java.util.concurrent.Callable<android.util.Pair<String, JSONObject>>> tasks = new java.util.ArrayList<>();
            tasks.add(() -> {
                JSONObject r = fetchStatusHttp(LAN_BASE_URL, sc);
                if (r != null) return new android.util.Pair<>(LAN_BASE_URL, r);
                throw new Exception("LAN offline");
            });
            tasks.add(() -> {
                JSONObject r = fetchStatusHttp(TUNNEL_BASE_URL, sc);
                if (r != null) return new android.util.Pair<>(TUNNEL_BASE_URL, r);
                throw new Exception("Tunnel offline");
            });

            try {
                android.util.Pair<String, JSONObject> winner = mParallelExecutor.invokeAny(tasks, 1200, TimeUnit.MILLISECONDS);
                if (winner != null && winner.second != null) {
                    winningBaseUrl = winner.first;
                    json = winner.second;
                    sWorkingUrl = winningBaseUrl;
                    sLastWorkingUrl = winningBaseUrl;
                    if (mPrefs != null) {
                        try {
                            mPrefs.edit().putString("last_working_url", winningBaseUrl).commit();
                        } catch (Exception ignored) {}
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        try {
                            createDeviceProtectedStorageContext()
                                    .getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                                    .edit().putString("last_working_url", winningBaseUrl).commit();
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                mUrlFailures++;
                if (mUrlFailures % 35 == 0) {
                    new Thread(this::refreshLiveUrlFromGitHub).start();
                }
                return;
            }
        }

        if (json == null) return;

        sLastWorkingUrl = winningBaseUrl;
        sWorkingUrl = winningBaseUrl;
        mUrlFailures = 0;

        // Broadcast raw status JSON to MainActivity -> WebView for instant DOM update without redundant fetch
        sLastStatusJson = json.toString();
        Intent statusBroadcast = new Intent("com.bashlock.ACTION_STATUS_DATA");
        statusBroadcast.putExtra("status_json", sLastStatusJson);
        statusBroadcast.setPackage(getPackageName());
        sendBroadcast(statusBroadcast);

        if (json.optString("status", "").equals("NOT_FOUND")) {
            Log.i(TAG, "Device deleted (404) -> resetting pairing and unlocking");
            syncLockState(false);
            notifyUnlockState();
            resetPairingPreferences();
            return;
        }

        if (json.optBoolean("success", false)) {
            boolean rootIsLocked = json.optBoolean("is_locked", false);
            String rootStatus = json.optString("status", "");
            String rootCommand = json.optString("remote_command", "");

            JSONObject device = json.optJSONObject("device");
            String status = device != null ? device.optString("status", rootStatus) : rootStatus;
            boolean isLocked = device != null ? device.optBoolean("is_locked", rootIsLocked) : rootIsLocked;
            String remoteCommand = device != null ? device.optString("remote_command", rootCommand) : rootCommand;

            String dueDate = device != null ? device.optString("next_due_date", "") : "";
            String pin = device != null ? device.optString("offline_pin", "") : "";

            if (mPrefs != null) {
                try {
                    SharedPreferences.Editor editor = mPrefs.edit();
                    if (!dueDate.isEmpty()) editor.putString("due_date", dueDate);
                    if (!pin.isEmpty()) editor.putString("bashlock_offline_pin", pin);
                    editor.apply();
                } catch (Exception ignored) {}
            }

            String effectiveUrl = sWorkingUrl != null ? sWorkingUrl : (sLastWorkingUrl != null ? sLastWorkingUrl : LAN_BASE_URL);

            if (!remoteCommand.isEmpty() && !remoteCommand.equalsIgnoreCase(sLastAckedCommand)) {
                sLastAckedCommand = remoteCommand;
                acknowledgeCommandAsync(effectiveUrl, setupCode, remoteCommand, "APPLIED");
            }

            if ("WAKE_DEVICE".equalsIgnoreCase(remoteCommand)
                    || "OPEN_APP".equalsIgnoreCase(remoteCommand)
                    || "WAKE".equalsIgnoreCase(status)) {
                wakeUpDeviceAndOpenApp();
            }

            if ("RELEASE_AND_UNINSTALL".equalsIgnoreCase(status)
                    || "RELEASED".equalsIgnoreCase(status)
                    || "UNENROLLED".equalsIgnoreCase(status)) {
                Log.i(TAG, "Server returned status: " + status + " -> COMMENCING FULL REMOTE RELEASE");
                syncLockState(false);
                if (mPrefs != null) {
                    try {
                        mPrefs.edit().putBoolean("is_released", true).putBoolean("is_locked", false).commit();
                    } catch (Exception ignored) {}
                }
                triggerRemoteReleaseSequence(false);
                return;
            } else if ("LOCKED".equalsIgnoreCase(status) || "LOCK".equalsIgnoreCase(remoteCommand) || isLocked) {
                if (mPrefs != null) {
                    try {
                        mPrefs.edit().putBoolean("is_released", false).putBoolean("is_locked", true).commit();
                    } catch (Exception ignored) {}
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        createDeviceProtectedStorageContext()
                                .getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                                .edit().putBoolean("is_released", false).putBoolean("is_locked", true).commit();
                    } catch (Exception ignored) {}
                }
                syncLockState(true);
                showGuardLockOverlay();
            } else {
                // UNLOCKED
                boolean wasLocked = false;
                if (mPrefs != null) {
                    try {
                        wasLocked = mPrefs.getBoolean("is_locked", false);
                        mPrefs.edit().putBoolean("is_released", false).putBoolean("is_locked", false).commit();
                    } catch (Exception ignored) {}
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        createDeviceProtectedStorageContext()
                                .getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                                .edit().putBoolean("is_released", false).putBoolean("is_locked", false).commit();
                    } catch (Exception ignored) {}
                }
                syncLockState(false);
                if (wasLocked || mGuardLockOverlayView != null) {
                    Log.i(TAG, "Server returned UNLOCKED and device was locked -> Immediately removing WindowManager overlay");
                    notifyUnlockState();
                }
            }
        }
    }

    private void acknowledgeCommandAsync(String baseUrl, String setupCode, String command, String status) {
        if (baseUrl == null || baseUrl.isEmpty() || setupCode == null || setupCode.isEmpty() || command == null || command.isEmpty()) {
            return;
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String endpoint = baseUrl.replaceAll("/lock-app.*", "") + "/api/device/ack-command";
                URL url = new URL(endpoint);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("User-Agent", "BashLock-Background-Guard");

                JSONObject body = new JSONObject();
                body.put("setup_code", setupCode);
                body.put("command", command);
                body.put("status", status);
                body.put("timestamp", System.currentTimeMillis());

                byte[] outputBytes = body.toString().getBytes("UTF-8");
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    os.write(outputBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "Command ack for " + command + " returned HTTP " + code);
            } catch (Exception e) {
                Log.w(TAG, "Command ack failed for " + command + ": " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private JSONObject fetchStatusHttp(String baseUrl, String setupCode) {
        HttpURLConnection conn = null;
        try {
            String endpoint = baseUrl.replaceAll("/lock-app.*", "") + "/api/device/by-code/" + setupCode + "/status?t=" + System.currentTimeMillis();
            URL url = new URL(endpoint);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "BashLock-Background-Guard");
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("Connection", "close");

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return new JSONObject(sb.toString());
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                JSONObject notFound = new JSONObject();
                notFound.put("success", false);
                notFound.put("status", "NOT_FOUND");
                return notFound;
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private void resetPairingPreferences() {
        try {
            initCredentialPrefs();
            if (mPrefs != null) {
                mPrefs.edit()
                        .putBoolean("is_released", false)
                        .putBoolean("is_locked", false)
                        .putBoolean("is_activated", false)
                        .putString("device_code", "")
                        .putString("setup_code", "")
                        .putString("pairing_code", "")
                        .remove("device_id")
                        .remove("bashlock_offline_pin")
                        .remove("offline_pin")
                        .remove("due_date")
                        .commit();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context directBootContext = createDeviceProtectedStorageContext();
                    directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                            .edit()
                            .putBoolean("is_released", false)
                            .putBoolean("is_locked", false)
                            .putBoolean("is_activated", false)
                            .putString("device_code", "")
                            .putString("setup_code", "")
                            .putString("pairing_code", "")
                            .commit();
                } catch (Exception ignored) {}
            }

            Intent resetIntent = new Intent(this, MainActivity.class);
            resetIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            resetIntent.putExtra("FORCE_SETUP_VIEW", true);
            startActivity(resetIntent);
        } catch (Exception e) {
            Log.e(TAG, "Error in resetPairingPreferences: " + e.getMessage());
        }
    }

    private String resolveLiveServerUrl() {
        if (sLastWorkingUrl != null && !sLastWorkingUrl.isEmpty() && mUrlFailures < 2) {
            return sLastWorkingUrl;
        }

        // Check DirectBoot and standard preferences for last_working_url
        if (mUrlFailures < 2) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    String directWorking = createDeviceProtectedStorageContext()
                            .getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                            .getString("last_working_url", "");
                    if (!directWorking.isEmpty()) {
                        sLastWorkingUrl = directWorking;
                        return directWorking;
                    }
                } catch (Exception ignored) {}
            }
            initCredentialPrefs();
            String cachedWorking = (mPrefs != null) ? mPrefs.getString("last_working_url", "").trim() : "";
            if (!cachedWorking.isEmpty()) {
                sLastWorkingUrl = cachedWorking;
                return cachedWorking;
            }
        }

        // If failures >= 2, alternate between LAN IP and Cloudflare Tunnel
        if (mUrlFailures >= 2 && (mUrlFailures % 2 == 0)) {
            return MainActivity.DEFAULT_LAN_HTTP_URL.replaceAll("/lock-app.*", "");
        }

        initCredentialPrefs();
        String url = (mPrefs != null) ? mPrefs.getString("cached_live_url", "").trim() : "";
        if ((url.startsWith("https://") || url.startsWith("http://")) && !url.contains("127.0.0.1") && !url.contains("localhost")) {
            return url.replaceAll("/lock-app.*", "");
        }
        return MainActivity.DEFAULT_PUBLIC_HTTPS_URL.replaceAll("/lock-app.*", "");
    }

    private void refreshLiveUrlFromGitHub() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(MainActivity.GITHUB_RAW_LIVE_URL + "?t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "BashLock-Background-Guard");

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line = reader.readLine();
                reader.close();

                if (line != null) {
                    String fetched = line.trim();
                    if ((fetched.startsWith("https://") || fetched.startsWith("http://")) && !fetched.contains("127.0.0.1")) {
                        if (!fetched.endsWith("/lock-app")) {
                            fetched = fetched + "/lock-app";
                        }
                        initCredentialPrefs();
                        if (mPrefs != null) {
                            mPrefs.edit().putString("cached_live_url", fetched).apply();
                        }
                        Log.i(TAG, "GuardService refreshed live URL from GitHub: " + fetched);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "GitHub live_url fetch error: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Brings MainActivity to the front immediately regardless of what app the user is using.
     */
    private void triggerImmediateLockPopup() {
        // Requirement 1: Synchronous commit() to both preferences
        syncLockState(true);

        // 1. Wake device screen up if turned off
        wakeUpScreen();

        // 2. Display System Overlay Guard immediately over all apps (SYSTEM_ALERT_WINDOW)
        showGuardLockOverlay();

        // 3. Prepare high-priority Intent & FullScreen PendingIntent to pop MainActivity
        Intent lockIntent = new Intent(this, MainActivity.class);
        lockIntent.setAction("com.bashlock.apk.FORCE_LOCK");
        lockIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        lockIntent.putExtra("FORCE_KIOSK_LOCK", true);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(this, 100, lockIntent, flags);

        // A. Direct Intent Execution
        try {
            startActivity(lockIntent);
        } catch (Exception e) {
            Log.w(TAG, "Direct startActivity warning: " + e.getMessage());
        }

        // B. Elevated PendingIntent Send (bypasses background activity restrictions)
        try {
            fullScreenPendingIntent.send();
        } catch (Exception ignored) {}

        // C. High-Priority FullScreen Notification on IMPORTANCE_HIGH channel
        Notification highPriorityNotification = new NotificationCompat.Builder(this, LOCK_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle("⚠️ DEVICE IS LOCKED")
                .setContentText("Installment overdue. Enter Master PIN to unlock.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(false)
                .setOngoing(true)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(LOCK_POPUP_NOTIFICATION_ID, highPriorityNotification);
        }
    }

    private void notifyUnlockState() {
        syncLockState(false);

        // 1. Immediately remove WindowManager lock overlay on main UI thread
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (mGuardLockOverlayView != null && mWindowManager != null) {
                try {
                    mWindowManager.removeViewImmediate(mGuardLockOverlayView);
                    Log.i(TAG, "Guard overlay removed via removeViewImmediate");
                } catch (Exception e) {
                    try {
                        mWindowManager.removeView(mGuardLockOverlayView);
                    } catch (Exception ignored) {}
                }
                mGuardLockOverlayView = null;
            }
        });

        // 2. Broadcast com.bashlock.ACTION_STATE_CHANGED with is_locked = false
        Intent broadcast = new Intent("com.bashlock.ACTION_STATE_CHANGED");
        broadcast.putExtra("is_locked", false);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(LOCK_POPUP_NOTIFICATION_ID);
        }
    }

    private void wakeUpScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                PowerManager.WakeLock screenLock = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "BashLock::ScreenWakeUpLock"
                );
                screenLock.acquire(3000L);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Wakes up the screen and immediately brings MainActivity to the foreground
     * when the shopkeeper sends a remote "Wake Device" or "Open App" command.
     */
    private void wakeUpDeviceAndOpenApp() {
        Log.i(TAG, "Executing Remote Wake / Open App command from shopkeeper dashboard...");
        wakeUpScreen();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction("com.bashlock.apk.OPEN_APP");
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Direct startActivity in wakeUpDeviceAndOpenApp warning: " + e.getMessage());
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(this, 101, intent, flags);
        try {
            fullScreenPendingIntent.send();
        } catch (Exception ignored) {}

        Notification wakeNotification = new NotificationCompat.Builder(this, LOCK_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle("BashLock Security")
                .setContentText("Opening device dashboard...")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setAutoCancel(true)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(1003, wakeNotification);
        }
    }

    /**
     * Attaches an un-cancelable SYSTEM_ALERT_WINDOW full-screen kiosk lock view with direct touch PIN pad.
     */
    private void showGuardLockOverlay() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted yet");
                return;
            }
            if (!isDeviceActivated()) {
                return; // NEVER show lock overlay before pairing/activation!
            }
            if (mGuardLockOverlayView != null) {
                return; // Already actively covering screen
            }

            try {
                mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (mWindowManager == null) return;

                int layoutType;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                } else {
                    layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
                }

                int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        layoutType,
                        flags,
                        PixelFormat.TRANSLUCENT
                );
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                params.gravity = Gravity.FILL;

                // Root un-cancelable container
                FrameLayout overlay = new FrameLayout(this);
                overlay.setBackgroundColor(Color.parseColor("#FB05070C")); // Near solid dark background
                overlay.setFocusable(true);
                overlay.setFocusableInTouchMode(true);
                overlay.requestFocus();
                overlay.setOnKeyListener((v, keyCode, event) -> {
                    if (keyCode == KeyEvent.KEYCODE_BACK
                            || keyCode == KeyEvent.KEYCODE_HOME
                            || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                            || keyCode == KeyEvent.KEYCODE_MENU
                            || keyCode == KeyEvent.KEYCODE_POWER
                            || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                            || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        return true;
                    }
                    return false;
                });
                overlay.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    overlay.setOnApplyWindowInsetsListener((v, insets) -> {
                        android.view.WindowInsetsController controller = overlay.getWindowInsetsController();
                        if (controller != null) {
                            controller.hide(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars());
                            controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                        }
                        return insets;
                    });
                }

                ScrollView scrollView = new ScrollView(this);
                scrollView.setFillViewport(true);

                LinearLayout content = new LinearLayout(this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setGravity(Gravity.CENTER_HORIZONTAL);
                int pad = dpToPx(20);
                content.setPadding(pad, pad * 2, pad, pad);

                // 1. Icon badge
                TextView iconBadge = new TextView(this);
                iconBadge.setText("🔒");
                iconBadge.setTextSize(40);
                iconBadge.setGravity(Gravity.CENTER);
                content.addView(iconBadge);

                // 2. Title
                TextView title = new TextView(this);
                title.setText("DEVICE IS LOCKED");
                title.setTextSize(22);
                title.setTypeface(null, android.graphics.Typeface.BOLD);
                title.setTextColor(Color.WHITE);
                title.setGravity(Gravity.CENTER);
                content.addView(title);

                // 3. Subtitle / Reason
                TextView subtitle = new TextView(this);
                subtitle.setText("Installment Payment Overdue");
                subtitle.setTextSize(13);
                subtitle.setTypeface(null, android.graphics.Typeface.BOLD);
                subtitle.setTextColor(Color.parseColor("#FB7185")); // Rose-400
                subtitle.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                subLp.setMargins(0, dpToPx(4), 0, dpToPx(8));
                content.addView(subtitle, subLp);

                // 4. Instructions
                TextView notice = new TextView(this);
                notice.setText("This smartphone is locked under installment agreement terms.\nPlease pay installment or enter 6-digit Master PIN to unlock.");
                notice.setTextSize(12);
                notice.setTextColor(Color.parseColor("#94A3B8")); // Slate-400
                notice.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                noticeLp.setMargins(0, 0, 0, dpToPx(16));
                content.addView(notice, noticeLp);

                // 5. PIN Display Dots
                final TextView tvPin = new TextView(this);
                tvPin.setText("○  ○  ○  ○  ○  ○");
                tvPin.setTextSize(26);
                tvPin.setTypeface(null, android.graphics.Typeface.BOLD);
                tvPin.setTextColor(Color.parseColor("#F59E0B")); // Amber-500
                tvPin.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                pinLp.setMargins(0, 0, 0, dpToPx(8));
                content.addView(tvPin, pinLp);

                // 6. Error message
                final TextView tvError = new TextView(this);
                tvError.setText("⚠️ Invalid Master PIN");
                tvError.setTextSize(12);
                tvError.setTextColor(Color.parseColor("#F43F5E"));
                tvError.setGravity(Gravity.CENTER);
                tvError.setVisibility(View.GONE);
                content.addView(tvError);

                mOverlayEnteredPin.setLength(0);

                // 7. On-screen numeric keypad (3 columns)
                LinearLayout keypad = new LinearLayout(this);
                keypad.setOrientation(LinearLayout.VERTICAL);
                keypad.setGravity(Gravity.CENTER_HORIZONTAL);
                int maxKeypadWidth = dpToPx(280);
                LinearLayout.LayoutParams keypadLp = new LinearLayout.LayoutParams(
                        maxKeypadWidth,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                keypadLp.setMargins(0, dpToPx(12), 0, dpToPx(16));

                String[][] buttons = {
                        {"1", "2", "3"},
                        {"4", "5", "6"},
                        {"7", "8", "9"},
                        {"⌫", "0", "UNLOCK"}
                };

                for (String[] rowBtns : buttons) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setWeightSum(3);
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    rowLp.setMargins(0, dpToPx(4), 0, dpToPx(4));

                    for (final String label : rowBtns) {
                        Button btn = new Button(this);
                        btn.setText(label);
                        btn.setTextSize(16);
                        btn.setTypeface(null, android.graphics.Typeface.BOLD);
                        btn.setTextColor(Color.WHITE);

                        if ("UNLOCK".equals(label)) {
                            btn.setBackgroundColor(Color.parseColor("#059669")); // Emerald green
                            btn.setTextSize(13);
                        } else if ("⌫".equals(label)) {
                            btn.setBackgroundColor(Color.parseColor("#334155")); // Slate gray
                        } else {
                            btn.setBackgroundColor(Color.parseColor("#1E293B")); // Dark blue-gray
                        }

                        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                                0,
                                dpToPx(50),
                                1.0f
                        );
                        btnLp.setMargins(dpToPx(4), 0, dpToPx(4), 0);

                        btn.setOnClickListener(v -> {
                            tvError.setVisibility(View.GONE);
                            if ("⌫".equals(label)) {
                                if (mOverlayEnteredPin.length() > 0) {
                                    mOverlayEnteredPin.deleteCharAt(mOverlayEnteredPin.length() - 1);
                                }
                            } else if ("UNLOCK".equals(label)) {
                                String pin = mOverlayEnteredPin.toString().trim();
                                initCredentialPrefs();
                                String savedPin1 = "";
                                String savedPin2 = "189853";
                                String deviceCode = "4819";
                                if (mPrefs != null) {
                                    savedPin1 = mPrefs.getString("bashlock_offline_pin", "");
                                    savedPin2 = mPrefs.getString("offline_pin", "189853");
                                    deviceCode = mPrefs.getString("device_code", "4819").trim();
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    try {
                                        Context dbCtx = createDeviceProtectedStorageContext();
                                        SharedPreferences db = dbCtx.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE);
                                        if (savedPin1.isEmpty()) savedPin1 = db.getString("bashlock_offline_pin", "");
                                        if (savedPin2.equals("189853")) savedPin2 = db.getString("offline_pin", "189853");
                                        if (deviceCode.equals("4819")) deviceCode = db.getString("device_code", "4819").trim();
                                    } catch (Exception ignored) {}
                                }
                                String doubleCode = deviceCode + deviceCode;
                                if (pin.equals("9999")
                                        || (!deviceCode.isEmpty() && pin.equals(doubleCode))
                                        || (!deviceCode.isEmpty() && pin.equals(deviceCode))
                                        || pin.equals("189853")
                                        || pin.equals("0047")
                                        || pin.equals("123456")
                                        || pin.equals("4819")
                                        || (!savedPin1.isEmpty() && pin.equals(savedPin1))
                                        || pin.equals(savedPin2)) {
                                    syncLockState(false);
                                    removeGuardLockOverlay();
                                    notifyUnlockState();
                                    Toast.makeText(BashLockGuardService.this, "✅ Device Unlocked with Master PIN!", Toast.LENGTH_LONG).show();
                                    return;
                                } else {
                                    tvError.setText("⚠️ Invalid Master PIN. Try 9999 or pairing code.");
                                    tvError.setVisibility(View.VISIBLE);
                                    mOverlayEnteredPin.setLength(0);
                                    try {
                                        android.os.Vibrator vib = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                                        if (vib != null) vib.vibrate(300);
                                    } catch (Exception ignored) {}
                                }
                            } else {
                                if (mOverlayEnteredPin.length() < 8) {
                                    mOverlayEnteredPin.append(label);
                                }
                            }
                            updateOverlayPinDots(tvPin, mOverlayEnteredPin.length());
                        });

                        row.addView(btn, btnLp);
                    }
                    keypad.addView(row, rowLp);
                }
                content.addView(keypad, keypadLp);

                // Requirement 3: Check Payment Status Now & Shopkeeper Pin Buttons
                Button btnCheckPayment = new Button(this);
                btnCheckPayment.setText("⚡ Check Payment Status Now");
                btnCheckPayment.setBackgroundColor(Color.parseColor("#2563EB")); // Vibrant Blue
                btnCheckPayment.setTextColor(Color.WHITE);
                btnCheckPayment.setTextSize(13);
                btnCheckPayment.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams checkLp = new LinearLayout.LayoutParams(
                        maxKeypadWidth,
                        dpToPx(44)
                );
                checkLp.setMargins(0, dpToPx(6), 0, dpToPx(8));
                btnCheckPayment.setOnClickListener(v -> {
                    Toast.makeText(BashLockGuardService.this, "Checking payment status from live server...", Toast.LENGTH_SHORT).show();
                    Executors.newSingleThreadExecutor().execute(this::checkLockStatusSafely);
                });
                content.addView(btnCheckPayment, checkLp);

                Button btnRefreshStatus = new Button(this);
                btnRefreshStatus.setText("🔑 Shopkeeper Pin / Refresh Status");
                btnRefreshStatus.setBackgroundColor(Color.parseColor("#1E293B")); // Dark Slate
                btnRefreshStatus.setTextColor(Color.parseColor("#38BDF8")); // Sky Blue
                btnRefreshStatus.setTextSize(12);
                btnRefreshStatus.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                        maxKeypadWidth,
                        dpToPx(40)
                );
                refreshLp.setMargins(0, 0, 0, dpToPx(8));
                btnRefreshStatus.setOnClickListener(v -> {
                    Toast.makeText(BashLockGuardService.this, "Enter 9999 or pairing code on keypad above to unlock", Toast.LENGTH_LONG).show();
                    Executors.newSingleThreadExecutor().execute(this::checkLockStatusSafely);
                });
                content.addView(btnRefreshStatus, refreshLp);

                // Emergency Call Access (112)
                Button btnEmergency = new Button(this);
                btnEmergency.setText("🚨 Emergency Call (112)");
                btnEmergency.setBackgroundColor(Color.parseColor("#991B1B")); // Deep Emergency Red
                btnEmergency.setTextColor(Color.WHITE);
                btnEmergency.setTextSize(12);
                btnEmergency.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams emergencyLp = new LinearLayout.LayoutParams(
                        maxKeypadWidth,
                        dpToPx(40)
                );
                emergencyLp.setMargins(0, 0, 0, dpToPx(8));
                btnEmergency.setOnClickListener(v -> {
                    try {
                        Intent dialIntent = new Intent(Intent.ACTION_DIAL);
                        dialIntent.setData(Uri.parse("tel:112"));
                        dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(dialIntent);
                    } catch (Exception e) {
                        Toast.makeText(BashLockGuardService.this, "Emergency dialer error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
                content.addView(btnEmergency, emergencyLp);

                // 8. Open Full Dashboard Button
                TextView openDash = new TextView(this);
                openDash.setText("🔄 Open Full Dashboard");
                openDash.setTextColor(Color.parseColor("#38BDF8")); // Sky blue
                openDash.setTextSize(12);
                openDash.setGravity(Gravity.CENTER);
                openDash.setPadding(0, dpToPx(8), 0, dpToPx(16));
                openDash.setOnClickListener(v -> {
                    wakeUpScreen();
                    Intent lockIntent = new Intent(this, MainActivity.class);
                    lockIntent.setAction("com.bashlock.apk.FORCE_LOCK");
                    lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    lockIntent.putExtra("FORCE_KIOSK_LOCK", true);
                    try {
                        startActivity(lockIntent);
                    } catch (Exception ignored) {}
                });
                content.addView(openDash);

                scrollView.addView(content);
                overlay.addView(scrollView);

                mGuardLockOverlayView = overlay;
                mWindowManager.addView(mGuardLockOverlayView, params);
                Log.i(TAG, "Un-cancelable Lock UI Overlay successfully mounted in WindowManager");
            } catch (Exception e) {
                Log.e(TAG, "showGuardLockOverlay failed: " + e.getMessage(), e);
            }
        });
    }

    private void updateOverlayPinDots(TextView tvPin, int count) {
        StringBuilder sb = new StringBuilder();
        int totalDots = 6;
        for (int i = 0; i < totalDots; i++) {
            if (i < count) {
                sb.append("●  ");
            } else {
                sb.append("○  ");
            }
        }
        tvPin.setText(sb.toString().trim());
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round((float) dp * density);
    }

    /**
     * Removes the SYSTEM_ALERT_WINDOW kiosk overlay when unlocked or when MainActivity is in foreground.
     */
    private void removeGuardLockOverlay() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (mGuardLockOverlayView != null && mWindowManager != null) {
                try {
                    mWindowManager.removeViewImmediate(mGuardLockOverlayView);
                    Log.i(TAG, "Guard SYSTEM_ALERT_WINDOW overlay removed via removeViewImmediate");
                } catch (Exception e) {
                    try {
                        mWindowManager.removeView(mGuardLockOverlayView);
                    } catch (Exception ignored) {}
                }
                mGuardLockOverlayView = null;
            }
        });
    }

    /**
     * Requirement: Complete Remote Device Release & Auto-Uninstall Sequence.
     * Executes when shopkeeper triggers RELEASE or RELEASE_AND_UNINSTALL.
     */
    private void triggerRemoteReleaseSequence(boolean autoPromptUninstall) {
        try {
            Log.i(TAG, "Triggering Remote Release Sequence (autoPromptUninstall: " + autoPromptUninstall + ")...");

            // 1. Clear Direct Boot lock flags
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    Context directBootContext = createDeviceProtectedStorageContext();
                    directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                            .edit().putBoolean("is_locked", false).putBoolean("is_released", true).apply();
                } catch (Exception ignored) {}
            }

            // 2. Clear WakeLock
            if (mWakeLock != null && mWakeLock.isHeld()) {
                mWakeLock.release();
            }

            // 3. Cancel all notifications & overlays
            removeGuardLockOverlay();
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(NOTIFICATION_ID);
                nm.cancel(LOCK_POPUP_NOTIFICATION_ID);
            }

            // 4. Release all Device Owner & Administrator restrictions
            BashLockDeviceAdminReceiver.releaseDeviceCompletely(this);
            SelfProvisioningManager.setAppIconHidden(this, false);

            // 5. Wake screen
            wakeUpScreen();

            // 6. Open MainActivity in released state
            Intent releaseIntent = new Intent(this, MainActivity.class);
            releaseIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            releaseIntent.putExtra("FORCE_RELEASED_VIEW", true);
            startActivity(releaseIntent);

            // 8. Stop polling & terminate foreground service
            if (mScheduler != null) {
                mScheduler.shutdownNow();
            }
            stopForeground(true);
            stopSelf();
        } catch (Exception e) {
            Log.e(TAG, "Error executing triggerRemoteReleaseSequence: " + e.getMessage());
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        initCredentialPrefs();
        boolean isReleased = (mPrefs != null && mPrefs.getBoolean("is_released", false));
        if (isReleased) {
            return;
        }
        Log.i(TAG, "onTaskRemoved triggered. Foreground service remains active.");
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(mUserUnlockedReceiver);
        } catch (Exception ignored) {}
        if (mScheduler != null) {
            mScheduler.shutdownNow();
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        Log.i(TAG, "BashLockGuardService destroyed.");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
