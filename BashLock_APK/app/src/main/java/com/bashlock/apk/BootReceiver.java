package com.bashlock.apk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * BootReceiver:
 * Restarts BashLockGuardService upon device boot, quick boot,
 * package replacement, or unexpected service kill.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BashLock_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.i(TAG, "BootReceiver triggered with action: " + action);

        // 1. Immediately launch persistent 24/7 foreground guard service
        try {
            Intent serviceIntent = new Intent(context, BashLockGuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BashLockGuardService from boot: " + e.getMessage());
        }

        // 2. If device was previously in locked state, immediately bring up Kiosk Lock Screen
        SharedPreferences prefs = context.getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
        boolean isLocked = prefs.getBoolean("is_locked", false);
        if (isLocked) {
            Log.w(TAG, "Device rebooted while locked! Forcing MainActivity Kiosk lock immediately.");
            Intent lockIntent = new Intent(context, MainActivity.class);
            lockIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            );
            lockIntent.putExtra("FORCE_KIOSK_LOCK", true);
            context.startActivity(lockIntent);
        }
    }
}
