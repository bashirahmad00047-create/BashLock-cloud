package com.bashlock.apk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.UserManager;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

/**
 * BashLockDeviceAdminReceiver:
 * Advanced Device Administration & Device Owner Controller.
 *
 * Capabilities when granted Device Owner:
 * 1. Blocks unauthorized uninstallation via setUninstallBlocked() & DISALLOW_UNINSTALL_APPS.
 * 2. Blocks Safe Mode bypass via DISALLOW_SAFE_BOOT.
 * 3. Blocks Recovery / Hardware Factory Reset via DISALLOW_FACTORY_RESET.
 * 4. Whitelists app for LockTask (True pinned un-escapable Kiosk mode).
 * 5. Disables status bar pull-down, notifications, and keyguard when device is locked.
 * 6. Supports complete remote release & unenrollment on full installment clearance.
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

        // Extract Enterprise QR provisioning extras (Zero-Touch Enrollment)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PersistableBundle extras = (PersistableBundle) intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE);
                if (extras != null) {
                    String serverUrl = extras.getString("server_url");
                    if (serverUrl == null) serverUrl = "";
                    String setupCode = extras.getString("setup_code");
                    if (setupCode == null) setupCode = "";
                    Log.i(TAG, "Provisioning extras extracted: code=" + setupCode + ", url=" + serverUrl);

                    if (setupCode != null && !setupCode.trim().isEmpty()) {
                        SharedPreferences prefs = context.getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
                        SharedPreferences.Editor ed = prefs.edit();
                        ed.putString("setup_code", setupCode.trim());
                        ed.putString("device_code", setupCode.trim());
                        if (serverUrl != null && !serverUrl.trim().isEmpty()) {
                            ed.putString("last_working_url", serverUrl.trim());
                        }
                        ed.putBoolean("is_activated", true);
                        ed.putBoolean("is_released", false);
                        ed.putBoolean("is_locked", false);
                        ed.commit();

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            try {
                                Context directBootContext = context.createDeviceProtectedStorageContext();
                                SharedPreferences dbPrefs = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE);
                                SharedPreferences.Editor dbed = dbPrefs.edit();
                                dbed.putString("setup_code", setupCode.trim());
                                dbed.putString("device_code", setupCode.trim());
                                if (serverUrl != null && !serverUrl.trim().isEmpty()) {
                                    dbed.putString("last_working_url", serverUrl.trim());
                                }
                                dbed.putBoolean("is_activated", true);
                                dbed.putBoolean("is_released", false);
                                dbed.putBoolean("is_locked", false);
                                dbed.commit();
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Provisioning bundle extraction warning: " + e.getMessage());
        }

        // Start background guard service immediately
        try {
            Intent guardIntent = new Intent(context, BashLockGuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, guardIntent);
            } else {
                context.startService(guardIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to start BashLockGuardService after provisioning: " + e.getMessage());
        }
    }

    /**
     * Enforces core Device Owner protections:
     * - Blocks Uninstall in Settings & Google Play
     * - Blocks Safe-Boot / Safe-Mode bypass
     * - Blocks Factory Reset bypass
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

                // 2. Comprehensive User & Hardware level tamper restrictions (Reset-Proof)
                try {
                    // Block soft & recovery factory reset
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
                    // Block Safe Mode (Safe Boot)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
                    // Block app uninstall
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
                    // Block developer options & USB debugging (ADB)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
                    // Block adding/removing users or guest accounts
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_REMOVE_USER);
                    // Block modifying Google/cloud accounts (FRP bypass prevention)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS);
                    // Block mounting SD card / USB OTG
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
                    // Block MTP USB file transfer
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
                    // Block app control (Force Stop / Clear Data in Settings -> Apps)
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
                } catch (Exception e) {
                    Log.w(TAG, "User restriction warning: " + e.getMessage());
                }

                // 3. Configure LockTask Kiosk packages
                dpm.setLockTaskPackages(admin, new String[]{pkg, "com.bashlock.apk"});

                // 4. Block status bar and notifications when locked under LockTask
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                }
                Log.i(TAG, "Device Owner policies successfully applied (Uninstall & Reset blocked, LockTask configured)");
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
                try {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_REMOVE_USER);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
                } catch (Exception ignored) {}

                dpm.setLockTaskPackages(admin, new String[]{pkg, "com.bashlock.apk"});

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setStatusBarDisabled(admin, isLocked);
                    dpm.setKeyguardDisabled(admin, isLocked);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "setKioskEnforcement warning: " + e.getMessage());
        }
    }

    /**
     * Completely releases the device when all installments are cleared (Remote NOC):
     * 1. Clears all UserManager anti-uninstall and factory-reset restrictions.
     * 2. Unblocks uninstallation in package manager.
     * 3. Disengages LockTask and reenables status bar / keyguard.
     * 4. Relinquishes Device Owner privilege (clearDeviceOwnerApp).
     * 5. Removes Device Administrator privilege.
     */
    public static void releaseDeviceCompletely(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, BashLockDeviceAdminReceiver.class);
            String pkg = context.getPackageName();

            if (dpm != null) {
                Log.i(TAG, "Initiating complete device release / unenrollment for: " + pkg);

                // 1. Clear user restrictions
                try {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_REMOVE_USER);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
                } catch (Exception e) {
                    Log.w(TAG, "clearUserRestriction: " + e.getMessage());
                }

                // 2. Unblock uninstallation
                try {
                    dpm.setUninstallBlocked(admin, pkg, false);
                } catch (Exception ignored) {}

                // 3. Clear LockTask & re-enable UI elements
                try {
                    dpm.setLockTaskPackages(admin, new String[]{});
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        dpm.setStatusBarDisabled(admin, false);
                        dpm.setKeyguardDisabled(admin, false);
                    }
                } catch (Exception ignored) {}

                // 4. Relinquish Device Owner privilege
                try {
                    if (dpm.isDeviceOwnerApp(pkg)) {
                        dpm.clearDeviceOwnerApp(pkg);
                        Log.i(TAG, "Device Owner privilege successfully cleared!");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "clearDeviceOwnerApp warning: " + e.getMessage());
                }

                // 5. Remove Device Administrator
                try {
                    if (dpm.isAdminActive(admin)) {
                        dpm.removeActiveAdmin(admin);
                        Log.i(TAG, "Device Admin successfully removed!");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "removeActiveAdmin warning: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in releaseDeviceCompletely: " + e.getMessage());
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
        Log.w(TAG, "Device Admin disabled. Checking if released before relaunching.");
        SharedPreferences prefs = context.getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
        boolean isReleased = prefs.getBoolean("is_released", false);
        if (!isReleased) {
            Intent launchIntent = new Intent(context, MainActivity.class);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(launchIntent);
        }
    }
}
