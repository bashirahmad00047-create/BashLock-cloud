package com.bashlock.apk;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Requirement 4: DeviceAdminReceiver prevents unauthorized uninstallation
 * from Android Settings by non-compliant customers.
 */
public class BashLockDeviceAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "BashLock Device Administration Activated", Toast.LENGTH_SHORT).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Warning shown if user attempts to remove admin access
        return "WARNING: BashLock Device Administration is locked under an active installment agreement. Disabling will freeze device access.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        // If disabled, immediately launch MainActivity to re-assert lock state
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(launchIntent);
    }
}
