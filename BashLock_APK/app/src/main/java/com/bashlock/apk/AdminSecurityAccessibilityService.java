package com.bashlock.apk;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * AdminSecurityAccessibilityService:
 * Real-time event-driven guard that detects when the user navigates to
 * Device Admin Settings or attempts to deactivate/uninstall BashLock.
 *
 * If the shopkeeper is not authorized via Master PIN, the service:
 * 1. Instantly kicks the user out using GLOBAL_ACTION_HOME.
 * 2. Overlays the AdminAuthActivity password screen.
 */
public class AdminSecurityAccessibilityService extends AccessibilityService {
    private static final String TAG = "BashLock_SecService";

    // Common Settings and Package Installer packages across Android OEMs (Samsung, Xiaomi, Vivo, Oppo, Stock)
    private static final String PKG_SETTINGS = "com.android.settings";
    private static final String PKG_PKG_INSTALLER_GOOGLE = "com.google.android.packageinstaller";
    private static final String PKG_PKG_INSTALLER = "com.android.packageinstaller";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String packageName = pkg.toString();

        // Only inspect Settings and Package Manager activities
        if (!packageName.equals(PKG_SETTINGS)
                && !packageName.equals(PKG_PKG_INSTALLER)
                && !packageName.equals(PKG_PKG_INSTALLER_GOOGLE)) {
            return;
        }

        // If the admin already authenticated in the last 60 seconds, allow access
        if (AdminSecurityManager.isAuthorized()) {
            return;
        }

        CharSequence cls = event.getClassName();
        String className = cls != null ? cls.toString().toLowerCase() : "";

        boolean isDeviceAdminScreen = className.contains("deviceadmin")
                || className.contains("specialaccess")
                || className.contains("uninstall");

        if (!isDeviceAdminScreen) {
            // Also inspect node hierarchy for relevant trigger keywords
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                isDeviceAdminScreen = hasTargetAdminKeywords(root);
                root.recycle();
            }
        }

        if (isDeviceAdminScreen) {
            Log.w(TAG, "Unauthorized attempt to access Device Admin settings detected!");
            blockAndPromptPin();
        }
    }

    private boolean hasTargetAdminKeywords(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // Check if BashLock specifically is mentioned on a settings / uninstall screen
        List<AccessibilityNodeInfo> bashlockNodes = node.findAccessibilityNodeInfosByText("BashLock");
        if (bashlockNodes != null && !bashlockNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : bashlockNodes) {
                n.recycle();
            }
            return true;
        }

        // Check for general Device Admin / Deactivate triggers
        List<AccessibilityNodeInfo> adminNodes = node.findAccessibilityNodeInfosByText("Device admin");
        if (adminNodes != null && !adminNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : adminNodes) {
                n.recycle();
            }
            return true;
        }

        List<AccessibilityNodeInfo> deactNodes = node.findAccessibilityNodeInfosByText("Deactivate");
        if (deactNodes != null && !deactNodes.isEmpty()) {
            for (AccessibilityNodeInfo n : deactNodes) {
                n.recycle();
            }
            return true;
        }

        return false;
    }

    private void blockAndPromptPin() {
        // 1. Kick user out of Settings back to Home
        performGlobalAction(GLOBAL_ACTION_HOME);

        // 2. Launch Master PIN verification screen on top
        Intent authIntent = new Intent(this, AdminAuthActivity.class);
        authIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        startActivity(authIntent);
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
