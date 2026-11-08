package com.bashlock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Autostart Receiver: Launches the BashLock Guard Service and checks lock state on boot.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BashLock_Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Boot event received: " + action);

        // 1. Start persistent Foreground Guard Service
        Intent serviceIntent = new Intent(context, LockGuardService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        // 2. Check if phone was in locked state before reboot
        SharedPreferences prefs = context.getSharedPreferences("BashLockPrefs", Context.MODE_PRIVATE);
        boolean isLocked = prefs.getBoolean("is_locked", false);

        if (isLocked) {
            Log.w(TAG, "Device was in LOCKED state prior to boot. Launching LockActivity immediately.");
            Intent lockIntent = new Intent(context, LockActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(lockIntent);
        } else {
            // Launch main app
            Intent appIntent = new Intent(context, MainActivity.class);
            appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(appIntent);
        }
    }
}
