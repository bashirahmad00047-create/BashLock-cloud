package com.bashlock.app;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * BashLock Device Administration Receiver.
 * Enforces remote device lock, prevents unauthorized uninstall,
 * and handles security policies for smartphone retail installment plans.
 */
public class BashLockAdminReceiver extends DeviceAdminReceiver {

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "BashLock Device Administration Enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Warning shown when a customer or unauthorized person tries to remove Device Admin
        return "Warning: Disabling BashLock Device Administration will immediately trigger complete device screen lock due to active installment policy.";
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        // If disabled, immediately launch the lock activity to prevent tampering
        Intent lockIntent = new Intent(context, LockActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(lockIntent);
    }
}
