package com.bashlock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Foreground Service running 24/7 on customer's phone.
 * Polls BashLock Cloud server every 10 seconds and checks offline schedule.
 */
public class LockGuardService extends Service {
    private static final String TAG = "BashLock_GuardService";
    private static final String CHANNEL_ID = "bashlock_guard_channel";
    private static final int NOTIFICATION_ID = 4819;

    private Handler mHandler;
    private Runnable mPollRunnable;
    private SharedPreferences mPrefs;

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = getSharedPreferences("BashLockPrefs", MODE_PRIVATE);
        mHandler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildForegroundNotification());

        startEnforcementLoop();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "BashLock Device Protection",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ensures retail installment device protection and remote lock enforcement");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("BashLock Guard Active")
                .setContentText("Retail installment security monitoring active.")
                .setSmallIcon(android.R.drawable.ic_secure)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void startEnforcementLoop() {
        mPollRunnable = new Runnable() {
            @Override
            public void run() {
                checkDeviceStatus();
                // Repeat check every 10 seconds
                mHandler.postDelayed(this, 10000);
            }
        };
        mHandler.post(mPollRunnable);
    }

    private void checkDeviceStatus() {
        final String code = mPrefs.getString("paired_code", "").trim();
        final String serverUrl = mPrefs.getString("server_url", "https://liberty-opposite-applying-www.trycloudflare.com");

        if (code.isEmpty()) {
            return;
        }

        // Background thread HTTP poll
        new Thread(() -> {
            try {
                URL url = new URL(serverUrl + "/api/device/by-code/" + code + "/status");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    JSONObject json = new JSONObject(response.toString());
                    if (json.optBoolean("success")) {
                        JSONObject dev = json.getJSONObject("device");
                        String status = dev.optString("status", "UNLOCKED");
                        boolean isOverdue = dev.optBoolean("is_overdue", false);
                        String offlinePin = dev.optString("offline_pin", "");
                        double balance = dev.optDouble("remaining_balance", 0);
                        String shopName = dev.optString("shop_name", "Shop");
                        String shopPhone = dev.optString("shop_phone", "");

                        mPrefs.edit()
                                .putString("offline_pin", offlinePin)
                                .putBoolean("is_locked", "LOCKED".equalsIgnoreCase(status) || isOverdue)
                                .putString("balance", String.valueOf((int) balance))
                                .putString("shop_name", shopName)
                                .putString("shop_phone", shopPhone)
                                .apply();

                        if ("LOCKED".equalsIgnoreCase(status) || isOverdue) {
                            Log.w(TAG, "Device status is LOCKED! Bringing LockActivity to front.");
                            launchLockActivity();
                        }
                    }
                }
            } catch (Exception e) {
                // If offline, check local date policy
                checkOfflineDatePolicy();
            }
        }).start();
    }

    private void checkOfflineDatePolicy() {
        String dueDate = mPrefs.getString("due_date", "");
        if (!dueDate.isEmpty()) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                java.util.Date due = sdf.parse(dueDate);
                java.util.Date now = new java.util.Date();
                if (due != null && now.after(due)) {
                    Log.w(TAG, "Offline due date expired. Triggering lock.");
                    mPrefs.edit().putBoolean("is_locked", true).apply();
                    launchLockActivity();
                }
            } catch (Exception ignored) {}
        }
    }

    private void launchLockActivity() {
        Intent lockIntent = new Intent(this, LockActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(lockIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (mHandler != null && mPollRunnable != null) {
            mHandler.removeCallbacks(mPollRunnable);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
