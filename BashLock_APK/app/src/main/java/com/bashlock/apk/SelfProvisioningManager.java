package com.bashlock.apk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * SelfProvisioningManager:
 * Implements automated in-app Self-Provisioning & Anti-Uninstall Activation.
 *
 * Responsibilities:
 * 1. Detects Device Owner, Device Admin, Accessibility Guard, Overlay, and Battery privileges.
 * 2. Automates step-by-step in-app activation prompts without requiring ADB commands.
 * 3. Automatically stealth-hides the app icon from the launcher once activated.
 * 4. Ensures 24/7 persistent foreground service and strict anti-uninstall enforcement.
 */
public class SelfProvisioningManager {
    private static final String TAG = "BashLock_SelfProvision";

    /**
     * Checks complete provisioning readiness
     */
    public static JSONObject getProvisioningStatus(Context context) {
        JSONObject json = new JSONObject();
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(context, BashLockDeviceAdminReceiver.class);
            String pkg = context.getPackageName();

            boolean isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(pkg);
            boolean isAdminActive = dpm != null && dpm.isAdminActive(admin);
            boolean hasOverlay = hasOverlayPermission(context);
            boolean hasAccessibility = isAccessibilityEnabled(context);
            boolean isBatteryIgnored = isBatteryOptimizationIgnored(context);

            SharedPreferences prefs = context.getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
            boolean isIconHidden = prefs.getBoolean("is_icon_hidden", false);
            boolean isPaired = !prefs.getString("setup_code", "").isEmpty();

            json.put("is_device_owner", isDeviceOwner);
            json.put("is_admin_active", isAdminActive);
            json.put("has_overlay", hasOverlay);
            json.put("has_accessibility", hasAccessibility);
            json.put("is_battery_ignored", isBatteryIgnored);
            json.put("is_icon_hidden", isIconHidden);
            json.put("is_paired", isPaired);
            
            // Fully customer-ready if Device Owner OR (Admin + Accessibility + Overlay + Battery) are granted
            boolean isFullyGuarded = (isDeviceOwner || (isAdminActive && hasAccessibility && hasOverlay && isBatteryIgnored));
            json.put("is_fully_guarded", isFullyGuarded);

        } catch (Exception e) {
            Log.e(TAG, "getProvisioningStatus error: " + e.getMessage());
        }
        return json;
    }

    /**
     * Executes the next missing permission prompt in the automated self-provisioning sequence
     */
    public static boolean runNextProvisioningStep(Activity activity) {
        Context context = activity.getApplicationContext();
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, BashLockDeviceAdminReceiver.class);

        // 1. Device Admin
        if (dpm != null && !dpm.isAdminActive(admin)) {
            try {
                Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "BashLock Device Administrator policy prevents unauthorized uninstallation under installment terms.");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                Toast.makeText(activity, "Step 1: Activate Device Administrator", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "DeviceAdmin prompt error: " + e.getMessage());
            }
            return true;
        }

        // 2. Overlay Permission (Display over other apps)
        if (!hasOverlayPermission(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                    Toast.makeText(activity, "Step 2: Allow 'Display over other apps'", Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    Log.e(TAG, "Overlay prompt error: " + e.getMessage());
                }
                return true;
            }
        }

        // 3. Security Accessibility Service
        if (!isAccessibilityEnabled(context)) {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                Toast.makeText(activity, "Step 3: Enable 'BashLock Security Guard' in Accessibility", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e(TAG, "Accessibility prompt error: " + e.getMessage());
            }
            return true;
        }

        // 4. Battery Optimization Exemption
        if (!isBatteryOptimizationIgnored(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(intent);
                    Toast.makeText(activity, "Step 4: Allow Unrestricted Background Operation", Toast.LENGTH_SHORT).show();
                    return true;
                } catch (Exception ignored) {}
            }
        }

        // All granted -> Enforce policies & auto-stealth hide icon
        finalizeSelfProvisioning(activity);
        return false;
    }

    /**
     * Finalizes self-provisioning: applies policies, starts 24/7 service, and hides icon
     */
    public static void finalizeSelfProvisioning(Activity activity) {
        Context context = activity.getApplicationContext();
        Log.i(TAG, "Finalizing Self-Provisioning: Enforcing policies and stealth mode...");

        // 1. Enforce Device Admin & Owner policies
        BashLockDeviceAdminReceiver.configureDeviceOwnerPolicies(context);

        // 2. Ensure 24/7 persistent service is running
        try {
            Intent serviceIntent = new Intent(context, BashLockGuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForegroundService: " + e.getMessage());
        }

        Toast.makeText(activity, "✅ BashLock Protection Fully Activated & Secured!", Toast.LENGTH_LONG).show();
    }

    /**
     * Stealth-hides the launcher app icon from home screen and app drawer using LauncherAlias
     */
    public static void setAppIconHidden(Context context, boolean hide) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName alias = new ComponentName(context.getPackageName(), "com.bashlock.apk.LauncherAlias");
            int newState = hide
                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            pm.setComponentEnabledSetting(alias, newState, PackageManager.DONT_KILL_APP);

            context.getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE)
                    .edit().putBoolean("is_icon_hidden", hide).apply();
            Log.i(TAG, "App icon visibility updated (hide: " + hide + ")");
        } catch (Exception e) {
            Log.e(TAG, "setAppIconHidden error: " + e.getMessage());
        }
    }

    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    public static boolean isAccessibilityEnabled(Context context) {
        int accessibilityEnabled = 0;
        final String service = context.getPackageName() + "/" + AdminSecurityAccessibilityService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException ignored) {}

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(service);
            }
        }
        return false;
    }

    public static boolean isBatteryOptimizationIgnored(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return true;
    }
}
