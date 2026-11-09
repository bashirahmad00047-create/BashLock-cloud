package com.bashlock.apk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class AdminAuthActivity extends AppCompatActivity {
    private final StringBuilder mEnteredPin = new StringBuilder();
    private View[] mDots;
    private TextView mTvSubtitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make window fullscreen & keep on top
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        setContentView(R.layout.activity_admin_auth);

        mTvSubtitle = findViewById(R.id.tvSubtitle);
        mDots = new View[]{
                findViewById(R.id.dot0),
                findViewById(R.id.dot1),
                findViewById(R.id.dot2),
                findViewById(R.id.dot3),
                findViewById(R.id.dot4),
                findViewById(R.id.dot5)
        };

        setupKeypad();
    }

    private void setupKeypad() {
        int[] numButtonIds = new int[]{
                R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
                R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        };

        for (int id : numButtonIds) {
            Button btn = findViewById(id);
            if (btn != null) {
                btn.setOnClickListener(v -> onDigitPressed(btn.getText().toString()));
            }
        }

        Button btnClear = findViewById(R.id.btnClear);
        if (btnClear != null) {
            btnClear.setOnClickListener(v -> onBackspacePressed());
        }

        Button btnCancel = findViewById(R.id.btnCancel);
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> exitToHomeScreen());
        }
    }

    private void onDigitPressed(String digit) {
        if (mEnteredPin.length() < 6) {
            mEnteredPin.append(digit);
            updateDotIndicators();

            if (mEnteredPin.length() == 6) {
                verifyPin();
            }
        }
    }

    private void onBackspacePressed() {
        if (mEnteredPin.length() > 0) {
            mEnteredPin.deleteCharAt(mEnteredPin.length() - 1);
            updateDotIndicators();
        }
    }

    private void updateDotIndicators() {
        int length = mEnteredPin.length();
        for (int i = 0; i < mDots.length; i++) {
            if (i < length) {
                mDots[i].setBackgroundColor(Color.parseColor("#10B981")); // Filled dot
            } else {
                mDots[i].setBackgroundColor(Color.parseColor("#475569")); // Empty dot
            }
        }
    }

    private void verifyPin() {
        String pin = mEnteredPin.toString();
        if (AdminSecurityManager.verifyPin(this, pin)) {
            // Authorized for 60 seconds to perform admin action
            AdminSecurityManager.grantAuthorization(60_000);
            Toast.makeText(this, "Admin Authenticated. Access Granted for 60s.", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            // Wrong PIN
            triggerVibration();
            Toast.makeText(this, "Invalid Master PIN! Access Blocked.", Toast.LENGTH_SHORT).show();
            mEnteredPin.setLength(0);
            updateDotIndicators();
        }
    }

    private void triggerVibration() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(200);
            }
        } catch (Exception ignored) {}
    }

    private void exitToHomeScreen() {
        AdminSecurityManager.revokeAuthorization();
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Pressing Back always cancels and kicks the user to Home
        exitToHomeScreen();
    }
}
