package com.bashlock.apk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Vibrator;
import android.util.Log;
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
    public void onPageReady() {
        mActivity.runOnUiThread(mActivity::dismissSplashOverlay);
    }

    // Requirement 1: Shared Lock State Sync (DirectBoot + Standard using commit())
    public void syncBothPrefs(boolean isLocked, String code, String pin, String deviceId, String dueDate) {
        // 1. Standard SharedPreferences
        SharedPreferences.Editor editor = mPrefs.edit();
        if (code != null && !code.isEmpty()) {
            editor.putString("setup_code", code);
            editor.putString("device_code", code);
        }
        if (pin != null && !pin.isEmpty()) {
            editor.putString("offline_pin", pin);
            editor.putString("bashlock_offline_pin", pin);
        }
        if (deviceId != null && !deviceId.isEmpty()) {
            editor.putString("device_id", deviceId);
        }
        if (dueDate != null && !dueDate.isEmpty()) {
            editor.putString("due_date", dueDate);
        }
        editor.putBoolean("is_released", false);
        editor.putBoolean("is_locked", isLocked);
        editor.commit(); // Synchronous persistent write

        // 2. Native DeviceProtectedStorageContext (Direct Boot) SharedPreferences
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directContext = mActivity.createDeviceProtectedStorageContext();
                SharedPreferences directPrefs = directContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE);
                SharedPreferences.Editor directEditor = directPrefs.edit();
                if (code != null && !code.isEmpty()) {
                    directEditor.putString("setup_code", code);
                    directEditor.putString("device_code", code);
                }
                if (pin != null && !pin.isEmpty()) {
                    directEditor.putString("offline_pin", pin);
                    directEditor.putString("bashlock_offline_pin", pin);
                }
                if (deviceId != null && !deviceId.isEmpty()) {
                    directEditor.putString("device_id", deviceId);
                }
                if (dueDate != null && !dueDate.isEmpty()) {
                    directEditor.putString("due_date", dueDate);
                }
                directEditor.putBoolean("is_released", false);
                directEditor.putBoolean("is_locked", isLocked);
                directEditor.commit(); // Synchronous persistent write
            } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public void setLockState(boolean isLocked) {
        syncBothPrefs(isLocked, null, null, null, null);
        mActivity.runOnUiThread(() -> mActivity.setKioskLockState(isLocked));
    }

    @JavascriptInterface
    public void savePairingData(String code, String pin, String deviceId) {
        savePairingData(code, pin, deviceId, "");
    }

    @JavascriptInterface
    public void savePairingData(String code, String pin, String deviceId, String dueDate) {
        boolean currentLock = mPrefs.getBoolean("is_locked", false);
        syncBothPrefs(currentLock, code, pin, deviceId, dueDate);

        if (code != null && !code.trim().isEmpty() && !"4819".equals(code.trim())) {
            mPrefs.edit().putBoolean("is_activated", true).commit();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    mActivity.createDeviceProtectedStorageContext()
                            .getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_activated", true).commit();
                } catch (Exception ignored) {}
            }
        }

        showNativeToast("Pairing saved: " + code);

        // Finalize self-provisioning & engage stealth mode
        mActivity.runOnUiThread(() -> SelfProvisioningManager.finalizeSelfProvisioning(mActivity));

        // Notify background service to perform instant poll
        try {
            android.content.Intent serviceIntent = new android.content.Intent(mActivity, BashLockGuardService.class);
            serviceIntent.putExtra("CHECK_NOW", true);
            serviceIntent.putExtra("RESTART_FAST_POLL", true);
            androidx.core.content.ContextCompat.startForegroundService(mActivity, serviceIntent);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void activateWithCode(String code) {
        if (code == null) return;
        final String trimmedCode = code.trim();
        if (trimmedCode.isEmpty()) return;

        // Save is_activated=true, device_code, and setup_code using commit().
        // Requirement 1: Only mark "is_activated"=true on code activation, and start in UNLOCKED state!
        mPrefs.edit()
                .putBoolean("is_activated", true)
                .putBoolean("is_locked", false)
                .putString("device_code", trimmedCode)
                .putString("setup_code", trimmedCode)
                .commit();

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directBootContext = mActivity.createDeviceProtectedStorageContext();
                directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("is_activated", true)
                        .putBoolean("is_locked", false)
                        .putString("device_code", trimmedCode)
                        .putString("setup_code", trimmedCode)
                        .commit();
            } catch (Exception ignored) {}
        }

        // Stealth Mode: Hide the launcher icon from customer's home screen & app drawer
        try {
            android.content.pm.PackageManager pm = mActivity.getPackageManager();
            android.content.ComponentName alias = new android.content.ComponentName(mActivity.getPackageName(), "com.bashlock.apk.LauncherAlias");
            pm.setComponentEnabledSetting(
                    alias,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP
            );
            Log.i("BashLock_WebBridge", "Stealth Mode: Launcher icon hidden from customer.");
        } catch (Exception e) {
            Log.w("BashLock_WebBridge", "Failed to hide launcher icon: " + e.getMessage());
        }

        showNativeToast("Activating protection for code: " + trimmedCode);

        // Run background network sync
        new Thread(() -> {
            try {
                String serverUrl = resolveLiveServerUrl();

                // Call /api/pair (POST)
                try {
                    java.net.URL pairUrl = new java.net.URL(serverUrl + "/api/pair");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) pairUrl.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(7000);
                    conn.setReadTimeout(7000);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json");
                    org.json.JSONObject pairJson = new org.json.JSONObject();
                    pairJson.put("code", trimmedCode);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        byte[] input = pairJson.toString().getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception e) {
                    android.util.Log.e("WebBridge", "Pairing POST error: " + e.getMessage());
                }

                // Call /api/device/by-code/<code>/status (GET)
                try {
                    java.net.URL statusUrl = new java.net.URL(serverUrl + "/api/device/by-code/" + java.net.URLEncoder.encode(trimmedCode, "UTF-8") + "/status?t=" + System.currentTimeMillis());
                    java.net.HttpURLConnection statusConn = (java.net.HttpURLConnection) statusUrl.openConnection();
                    statusConn.setRequestMethod("GET");
                    statusConn.setConnectTimeout(7000);
                    statusConn.setReadTimeout(7000);
                    statusConn.setRequestProperty("User-Agent", "BashLock-WebBridge");

                    if (statusConn.getResponseCode() == java.net.HttpURLConnection.HTTP_OK) {
                        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(statusConn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(line);
                        }
                        reader.close();

                        org.json.JSONObject json = new org.json.JSONObject(sb.toString());
                        if (json.optBoolean("success", false)) {
                            org.json.JSONObject device = json.optJSONObject("device");
                            if (device != null) {
                                String pin = device.optString("offline_pin", "");
                                String dueDate = device.optString("next_due_date", "");
                                String deviceId = device.optString("id", "");
                                String status = device.optString("status", "ACTIVE");
                                boolean isLocked = "LOCKED".equalsIgnoreCase(status) || device.optBoolean("is_overdue", false);

                                syncBothPrefs(isLocked, code, pin, deviceId, dueDate);

                                if (isLocked) {
                                    mActivity.runOnUiThread(() -> mActivity.setKioskLockState(true));
                                } else {
                                    mActivity.runOnUiThread(() -> mActivity.setKioskLockState(false));
                                }
                            }
                        }
                    }
                    statusConn.disconnect();
                } catch (Exception e) {
                    android.util.Log.e("WebBridge", "Status GET error: " + e.getMessage());
                }

                // Finalize self-provisioning & engage stealth mode
                mActivity.runOnUiThread(() -> SelfProvisioningManager.finalizeSelfProvisioning(mActivity));

                // Restarts BashLockGuardService so polling starts immediately every 3 seconds
                android.content.Intent serviceIntent = new android.content.Intent(mActivity, BashLockGuardService.class);
                serviceIntent.putExtra("CHECK_NOW", true);
                serviceIntent.putExtra("RESTART_FAST_POLL", true);
                androidx.core.content.ContextCompat.startForegroundService(mActivity, serviceIntent);

            } catch (Exception e) {
                android.util.Log.e("WebBridge", "activateWithCode error: " + e.getMessage());
            }
        }).start();
    }

    private String resolveLiveServerUrl() {
        String base = mActivity.getServerBaseUrl();
        if (base != null && !base.isEmpty()) {
            base = base.replaceAll("/lock-app.*", "");
        }
        try {
            java.net.URL url = new java.net.URL(MainActivity.GITHUB_RAW_LIVE_URL + "?t=" + System.currentTimeMillis());
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            if (conn.getResponseCode() == java.net.HttpURLConnection.HTTP_OK) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    String fetched = line.trim().replaceAll("/lock-app.*", "");
                    if (fetched.startsWith("https://") && !fetched.contains("10.")) {
                        base = fetched;
                        mPrefs.edit().putString("cached_live_url", fetched + "/lock-app").apply();
                    }
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {}

        if (base == null || base.isEmpty()) {
            base = MainActivity.DEFAULT_PUBLIC_HTTPS_URL.replaceAll("/lock-app.*", "");
        }
        return base;
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
    public void openDeviceAdmin() {
        requestDeviceAdmin();
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
    public void openOverlayPermission() {
        requestOverlayPermission();
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
    public void openAccessibilityService() {
        requestAccessibilityService();
    }

    @JavascriptInterface
    public boolean isBatteryOptimizationIgnored() {
        try {
            return mActivity.isBatteryOptimizationIgnored();
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void requestBatteryOptimization() {
        try {
            mActivity.runOnUiThread(mActivity::promptBatteryOptimization);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void openBatteryOptimization() {
        requestBatteryOptimization();
    }

    @JavascriptInterface
    public boolean isDeviceOwner() {
        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) mActivity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm != null && dpm.isDeviceOwnerApp(mActivity.getPackageName());
    }

    @JavascriptInterface
    public String getSetupCode() {
        boolean isActivated = mPrefs.getBoolean("is_activated", false);
        if (!isActivated) return "";
        String code = mPrefs.getString("setup_code", mPrefs.getString("device_code", "")).trim();
        return "4819".equals(code) ? "" : code;
    }

    @JavascriptInterface
    public void resetPairingState() {
        Log.i("WebBridge", "resetPairingState called from WebView: wiping all pairing preferences");
        // 1. Wipe standard SharedPreferences
        mPrefs.edit()
                .putBoolean("is_released", false)
                .putBoolean("is_locked", false)
                .putBoolean("is_activated", false)
                .putString("device_code", "")
                .putString("setup_code", "")
                .putString("pairing_code", "")
                .putString("device_id", "")
                .putString("bashlock_offline_pin", "")
                .putString("offline_pin", "")
                .putString("due_date", "")
                .putString("monthly_installment", "")
                .putString("customer_name", "")
                .putString("phone_model", "")
                .putString("shop_name", "")
                .putString("shop_phone", "")
                .commit();

        // 2. Wipe DirectBoot / DeviceProtectedStorageContext SharedPreferences
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directBootContext = mActivity.createDeviceProtectedStorageContext();
                directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("is_released", false)
                        .putBoolean("is_locked", false)
                        .putBoolean("is_activated", false)
                        .putString("device_code", "")
                        .putString("setup_code", "")
                        .putString("pairing_code", "")
                        .commit();
            } catch (Exception ignored) {}
        }

        // 3. UI Thread: unlock kiosk & reload setup screen
        mActivity.runOnUiThread(() -> {
            try {
                mActivity.setKioskLockState(false);
                if (mActivity.getWebView() != null) {
                    mActivity.getWebView().evaluateJavascript(
                            "if(typeof resetToSetupMode==='function'){resetToSetupMode();}", null
                    );
                }
                showNativeToast("Pairing reset. Ready for new 4-digit code.");
            } catch (Exception e) {
                Log.e("WebBridge", "Error reloading setup view: " + e.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void resetPairing() {
        resetPairingState();
    }

    @JavascriptInterface
    public boolean isDeviceReleased() {
        boolean isActivated = mPrefs.getBoolean("is_activated", false);
        String code = mPrefs.getString("setup_code", mPrefs.getString("device_code", "")).trim();
        if (!isActivated || code.isEmpty() || "4819".equals(code)) {
            return false;
        }
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
    public String getServerBaseUrl() {
        return mActivity.getServerBaseUrl();
    }

    @JavascriptInterface
    public void loadRemoteUrl(String url) {
        mActivity.runOnUiThread(() -> mActivity.loadUrlExplicit(url));
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
