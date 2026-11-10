package com.bashlock.apk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

/**
 * AdminSecurityAccessibilityService:
 * Real-time event-driven guard that detects when the user navigates to
 * Settings -> Apps -> BashLock, or Device Admin Settings, or attempts
 * to deactivate, force stop, clear data, or uninstall BashLock.
 *
 * If the shopkeeper is not authorized via Master PIN, the service:
 * 1. Instantly navigates back (GLOBAL_ACTION_BACK) and exits to home (GLOBAL_ACTION_HOME).
 * 2. Overlays the lock screen and triggers the persistent WindowManager guard.
 */
public class AdminSecurityAccessibilityService extends AccessibilityService {
    private static final String TAG = "BashLock_SecService";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        SharedPreferences prefs = getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
        if (prefs.getBoolean("is_released", false)) {
            return; // Device released on installment clearance; do not block
        }

        // If the shopkeeper authenticated in the last 60 seconds, allow access
        if (AdminSecurityManager.isAuthorized()) {
            return;
        }

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String packageName = pkg.toString().toLowerCase();

        // Never intercept or block our own app!
        if (packageName.startsWith("com.bashlock.") || packageName.contains("bashlock")) {
            return;
        }

        // 1. NEVER Lock During Setup (Before Pairing):
        // If "is_activated" is false or pairing code is empty / default "4819", NEVER block or lock!
        boolean isActivated = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                isActivated = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .getBoolean("is_activated", false);
            } catch (Exception ignored) {}
        }
        if (!isActivated) {
            isActivated = prefs.getBoolean("is_activated", false);
        }

        String setupCode = "";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                setupCode = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .getString("setup_code", directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE).getString("device_code", "")).trim();
            } catch (Exception ignored) {}
        }
        if (setupCode.isEmpty()) {
            setupCode = prefs.getString("setup_code", prefs.getString("device_code", "")).trim();
        }

        // While the shopkeeper is turning on permissions (Device Admin, Overlay, Accessibility, Battery),
        // NEVER show lock overlay and NEVER block Settings if "is_activated" is false or code is empty/default.
        if (!isActivated || setupCode.isEmpty() || "4819".equals(setupCode)) {
            return;
        }

        // 2. Check if device is in LOCKED state (Direct Boot + Normal prefs)
        boolean isLocked = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                SharedPreferences directPrefs = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE);
                isLocked = directPrefs.getBoolean("is_locked", false);
            } catch (Exception ignored) {}
        }
        if (!isLocked) {
            isLocked = prefs.getBoolean("is_locked", false);
        }

        // 2. Requirement 3: Continuous Watchdog in AccessibilityService
        // If the device is locked and user navigates outside BashLock (to Launcher, YouTube, WhatsApp, etc.),
        // immediately draw WindowManager overlay, dismiss notification/power menu, and force lock screen!
        if (isLocked) {
            boolean isBashLockApp = packageName.startsWith("com.bashlock.");
            boolean isInputMethod = packageName.contains("inputmethod") || packageName.contains("ime") || packageName.contains("keyboard");
            if (!isBashLockApp && !isInputMethod) {
                Log.w(TAG, "Device is in LOCKED state! Blocking unauthorized window: " + packageName);
                if (packageName.contains("systemui")) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        try {
                            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                        } catch (Exception ignored) {}
                    }
                }
                performGlobalAction(GLOBAL_ACTION_HOME);
                performGlobalAction(GLOBAL_ACTION_BACK);
                launchLockActivity();
                return;
            }
        }

        // Check if package is a Settings, Package Installer, Google Play, Launcher dialog, or Security Center app
        boolean isSettingsOrManager = packageName.contains("settings")
                || packageName.contains("packageinstaller")
                || packageName.contains("permissioncontroller")
                || packageName.contains("vending")
                || packageName.contains("vivo")
                || packageName.contains("security")
                || packageName.contains("safecenter")
                || packageName.contains("permissionmanager")
                || packageName.contains("phonemanager")
                || packageName.contains("samsung")
                || packageName.contains("huawei")
                || packageName.contains("oppo")
                || packageName.contains("coloros")
                || packageName.contains("miui")
                || packageName.contains("transsion")
                || packageName.contains("infinix")
                || packageName.contains("tecno")
                || packageName.contains("itel")
                || packageName.contains("realme")
                || packageName.contains("xiaomi")
                || packageName.contains("launcher")
                || packageName.contains("systemui");

        if (!isSettingsOrManager) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean isBlockedScreen = false;
        if (root != null) {
            isBlockedScreen = scanNodeRecursively(root);
            root.recycle();
        }
        if (!isBlockedScreen) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                isBlockedScreen = scanNodeRecursively(source);
                source.recycle();
            }
        }
        if (!isBlockedScreen && event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) {
                    String text = cs.toString().toLowerCase();
                    if (text.contains("bashlock") || text.contains("uninstall") || text.contains("clear data") || text.contains("force stop")) {
                        isBlockedScreen = true;
                        break;
                    }
                }
            }
        }

        if (isBlockedScreen) {
            Log.w(TAG, "Unauthorized attempt to access Reset, Developer Options, or BashLock Admin settings! Blocking immediately.");
            blockAndPromptPin();
        }
    }

    private boolean scanNodeRecursively(AccessibilityNodeInfo node) {
        if (node == null) return false;

        CharSequence textSeq = node.getText();
        CharSequence descSeq = node.getContentDescription();
        CharSequence viewIdSeq = node.getViewIdResourceName();

        String combined = ((textSeq != null ? textSeq : "") + " "
                + (descSeq != null ? descSeq : "") + " "
                + (viewIdSeq != null ? viewIdSeq : "")).toLowerCase();

        // If this is an Uninstall confirmation dialog, auto-click Cancel!
        if (combined.contains("uninstall") && (combined.contains("cancel") || combined.contains("ok"))) {
            try {
                java.util.List<AccessibilityNodeInfo> cancels = node.findAccessibilityNodeInfosByText("Cancel");
                if (cancels != null) {
                    for (AccessibilityNodeInfo c : cancels) {
                        c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        c.recycle();
                    }
                }
            } catch (Exception ignored) {}
        }

        // 1. Detect any attempt to view BashLock App Info in Settings, or uninstall / clear data (Commercial Anti-Tamper)
        boolean isTamperOrResetScreen = combined.contains("bashlock")
                || combined.contains("com.bashlock")
                || combined.contains("clear data")
                || combined.contains("clear storage")
                || combined.contains("clear all data")
                || combined.contains("delete all data")
                || combined.contains("clear cache")
                || combined.contains("storage & cache")
                || combined.contains("manage space")
                || combined.contains("force stop")
                || combined.contains("force quit")
                || combined.contains("deactivate")
                || combined.contains("device admin")
                || combined.contains("device administrator")
                || combined.contains("uninstall")
                || combined.contains("factory data reset")
                || combined.contains("erase all data")
                || combined.contains("backup & reset")
                || combined.contains("backup and reset")
                || combined.contains("factory reset")
                || combined.contains("reset phone")
                || combined.contains("wipe data")
                || combined.contains("format phone")
                || combined.contains("reset options")
                || combined.contains("master clear")
                || combined.contains("hard reset")
                || combined.contains("wipe all data")
                || combined.contains("developer options")
                || combined.contains("usb debugging")
                || combined.contains("wireless debugging")
                || combined.contains("oem unlocking");

        if (isTamperOrResetScreen) {
            Log.w(TAG, "Blocked security/tamper/reset keyword detected: " + combined);
            return true;
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = scanNodeRecursively(child);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    private void blockAndPromptPin() {
        Log.w(TAG, "TAMPER ATTEMPT BLOCKED! Kicking out of Settings & Launching Master PIN prompt.");
        // 1. Instantly navigate back and exit to Home
        try {
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Exception ignored) {}

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK);
                performGlobalAction(GLOBAL_ACTION_HOME);
            } catch (Exception ignored) {}
        }, 80);

        // 2. Launch AdminAuthActivity (Shopkeeper PIN required to access app details, uninstall, or settings!)
        try {
            Intent authIntent = new Intent(this, AdminAuthActivity.class);
            authIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            startActivity(authIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch AdminAuthActivity: " + e.getMessage());
        }

        try {
            Toast.makeText(getApplicationContext(), "⚠️ Action Restricted: Settings modification is disabled under Installment agreement.", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    // Requirement 2 & 3: Bypass Android 10+ background activity restrictions via Accessibility & WindowManager
    private void launchLockActivity() {
        // 1. Immediately ensure persistent Foreground LockService draws the un-dismissible WindowManager overlay view
        try {
            Intent serviceIntent = new Intent(this, BashLockGuardService.class);
            serviceIntent.putExtra("FORCE_GUARD_OVERLAY", true);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start GuardService from AccessibilityService: " + e.getMessage());
        }

        // 2. Launch MainActivity with FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP
        // (Accessibility services are explicitly exempt from Android 10+ background activity start restrictions)
        try {
            Intent lockIntent = new Intent(this, MainActivity.class);
            lockIntent.setAction("com.bashlock.apk.FORCE_LOCK");
            lockIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
            );
            lockIntent.putExtra("FORCE_KIOSK_LOCK", true);
            startActivity(lockIntent);
        } catch (Exception e) {
            Log.e(TAG, "launchLockActivity startActivity error: " + e.getMessage());
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "AdminSecurityAccessibilityService interrupted");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "AdminSecurityAccessibilityService connected & guarding Device Admin privileges");

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);

        // Requirement 2: Check lock state on service connect (boot) and attach WindowManager overlay view
        boolean isLocked = false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Context directBootContext = createDeviceProtectedStorageContext();
                isLocked = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .getBoolean("is_locked", false);
            }
        } catch (Exception ignored) {}
        if (!isLocked) {
            SharedPreferences normalPrefs = getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
            isLocked = normalPrefs.getBoolean("is_locked", false);
        }
        if (isLocked) {
            launchLockActivity();
        }
    }
}
