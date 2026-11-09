package com.bashlock.apk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

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
    private static final String CHANNEL_ID = "bashlock_guard_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int LOCK_POPUP_NOTIFICATION_ID = 1002;

    private SharedPreferences mPrefs;
    private ScheduledExecutorService mScheduler;
    private PowerManager.WakeLock mWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = getSharedPreferences("BashLock_Config", MODE_PRIVATE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification("BashLock Protection Active", "Securing device in background"));

        acquireWakeLock();
        startStatusPollingScheduler();
        Log.i(TAG, "BashLockGuardService created and started in foreground");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra("CHECK_NOW", false)) {
            Executors.newSingleThreadExecutor().execute(this::checkLockStatus);
        }
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "BashLock 24/7 Security Guard",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains continuous background device security");
            channel.setShowBadge(false);

            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
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

        return new NotificationCompat.Builder(this, CHANNEL_ID)
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

                        SharedPreferences.Editor editor = mPrefs.edit();
                        if (!dueDate.isEmpty()) editor.putString("due_date", dueDate);
                        if (!pin.isEmpty()) editor.putString("bashlock_offline_pin", pin);
                        editor.apply();

                        if ("LOCKED".equalsIgnoreCase(status) || isOverdue) {
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
            }
        } catch (Exception e) {
            Log.d(TAG, "Polling check server reachability: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String resolveLiveServerUrl() {
        // First check locally cached live URL
        String url = mPrefs.getString("cached_live_url", "");
        if (url.startsWith("http")) {
            return url;
        }
        return MainActivity.DEFAULT_PUBLIC_HTTPS_URL;
    }

    /**
     * Brings MainActivity to the front immediately regardless of what app the user is using.
     */
    private void triggerImmediateLockPopup() {
        mPrefs.edit().putBoolean("is_locked", true).apply();

        // Wake device screen up if turned off
        wakeUpScreen();

        // Intent to launch/bring MainActivity to front
        Intent lockIntent = new Intent(this, MainActivity.class);
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

        // 1. Direct Start (Works with SYSTEM_ALERT_WINDOW permission & Device Admin)
        try {
            startActivity(lockIntent);
        } catch (Exception e) {
            Log.w(TAG, "Direct startActivity warning: " + e.getMessage());
        }

        // 2. High-Priority FullScreen Notification (Guaranteed Android 10+ background popup override)
        Notification highPriorityNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentTitle("⚠️ DEVICE IS LOCKED")
                .setContentText("Installment overdue. Enter Master PIN to unlock.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
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

    @Override
    public void onDestroy() {
        if (mScheduler != null) {
            mScheduler.shutdownNow();
        }
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
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
