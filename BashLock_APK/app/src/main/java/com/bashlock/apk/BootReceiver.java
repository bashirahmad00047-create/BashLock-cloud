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
 * BroadcastReceiver listening for BOOT_COMPLETED and LOCKED_BOOT_COMPLETED.
 *
 * Responsibilities:
 * 1. Supports Direct Boot (executes immediately upon device boot before PIN unlock).
 * 2. Launches persistent 24/7 foreground security guard service.
 * 3. Immediately brings up Kiosk Lock Screen if device was previously in locked state.
 * 4. Re-asserts Device Owner protections (setUninstallBlocked and LockTask).
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BashLock_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.i(TAG, "BootReceiver triggered with action: " + action);

        // 1. Re-assert Device Owner policies on reboot
        BashLockDeviceAdminReceiver.configureDeviceOwnerPolicies(context);

        // 2. Check activation, lock state & pairing code across Direct Boot (Device Protected) and Standard storage
        boolean isActivated = false;
        boolean isLocked = false;
        String setupCode = "";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Context directBootContext = context.createDeviceProtectedStorageContext();
                SharedPreferences directPrefs = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE);
                isActivated = directPrefs.getBoolean("is_activated", false);
                if (directPrefs.contains("is_locked")) {
                    isLocked = directPrefs.getBoolean("is_locked", false);
                }
                setupCode = directPrefs.getString("setup_code", directPrefs.getString("device_code", "")).trim();
            }
        } catch (Exception ignored) {}

        if (!isActivated || !isLocked) {
            try {
                SharedPreferences normalPrefs = context.getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
                if (!isActivated) {
                    isActivated = normalPrefs.getBoolean("is_activated", false);
                }
                if (!isLocked) {
                    isLocked = normalPrefs.getBoolean("is_locked", false);
                }
                if (setupCode.isEmpty()) {
                    setupCode = normalPrefs.getString("setup_code", normalPrefs.getString("device_code", "")).trim();
                }
            } catch (Exception ignored) {}
        }
        if ("4819".equals(setupCode)) {
            setupCode = "";
        }

        // 3. Commercial Lock State:
        // NEVER lock during setup! Only enforce lock on boot if device was paired & activated AND is_locked is true!
        boolean shouldEnforceBootLock = isActivated && isLocked && !setupCode.isEmpty() && !"4819".equals(setupCode);

        // Synchronously commit both contexts (commit() not apply())
        if (isLocked) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Context directBootContext = context.createDeviceProtectedStorageContext();
                    directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_locked", true).putString("setup_code", setupCode).commit();
                }
            } catch (Exception ignored) {}
            try {
                context.getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE)
                        .edit().putBoolean("is_locked", true).putString("setup_code", setupCode).commit();
            } catch (Exception ignored) {}
        }

        // 4. Immediately launch persistent 24/7 foreground guard service with WindowManager overlay active
        try {
            Intent serviceIntent = new Intent(context, BashLockGuardService.class);
            serviceIntent.putExtra("FORCE_GUARD_OVERLAY", shouldEnforceBootLock);
            serviceIntent.putExtra("CHECK_NOW", true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "BashLockGuardService started from BootReceiver (shouldEnforceBootLock: " + shouldEnforceBootLock + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BashLockGuardService on boot: " + e.getMessage());
        }

        // 5. Bypass Android 10+ background activity restrictions:
        // Wake screen and launch MainActivity on top of the lockscreen
        if (shouldEnforceBootLock) {
            Log.w(TAG, "Device rebooted while in LOCKED state! Forcing MainActivity Kiosk lock immediately.");
            try {
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                            android.os.PowerManager.FULL_WAKE_LOCK | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP | android.os.PowerManager.ON_AFTER_RELEASE,
                            "BashLock::BootWakeLock"
                    );
                    wl.acquire(10000L);
                }
            } catch (Exception ignored) {}

            Intent lockIntent = new Intent(context, MainActivity.class);
            lockIntent.setAction("com.bashlock.apk.FORCE_LOCK");
            lockIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            lockIntent.putExtra("FORCE_KIOSK_LOCK", true);
            try {
                context.startActivity(lockIntent);
            } catch (Exception e) {
                Log.w(TAG, "startActivity on boot note: " + e.getMessage());
            }
        }
    }
}
