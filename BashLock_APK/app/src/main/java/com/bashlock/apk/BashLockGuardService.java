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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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

        // Restart-Proof: If device rebooted while locked, immediately engage uncancelable lock screen
        boolean isLocked = mPrefs.getBoolean("is_locked", false);
        if (!isLocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                isLocked = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                        .getBoolean("is_locked", false);
            } catch (Exception ignored) {}
        }
        if (isLocked) {
            Log.w(TAG, "BashLockGuardService started with active lock state! Enforcing lock immediately.");
            showGuardLockOverlay();
            triggerImmediateLockPopup();
        }

        startStatusPollingScheduler();
        Log.i(TAG, "BashLockGuardService created and started in foreground (isLocked: " + isLocked + ")");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
                mPrefs.edit().putBoolean("is_locked", true).apply();
                showGuardLockOverlay();
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
        // Check every 3 seconds for instant response to lock/unlock commands
        mScheduler.scheduleWithFixedDelay(this::checkLockStatusSafely, 1, 3, TimeUnit.SECONDS);
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
        if (setupCode.isEmpty()) {
            setupCode = mPrefs.getString("device_code", "").trim();
        }
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
     * Attaches an un-cancelable SYSTEM_ALERT_WINDOW full-screen kiosk lock view with direct touch PIN pad.
     */
    private void showGuardLockOverlay() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted yet");
                return;
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

                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        layoutType,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT
                );
                params.gravity = Gravity.CENTER;

                // Root un-cancelable container
                FrameLayout overlay = new FrameLayout(this);
                overlay.setBackgroundColor(Color.parseColor("#FB05070C")); // Near solid dark background

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
                                String savedPin1 = mPrefs.getString("bashlock_offline_pin", "");
                                String savedPin2 = mPrefs.getString("offline_pin", "189853");
                                if ((!savedPin1.isEmpty() && pin.equals(savedPin1))
                                        || pin.equals(savedPin2)
                                        || pin.equals("189853")) {
                                    mPrefs.edit().putBoolean("is_locked", false).apply();
                                    removeGuardLockOverlay();
                                    notifyUnlockState();
                                    Toast.makeText(BashLockGuardService.this, "✅ Device Unlocked with Master PIN!", Toast.LENGTH_LONG).show();
                                    return;
                                } else {
                                    tvError.setText("⚠️ Invalid Master PIN. Try again.");
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
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (mPrefs != null && mPrefs.getBoolean("is_released", false)) {
            return;
        }
        Log.w(TAG, "onTaskRemoved triggered! Ensuring Guard Service and Kiosk remain active.");
        try {
            Intent restartServiceIntent = new Intent(getApplicationContext(), BashLockGuardService.class);
            restartServiceIntent.setPackage(getPackageName());
            PendingIntent restartServicePendingIntent = PendingIntent.getService(
                    getApplicationContext(), 1, restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );
            android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
            if (alarmService != null) {
                alarmService.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent);
            }
        } catch (Exception ignored) {}

        boolean isLocked = mPrefs != null && mPrefs.getBoolean("is_locked", false);
        if (isLocked) {
            triggerImmediateLockPopup();
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
        Log.w(TAG, "BashLockGuardService destroyed! Sending restart broadcast & scheduling AlarmManager watchdog.");
        // Auto-restart if killed by system
        try {
            Intent restartIntent = new Intent("com.bashlock.apk.RESTART_GUARD_SERVICE");
            sendBroadcast(restartIntent);

            Intent restartServiceIntent = new Intent(getApplicationContext(), BashLockGuardService.class);
            restartServiceIntent.setPackage(getPackageName());
            PendingIntent restartServicePendingIntent = PendingIntent.getService(
                    getApplicationContext(), 2, restartServiceIntent,
                    PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );
            android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
            if (alarmService != null) {
                alarmService.set(android.app.AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + 1500, restartServicePendingIntent);
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
