package com.bashlock.apk;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Requirement 4: DeviceAdminReceiver prevents unauthorized uninstallation
 * from Android Settings by non-compliant customers.
 */
public class BashLockDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "BashLock_DeviceAdmin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device Admin enabled");
        Toast.makeText(context, "BashLock Device Administration Activated", Toast.LENGTH_SHORT).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Check if shopkeeper entered PIN
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

        // If authorized within the 60-second window, allow proceeding
        return "Warning: Disabling Device Administration will remove installment compliance.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.w(TAG, "Device Admin disabled. Immediately re-launching MainActivity.");
        // If disabled, immediately launch MainActivity to re-assert lock state
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(launchIntent);
    }
}
