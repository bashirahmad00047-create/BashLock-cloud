package com.bashlock.apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

/**
 * Native JavaScript Bridge connecting WebKit WebView with Android APIs.
 */
public class WebBridge {
    private final MainActivity mActivity;
    private final SharedPreferences mPrefs;
    private final Vibrator mVibrator;

    public WebBridge(MainActivity activity) {
        this.mActivity = activity;
        this.mPrefs = activity.getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
        this.mVibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
    }

    @JavascriptInterface
    public void setLockState(boolean isLocked) {
        mPrefs.edit().putBoolean("is_locked", isLocked).apply();
        mActivity.runOnUiThread(() -> mActivity.setKioskLockState(isLocked));
    }

    @JavascriptInterface
    public void savePairingData(String code, String pin, String deviceId) {
        savePairingData(code, pin, deviceId, "");
    }

    @JavascriptInterface
    public void savePairingData(String code, String pin, String deviceId, String dueDate) {
        SharedPreferences.Editor editor = mPrefs.edit()
                .putString("setup_code", code)
                .putString("offline_pin", pin)
                .putString("bashlock_offline_pin", pin)
                .putString("device_id", deviceId);
        if (dueDate != null && !dueDate.isEmpty()) {
            editor.putString("due_date", dueDate);
        }
        editor.apply();
        showNativeToast("Pairing saved: " + code);

        // Finalize self-provisioning & engage stealth mode
        mActivity.runOnUiThread(() -> SelfProvisioningManager.finalizeSelfProvisioning(mActivity));

        // Notify background service to perform instant poll
        try {
            android.content.Intent serviceIntent = new android.content.Intent(mActivity, BashLockGuardService.class);
            serviceIntent.putExtra("CHECK_NOW", true);
            androidx.core.content.ContextCompat.startForegroundService(mActivity, serviceIntent);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public String getProvisioningStatusJson() {
        return SelfProvisioningManager.getProvisioningStatus(mActivity).toString();
    }

    @JavascriptInterface
    public void runNextProvisioningStep() {
        mActivity.runOnUiThread(() -> SelfProvisioningManager.runNextProvisioningStep(mActivity));
    }

    @JavascriptInterface
    public void setAppIconHidden(boolean hide) {
        mActivity.runOnUiThread(() -> SelfProvisioningManager.setAppIconHidden(mActivity, hide));
    }

    @JavascriptInterface
    public String getSavedSetupCode() {
        return mPrefs.getString("setup_code", "");
    }

    @JavascriptInterface
    public String getSavedOfflinePin() {
        return mPrefs.getString("offline_pin", "189853");
    }

    @JavascriptInterface
    public boolean isDeviceAdminActive() {
        try {
            return mActivity.isDeviceAdminActive();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void requestDeviceAdmin() {
        try {
            mActivity.runOnUiThread(mActivity::promptDeviceAdmin);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public boolean hasOverlayPermission() {
        try {
            return mActivity.hasOverlayPermission();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void requestOverlayPermission() {
        try {
            mActivity.runOnUiThread(mActivity::promptOverlayPermission);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public boolean isAccessibilityServiceEnabled() {
        try {
            return mActivity.isAccessibilityServiceEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void requestAccessibilityService() {
        try {
            mActivity.runOnUiThread(mActivity::promptAccessibilityService);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void requestBatteryOptimization() {
        try {
            mActivity.runOnUiThread(mActivity::promptBatteryOptimization);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public boolean isDeviceOwner() {
        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) mActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(mActivity.getPackageName());
    }

    @JavascriptInterface
    public boolean isDeviceReleased() {
        return mPrefs.getBoolean("is_released", false);
    }

    @JavascriptInterface
    public void uninstallApp() {
        mActivity.runOnUiThread(() -> {
            try {
                // Ensure all device admin policies and owner locks are removed
                BashLockDeviceAdminReceiver.releaseDeviceCompletely(mActivity);
                SelfProvisioningManager.setAppIconHidden(mActivity, false);

                android.content.Intent uninstallIntent = new android.content.Intent(android.content.Intent.ACTION_DELETE);
                uninstallIntent.setData(android.net.Uri.parse("package:" + mActivity.getPackageName()));
                uninstallIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                mActivity.startActivity(uninstallIntent);
            } catch (Exception e) {
                showNativeToast("Uninstall launch error: " + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void showNativeToast(String message) {
        mActivity.runOnUiThread(() -> Toast.makeText(mActivity, message, Toast.LENGTH_SHORT).show());
    }

    @JavascriptInterface
    public void vibrateDevice(long milliseconds) {
        if (mVibrator != null) {
            mVibrator.vibrate(milliseconds);
        }
    }
}
