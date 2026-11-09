package com.bashlock.apk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * AdminSecurityAccessibilityService:
 * Real-time event-driven guard that detects when the user navigates to
 * Settings -> Apps -> BashLock, or Device Admin Settings, or attempts
 * to deactivate, force stop, clear data, or uninstall BashLock.
 *
 * If the shopkeeper is not authorized via Master PIN, the service:
 * 1. Instantly navigates back (GLOBAL_ACTION_BACK) and exits to home (GLOBAL_ACTION_HOME).
 * 2. Overlays the AdminAuthActivity password screen.
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
                || packageName.contains("security")
                || packageName.contains("safecenter")
                || packageName.contains("permissionmanager")
                || packageName.contains("phonemanager");

        if (!isSettingsOrManager) {
            return;
        }

        CharSequence cls = event.getClassName();
        String className = cls != null ? cls.toString().toLowerCase() : "";

        boolean isBlockedScreen = false;

        // 1. Direct class check for Device Admin or App Details
        if (className.contains("deviceadmin")
                || className.contains("specialaccess")
                || className.contains("installedappdetails")
                || className.contains("appinfo")
                || className.contains("uninstall")
                || className.contains("applications")) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                isBlockedScreen = hasBashLockOrAdminTarget(root);
                root.recycle();
            } else {
                isBlockedScreen = className.contains("deviceadmin");
            }
        } else {
            // 2. Full hierarchy inspection for trigger keywords
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                isBlockedScreen = hasBashLockOrAdminTarget(root);
                root.recycle();
            }
        }

        if (isBlockedScreen) {
            Log.w(TAG, "Unauthorized attempt to access BashLock App Info or Device Admin settings! Blocking immediately.");
            blockAndPromptPin();
        }
    }

    private boolean hasBashLockOrAdminTarget(AccessibilityNodeInfo root) {
        if (root == null) return false;

        boolean hasBashLockText = hasTextIgnoreCase(root, "BashLock") || hasTextIgnoreCase(root, "com.bashlock.apk");

        // If user is inside BashLock app info screen (Settings -> Apps -> BashLock)
        if (hasBashLockText) {
            return true;
        }

        // If on Device Admin deactivation screen
        if (hasTextIgnoreCase(root, "Deactivate this device admin app")
                || hasTextIgnoreCase(root, "Deactivate device admin")
                || (hasTextIgnoreCase(root, "Deactivate") && hasTextIgnoreCase(root, "admin"))) {
            return true;
        }

        // If on uninstall confirmation dialog
        if (hasTextIgnoreCase(root, "Do you want to uninstall this app?")
                || hasTextIgnoreCase(root, "Uninstall app")
                || (hasTextIgnoreCase(root, "Uninstall") && hasBashLockText)) {
            return true;
        }

        // Check if general Device admin listing contains BashLock
        List<AccessibilityNodeInfo> adminNodes = root.findAccessibilityNodeInfosByText("Device admin");
        if (adminNodes != null && !adminNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : adminNodes) {
                n.recycle();
            }
            if (hasBashLockText) return true;
        }

        return false;
    }

    private boolean hasTextIgnoreCase(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        List<AccessibilityNodeInfo> list = node.findAccessibilityNodeInfosByText(text);
        if (list != null && !list.isEmpty()) {
            for (AccessibilityNodeInfo n : list) {
                n.recycle();
            }
            return true;
        }
        return false;
    }

    private void blockAndPromptPin() {
        // 1. Instantly navigate back and exit to Home
        try {
            performGlobalAction(GLOBAL_ACTION_BACK);
            performGlobalAction(GLOBAL_ACTION_HOME);
        } catch (Exception ignored) {}

        // 2. Launch MainActivity to foreground
        Intent mainIntent = new Intent(this, MainActivity.class);
        mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(mainIntent);
        } catch (Exception ignored) {}

        // 3. Launch Master PIN verification screen on top
        Intent authIntent = new Intent(this, AdminAuthActivity.class);
        authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            startActivity(authIntent);
        } catch (Exception ignored) {}

        try {
            Toast.makeText(getApplicationContext(), "⚠️ BashLock Security: Uninstallation is Protected under Installment terms.", Toast.LENGTH_SHORT).show();
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
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }
}
