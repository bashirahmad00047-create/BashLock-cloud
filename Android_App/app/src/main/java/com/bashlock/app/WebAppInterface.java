package com.bashlock.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

/**
 * Native JavaScript Interface exposed to the WebView in lock_app.html.
 * Allows web code to trigger native Android actions (saving pairing info,
 * locking the screen, requesting admin status, etc.).
 */
public class WebAppInterface {
    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final DevicePolicyManager mDpm;
    private final ComponentName mAdminComponent;

    public WebAppInterface(Context context) {
        this.mContext = context;
        this.mPrefs = context.getSharedPreferences("BashLockPrefs", Context.MODE_PRIVATE);
        this.mDpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.mAdminComponent = new ComponentName(context, BashLockAdminReceiver.class);
    }

    @JavascriptInterface
    public void savePairingData(String setupCode, String offlinePin, String deviceId, String dueDate, String balance) {
        mPrefs.edit()
                .putString("paired_code", setupCode)
                .putString("offline_pin", offlinePin)
                .putString("device_id", deviceId)
                .putString("due_date", dueDate)
                .putString("balance", balance)
                .apply();
        Toast.makeText(mContext, "BashLock: Device Paired (" + setupCode + ")", Toast.LENGTH_SHORT).show();
    }

    @JavascriptInterface
    public void triggerNativeLock(String reason) {
        mPrefs.edit().putBoolean("is_locked", true).apply();

        // 1. Launch high-security Kiosk Lock Activity
        Intent lockIntent = new Intent(mContext, LockActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivity(lockIntent);

        // 2. Hardware screen turn off via Device Admin if available
        if (mDpm != null && mDpm.isAdminActive(mAdminComponent)) {
            try {
                mDpm.lockNow();
            } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public void triggerNativeUnlock() {
        mPrefs.edit().putBoolean("is_locked", false).apply();
        Toast.makeText(mContext, "Device Unlocked by Shopkeeper", Toast.LENGTH_LONG).show();
    }

    @JavascriptInterface
    public String getSavedPairingCode() {
        return mPrefs.getString("paired_code", "");
    }

    @JavascriptInterface
    public String getSavedOfflinePin() {
        return mPrefs.getString("offline_pin", "");
    }

    @JavascriptInterface
    public boolean isDeviceAdminActive() {
        return mDpm != null && mDpm.isAdminActive(mAdminComponent);
    }

    @JavascriptInterface
    public void requestDeviceAdminPermission() {
        if (mDpm != null && !mDpm.isAdminActive(mAdminComponent)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mAdminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "BashLock installment policy requires Device Administrator access to secure device payments.");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(intent);
        }
    }
}
