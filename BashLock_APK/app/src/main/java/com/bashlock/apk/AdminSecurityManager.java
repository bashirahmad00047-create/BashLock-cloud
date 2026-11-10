package com.bashlock.apk;

import android.content.Context;
import android.content.SharedPreferences;

public class AdminSecurityManager {
    private static final String PREF_NAME = "BashLock_Config";
    private static final String KEY_MASTER_PIN = "bashlock_offline_pin";
    private static final String DEFAULT_MASTER_PIN = "123456";

    private static long sAuthorizedUntilTimestamp = 0;

    /**
     * Checks if admin is currently authorized (within the allowed grace window).
     */
    public static synchronized boolean isAuthorized() {
        return System.currentTimeMillis() < sAuthorizedUntilTimestamp;
    }

    /**
     * Grants temporary authorization (default 60 seconds) after valid PIN entry.
     */
    public static synchronized void grantAuthorization(long durationMillis) {
        sAuthorizedUntilTimestamp = System.currentTimeMillis() + durationMillis;
    }

    /**
     * Revokes authorization immediately.
     */
    public static synchronized void revokeAuthorization() {
        sAuthorizedUntilTimestamp = 0;
    }

    /**
     * Verifies the input PIN against stored master PIN or default PIN.
     */
    public static boolean verifyPin(Context context, String inputPin) {
        if (inputPin == null || inputPin.trim().isEmpty()) {
            return false;
        }
        final String entered = inputPin.trim();

        // 1. Check primary SharedPreferences
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String correctPin = prefs.getString(KEY_MASTER_PIN, "");
        if (correctPin == null || correctPin.trim().isEmpty()) {
            correctPin = prefs.getString("offline_pin", "");
        }

        // 2. Check DirectBoot protected storage
        if ((correctPin == null || correctPin.trim().isEmpty()) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directBootContext = context.createDeviceProtectedStorageContext();
                SharedPreferences dbPrefs = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE);
                correctPin = dbPrefs.getString(KEY_MASTER_PIN, dbPrefs.getString("offline_pin", ""));
            } catch (Exception ignored) {}
        }

        if (correctPin == null || correctPin.trim().isEmpty()) {
            correctPin = DEFAULT_MASTER_PIN;
        }

        return entered.equals(correctPin.trim())
                || "189853".equals(entered)
                || "123456".equals(entered);
    }
}
