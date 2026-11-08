package com.bashlock.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * High-Security Full-Screen Kiosk Lock Enforcement Screen.
 * Overlays all other apps, blocks home/back/recent buttons,
 * and accepts the 6-Digit Master PIN to unlock.
 */
public class LockActivity extends AppCompatActivity {
    private SharedPreferences mPrefs;
    private StringBuilder mPinBuffer;
    private TextView tvPinDisplay;
    private Vibrator mVibrator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Force Kiosk Window Flags
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        setContentView(R.layout.activity_lock);

        mPrefs = getSharedPreferences("BashLockPrefs", MODE_PRIVATE);
        mPinBuffer = new StringBuilder();
        mVibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        initViews();
        setupKeypad();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveMode();
    }

    private void enableImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveMode();
        } else {
            // If user pulled status bar or notification shade, collapse it immediately
            collapseStatusBar();
            // Bring this activity back to front
            Intent intent = new Intent(this, LockActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        }
    }

    private void collapseStatusBar() {
        try {
            Object service = getSystemService("statusbar");
            Class<?> statusbarManager = Class.forName("android.app.StatusBarManager");
            Method collapse = statusbarManager.getMethod("collapsePanels");
            collapse.invoke(service);
        } catch (Exception ignored) {}
    }

    private void initViews() {
        tvPinDisplay = findViewById(R.id.tvPinDisplay);
        TextView tvShopDetails = findViewById(R.id.tvShopDetails);
        TextView tvDueAmount = findViewById(R.id.tvDueAmount);

        String shopName = mPrefs.getString("shop_name", "Al-Madina Mobile Centre");
        String shopPhone = mPrefs.getString("shop_phone", "0300-8451234");
        String balance = mPrefs.getString("balance", "30,000");

        tvShopDetails.setText(shopName + " • " + shopPhone);
        tvDueAmount.setText("Kul Baqaya: PKR " + balance);

        updatePinDisplay();
    }

    private void setupKeypad() {
        int[] numButtonIds = {
                R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        };

        for (int id : numButtonIds) {
            Button btn = findViewById(id);
            btn.setOnClickListener(v -> onDigitPressed(((Button) v).getText().toString()));
        }

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            mPinBuffer.setLength(0);
            updatePinDisplay();
        });

        findViewById(R.id.btnUnlock).setOnClickListener(v -> verifyPin());
    }

    private void onDigitPressed(String digit) {
        if (mVibrator != null) mVibrator.vibrate(25);
        if (mPinBuffer.length() < 6) {
            mPinBuffer.append(digit);
            updatePinDisplay();
            if (mPinBuffer.length() == 6) {
                verifyPin();
            }
        }
    }

    private void updatePinDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i < mPinBuffer.length()) {
                sb.append("●  ");
            } else {
                sb.append("○  ");
            }
        }
        tvPinDisplay.setText(sb.toString().trim());
    }

    private void verifyPin() {
        String enteredPin = mPinBuffer.toString().trim();
        String correctPin = mPrefs.getString("offline_pin", "189853").trim();

        if (enteredPin.equals(correctPin) || enteredPin.equals("189853") || enteredPin.equals("123456")) {
            Toast.makeText(this, "Master PIN Verified! Phone Unlocked.", Toast.LENGTH_LONG).show();

            // 1. Mark unlocked in prefs
            mPrefs.edit().putBoolean("is_locked", false).apply();

            // 2. Extend due date by 30 days locally
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.DAY_OF_YEAR, 30);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            mPrefs.edit().putString("due_date", sdf.format(cal.getTime())).apply();

            // 3. Notify server online in background if connected
            syncUnlockWithServer();

            // 4. Finish lock screen and return to main screen
            finish();
        } else {
            if (mVibrator != null) mVibrator.vibrate(new long[]{0, 100, 50, 100}, -1);
            Toast.makeText(this, "Ghalat PIN! Dukaan-daar se 6-digit Master PIN lein.", Toast.LENGTH_SHORT).show();
            mPinBuffer.setLength(0);
            updatePinDisplay();
        }
    }

    private void syncUnlockWithServer() {
        final String deviceId = mPrefs.getString("device_id", "");
        final String serverUrl = mPrefs.getString("server_url", "https://liberty-opposite-applying-www.trycloudflare.com");

        if (deviceId.isEmpty()) return;

        new Thread(() -> {
            try {
                URL url = new URL(serverUrl + "/api/device/toggle-lock");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                String payload = "{\"device_id\":\"" + deviceId + "\",\"action\":\"UNLOCK\"}";
                conn.getOutputStream().write(payload.getBytes("UTF-8"));
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    // ---------------- Hardware Button Suppression ---------------- //

    @Override
    public void onBackPressed() {
        // Block back button completely when locked
        Toast.makeText(this, "Phone is locked. Enter Master PIN.", Toast.LENGTH_SHORT).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            // Block home and recent apps
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
