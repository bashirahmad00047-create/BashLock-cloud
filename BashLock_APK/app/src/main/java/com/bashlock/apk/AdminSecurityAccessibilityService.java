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

        // Check if package is a Settings, Package Installer, or Security Center app
        boolean isSettingsOrManager = packageName.contains("settings")
                || packageName.contains("packageinstaller")
                || packageName.contains("permissioncontroller")
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
                || packageName.contains("systemui");

        if (!isSettingsOrManager) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            boolean isBlockedScreen = scanNodeRecursively(root);
            root.recycle();
            if (isBlockedScreen) {
                Log.w(TAG, "Unauthorized attempt to access Reset, Developer Options, or BashLock Admin settings! Blocking immediately.");
                blockAndPromptPin();
            }
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

        // 1. Detect any Factory Reset / Backup & Reset / Erase All Data / Settings Tamper attempt (Reset-Proof)
        boolean isTamperOrResetScreen = combined.contains("factory data reset")
                || combined.contains("erase all data")
                || combined.contains("backup & reset")
                || combined.contains("backup and reset")
                || combined.contains("device admin")
                || combined.contains("force stop")
                || combined.contains("uninstall")
                || combined.contains("bashlock")
                || combined.contains("factory reset")
                || combined.contains("reset phone")
                || combined.contains("wipe data")
                || combined.contains("format phone")
                || combined.contains("reset options")
                || combined.contains("delete all data")
                || combined.contains("master clear")
                || combined.contains("hard reset")
                || combined.contains("wipe all data")
                || combined.contains("developer options")
                || combined.contains("usb debugging")
                || combined.contains("wireless debugging")
                || combined.contains("oem unlocking")
                || combined.contains("deactivate");

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
        // 1. Instantly navigate back and exit to Home
        try {
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Exception ignored) {}

        // Secondary Home at 100ms interval to override OEM animations
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK);
                performGlobalAction(GLOBAL_ACTION_HOME);
            } catch (Exception ignored) {}
        }, 100);

        // 2. Trigger Guard Service overlay immediately
        try {
            Intent guardIntent = new Intent(this, BashLockGuardService.class);
            guardIntent.putExtra("FORCE_GUARD_OVERLAY", true);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(guardIntent);
            } else {
                startService(guardIntent);
            }
        } catch (Exception ignored) {}

        // 3. Launch MainActivity if locked
        boolean isLocked = false;
        try {
            SharedPreferences normalPrefs = getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
            isLocked = normalPrefs.getBoolean("is_locked", false);
            if (!isLocked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                Context directBootContext = createDeviceProtectedStorageContext();
                isLocked = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .getBoolean("is_locked", false);
            }
        } catch (Exception ignored) {}

        if (isLocked) {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.setAction("com.bashlock.apk.FORCE_LOCK");
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mainIntent.putExtra("FORCE_KIOSK_LOCK", true);
            try {
                startActivity(mainIntent);
            } catch (Exception ignored) {}
        }

        try {
            Toast.makeText(getApplicationContext(), "⚠️ BashLock Security: Factory Reset, Uninstall, and Settings modification are Disabled under Installment terms.", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
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
    }
}
