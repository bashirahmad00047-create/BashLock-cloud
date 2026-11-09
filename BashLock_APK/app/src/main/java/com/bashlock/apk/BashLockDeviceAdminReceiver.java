package com.bashlock.apk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/**
 * BashLockDeviceAdminReceiver:
 * Advanced Device Administration & Device Owner Controller.
 *
 * Capabilities when granted Device Owner:
 * 1. Blocks unauthorized uninstallation via setUninstallBlocked().
 * 2. Whitelists app for LockTask (True pinned un-escapable Kiosk mode).
 * 3. Disables status bar pull-down, notifications, and keyguard when device is locked.
 */
public class BashLockDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "BashLock_DeviceAdmin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Admin enabled");
        configureDeviceOwnerPolicies(context);
        Toast.makeText(context, "BashLock Device Administration Activated", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onProfileProvisioningComplete(Context context, Intent intent) {
        super.onProfileProvisioningComplete(context, intent);
        Log.i(TAG, "Device Owner provisioning complete");
        configureDeviceOwnerPolicies(context);
    }

    /**
     * Enforces core Device Owner protections:
     * - Blocks Uninstall in Settings & Google Play
     * - Configures LockTask packages for Kiosk mode
     */
    public static void configureDeviceOwnerPolicies(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, BashLockDeviceAdminReceiver.class);
            String pkg = context.getPackageName();

            if (dpm != null && dpm.isDeviceOwnerApp(pkg)) {
                Log.i(TAG, "Configuring Device Owner policies for: " + pkg);

                // 1. Block Uninstall completely
                dpm.setUninstallBlocked(admin, pkg, true);

                // 2. Configure LockTask Kiosk packages
                dpm.setLockTaskPackages(admin, new String[]{pkg});

                // 3. Block status bar and notifications when locked under LockTask
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                }
                Log.i(TAG, "Device Owner policies successfully applied (Uninstall blocked, LockTask configured)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Device Owner policy configuration warning: " + e.getMessage());
        }
    }

    /**
     * Toggles hardware lock enforcement (status bar & keyguard disable) based on lock state
     */
    public static void setKioskEnforcement(Context context, boolean isLocked) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, BashLockDeviceAdminReceiver.class);
            String pkg = context.getPackageName();

            if (dpm != null && dpm.isDeviceOwnerApp(pkg)) {
                dpm.setUninstallBlocked(admin, pkg, true);
                dpm.setLockTaskPackages(admin, new String[]{pkg});

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setStatusBarDisabled(admin, isLocked);
                    dpm.setKeyguardDisabled(admin, isLocked);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "setKioskEnforcement warning: " + e.getMessage());
        }
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Check if shopkeeper entered Master PIN
        if (!AdminSecurityManager.isAuthorized()) {
            Log.w(TAG, "Unauthorized disable requested! Locking screen & launching PIN prompt.");

            // 1. Instantly lock screen
            try {
                DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (dpm != null) {
                    dpm.lockNow();
                }
            } catch (Exception ignored) {}

            // 2. Launch PIN verification activity
            Intent authIntent = new Intent(context, AdminAuthActivity.class);
            authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(authIntent);

            return "⚠️ SECURITY ALERT: Unauthorized deactivation is blocked! Shopkeeper Master PIN is required.";
        }

        // If authorized within the grace window, unblock uninstall and allow
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, BashLockDeviceAdminReceiver.class);
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                dpm.setUninstallBlocked(admin, context.getPackageName(), false);
            }
        } catch (Exception ignored) {}

        return "Warning: Disabling Device Administration will remove installment compliance.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.w(TAG, "Device Admin disabled. Immediately re-launching MainActivity.");
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(launchIntent);
    }
}
