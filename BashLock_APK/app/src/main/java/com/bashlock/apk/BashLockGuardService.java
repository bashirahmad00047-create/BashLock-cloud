package com.bashlock.apk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

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

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = getSharedPreferences("BashLock_Config", MODE_PRIVATE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification("BashLock Protection Active", "Securing device in background"));

        acquireWakeLock();
        if (!mPrefs.getBoolean("is_released", false)) {
            BashLockDeviceAdminReceiver.configureDeviceOwnerPolicies(this);
        }
        startStatusPollingScheduler();
        Log.i(TAG, "BashLockGuardService created and started in foreground");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if (intent.getBooleanExtra("CHECK_NOW", false)) {
                Executors.newSingleThreadExecutor().execute(this::checkLockStatus);
            }
            if (intent.getBooleanExtra("DISMISS_GUARD_OVERLAY", false)) {
                removeGuardLockOverlay();
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

    private void startStatusPollingScheduler() {
        if (mScheduler != null && !mScheduler.isShutdown()) {
            return;
        }

        mScheduler = Executors.newSingleThreadScheduledExecutor();
        // Check every 10 seconds
        mScheduler.scheduleWithFixedDelay(this::checkLockStatusSafely, 2, 10, TimeUnit.SECONDS);
    }

    private void checkLockStatusSafely() {
        try {
            if (!mPrefs.getBoolean("is_released", false)) {
                BashLockDeviceAdminReceiver.configureDeviceOwnerPolicies(this);
            }
            checkLockStatus();
        } catch (Throwable t) {
            Log.e(TAG, "Unexpected error during background status check: " + t.getMessage());
        }
    }

    /**
     * Requirement 2: Polls server every 10 seconds.
     * If 'LOCKED' or overdue, immediately forces MainActivity to front.
     */
    private void checkLockStatus() {
        if (mPrefs.getBoolean("is_released", false)) {
            Log.i(TAG, "Device is marked as RELEASED. Guard service shutting down.");
            stopSelf();
            return;
        }

        // 1. Offline due date enforcement check
        String offlineDueDate = mPrefs.getString("due_date", "").trim();
        if (!offlineDueDate.isEmpty()) {
            String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (todayStr.compareTo(offlineDueDate) > 0) {
                Log.w(TAG, "Device overdue offline! Today: " + todayStr + " > Due: " + offlineDueDate);
                triggerImmediateLockPopup();
                return;
            }
        }

        // 2. Online Server check
        String setupCode = mPrefs.getString("setup_code", "").trim();
        String activeUrl = resolveLiveServerUrl();

        if (activeUrl == null || activeUrl.isEmpty()) {
            return;
        }

        // Target endpoint: <baseUrl>/api/device/by-code/<setupCode>/status
        String endpoint;
        if (!setupCode.isEmpty()) {
            endpoint = activeUrl.replaceAll("/lock-app.*", "") + "/api/device/by-code/" + setupCode + "/status";
        } else {
            // No setup code paired yet
            return;
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint + "?t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "BashLock-Background-Guard");

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                if (json.optBoolean("success", false)) {
                    JSONObject device = json.optJSONObject("device");
                    if (device != null) {
                        String status = device.optString("status", "ACTIVE");
                        boolean isOverdue = device.optBoolean("is_overdue", false);
                        String dueDate = device.optString("next_due_date", "");
                        String pin = device.optString("offline_pin", "");
                        String remoteCommand = device.optString("remote_command", "");

                        SharedPreferences.Editor editor = mPrefs.edit();
                        if (!dueDate.isEmpty()) editor.putString("due_date", dueDate);
                        if (!pin.isEmpty()) editor.putString("bashlock_offline_pin", pin);
                        editor.apply();

                        // Check for Remote Wake / Open App command from shopkeeper
                        if ("WAKE_DEVICE".equalsIgnoreCase(remoteCommand)
                                || "OPEN_APP".equalsIgnoreCase(remoteCommand)
                                || "WAKE".equalsIgnoreCase(status)) {
                            wakeUpDeviceAndOpenApp();
                        }

                        if ("RELEASE_AND_UNINSTALL".equalsIgnoreCase(status)
                                || "RELEASED".equalsIgnoreCase(status)
                                || "UNENROLLED".equalsIgnoreCase(status)) {
                            Log.i(TAG, "Server returned status: " + status + " -> COMMENCING FULL REMOTE RELEASE & UNINSTALL");
                            mPrefs.edit().putBoolean("is_released", true).putBoolean("is_locked", false).apply();
                            boolean autoPromptUninstall = "RELEASE_AND_UNINSTALL".equalsIgnoreCase(status);
                            triggerRemoteReleaseSequence(autoPromptUninstall);
                            return;
                        } else if ("LOCKED".equalsIgnoreCase(status) || isOverdue) {
                            Log.w(TAG, "Server returned status: " + status + " (overdue: " + isOverdue + ") -> ENFORCING LOCK");
                            mPrefs.edit().putBoolean("is_locked", true).apply();
                            triggerImmediateLockPopup();
                        } else {
                            boolean wasLocked = mPrefs.getBoolean("is_locked", false);
                            if (wasLocked) {
                                Log.i(TAG, "Device unlocked by shopkeeper -> Releasing lock");
                                mPrefs.edit().putBoolean("is_locked", false).apply();
                                notifyUnlockState();
                            }
                        }
                    }
                }
            } else {
                // If HTTP status is not 200, refresh URL from GitHub
                refreshLiveUrlFromGitHub();
            }
        } catch (Exception e) {
            Log.d(TAG, "Polling check server reachability: " + e.getMessage());
            // If connection fails, fetch latest Cloudflare URL from GitHub
            refreshLiveUrlFromGitHub();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String resolveLiveServerUrl() {
        // First check locally cached live URL
        String url = mPrefs.getString("cached_live_url", "").trim();
        if (url.startsWith("https://") && !url.contains("10.")) {
            return url;
        }
        return MainActivity.DEFAULT_PUBLIC_HTTPS_URL;
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
                    if (fetched.startsWith("https://") && !fetched.contains("10.")) {
                        if (!fetched.endsWith("/lock-app")) {
                            fetched = fetched + "/lock-app";
                        }
                        mPrefs.edit().putString("cached_live_url", fetched).apply();
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
        mPrefs.edit().putBoolean("is_locked", true).apply();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                        .edit().putBoolean("is_locked", true).apply();
            } catch (Exception ignored) {}
        }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                        .edit().putBoolean("is_locked", false).apply();
            } catch (Exception ignored) {}
        }

        removeGuardLockOverlay();

        Intent unlockIntent = new Intent(this, MainActivity.class);
        unlockIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );
        unlockIntent.putExtra("FORCE_KIOSK_UNLOCK", true);
        startActivity(unlockIntent);

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
     * Attaches SYSTEM_ALERT_WINDOW full-screen kiosk guard overlay over any running app.
     */
    private void showGuardLockOverlay() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                return;
            }
            if (mGuardLockOverlayView != null) {
                return;
            }

            try {
                mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                if (mWindowManager == null) return;

                int layoutType;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
                } else {
                    layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
                }

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        layoutType,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.CENTER;

                FrameLayout overlay = new FrameLayout(this);
                overlay.setBackgroundColor(Color.parseColor("#EE05070C"));

                TextView tv = new TextView(this);
                tv.setText("🔒 BashLock Protection Active\nOpening Lock Screen...");
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(16);
                tv.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                );
                overlay.addView(tv, lp);

                overlay.setOnClickListener(v -> {
                    wakeUpScreen();
                    Intent lockIntent = new Intent(this, MainActivity.class);
                    lockIntent.setAction("com.bashlock.apk.FORCE_LOCK");
                    lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    lockIntent.putExtra("FORCE_KIOSK_LOCK", true);
                    startActivity(lockIntent);
                });

                mGuardLockOverlayView = overlay;
                mWindowManager.addView(mGuardLockOverlayView, params);
                Log.i(TAG, "Guard SYSTEM_ALERT_WINDOW overlay attached successfully");
            } catch (Exception e) {
                Log.w(TAG, "showGuardLockOverlay warning: " + e.getMessage());
            }
        });
    }

    /**
     * Removes the SYSTEM_ALERT_WINDOW kiosk overlay when unlocked or when MainActivity is in foreground.
     */
    private void removeGuardLockOverlay() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (mGuardLockOverlayView != null && mWindowManager != null) {
                try {
                    mWindowManager.removeView(mGuardLockOverlayView);
                    Log.i(TAG, "Guard SYSTEM_ALERT_WINDOW overlay removed");
                } catch (Exception ignored) {}
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

            // 6. Direct Uninstall Intent if RELEASE_AND_UNINSTALL requested
            if (autoPromptUninstall) {
                try {
                    Intent uninstallIntent = new Intent(Intent.ACTION_DELETE);
                    uninstallIntent.setData(Uri.parse("package:" + getPackageName()));
                    uninstallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(uninstallIntent);
                } catch (Exception e) {
                    Log.w(TAG, "Direct uninstall trigger warning: " + e.getMessage());
                }
            }

            // 7. Open MainActivity in released state
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
    public void onDestroy() {
        if (mScheduler != null) {
            mScheduler.shutdownNow();
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
        }
        if (mPrefs != null && mPrefs.getBoolean("is_released", false)) {
            Log.i(TAG, "Device is released. Guard service exiting permanently.");
            super.onDestroy();
            return;
        }
        Log.w(TAG, "BashLockGuardService destroyed! Sending restart broadcast.");
        // Auto-restart if killed by system
        Intent restartIntent = new Intent("com.bashlock.apk.RESTART_GUARD_SERVICE");
        sendBroadcast(restartIntent);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
