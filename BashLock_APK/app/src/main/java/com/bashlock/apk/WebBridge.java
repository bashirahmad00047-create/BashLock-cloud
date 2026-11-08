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
        mPrefs.edit()
                .putString("setup_code", code)
                .putString("offline_pin", pin)
                .putString("device_id", deviceId)
                .apply();
        showNativeToast("Pairing saved: " + code);
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
        return mActivity.isDeviceAdminActive();
    }

    @JavascriptInterface
    public void requestDeviceAdmin() {
        mActivity.runOnUiThread(mActivity::promptDeviceAdmin);
    }

    @JavascriptInterface
    public void requestOverlayPermission() {
        mActivity.runOnUiThread(mActivity::promptOverlayPermission);
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
