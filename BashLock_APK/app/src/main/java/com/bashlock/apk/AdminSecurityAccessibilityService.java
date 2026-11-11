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

import androidx.core.content.ContextCompat;

/**
 * AdminSecurityAccessibilityService:
 * Real-time event-driven guard that detects when the user navigates to
 * Settings -> Apps -> BashLock, or Device Admin Settings, or attempts
 * to deactivate, force stop, clear data, or uninstall BashLock, or access Power/Restart dialog.
 */
public class AdminSecurityAccessibilityService extends AccessibilityService {
    private static final String TAG = "BashLock_SecService";

    private void ensureGuardServiceRunning() {
        try {
            Intent serviceIntent = new Intent(this, BashLockGuardService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // Requirement 4: Ensure guard service is continuously running
        ensureGuardServiceRunning();

        // Check is_activated strictly from DeviceProtectedStorageContext first
        boolean isActivated = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                isActivated = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .getBoolean("is_activated", false);
            } catch (Exception ignored) {}
        }
        if (!isActivated) {
            SharedPreferences prefs = getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
            isActivated = prefs.getBoolean("is_activated", false);
        }

        // Only enforce when device is activated by shopkeeper
        if (!isActivated) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences("BashLock_Config", Context.MODE_PRIVATE);
        boolean isLockedState = false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                isLockedState = createDeviceProtectedStorageContext()
                        .getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .getBoolean("is_locked", false);
            }
        } catch (Exception ignored) {}
        if (!isLockedState) {
            isLockedState = prefs.getBoolean("is_locked", false);
        }

        if (prefs.getBoolean("is_released", false) && !isLockedState) {
            return; // Device released on installment clearance (NOC); do not block
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

        boolean isLocked = false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                isLocked = directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", Context.MODE_PRIVATE)
                        .getBoolean("is_locked", false);
            } catch (Exception ignored) {}
        }
        if (!isLocked) {
            isLocked = prefs.getBoolean("is_locked", false);
        }

        // 2.a) Block Power Menu / Restart Dialog:
        // Intercept the system Power Menu ("Power off", "Restart", "Reboot") and immediately dismiss it with GLOBAL_ACTION_BACK and GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
        if (isLocked) {
            String windowText = getWindowText(event);
            String fullContent = getFullWindowContent(event);
            if (containsAny(windowText, "power off", "restart", "reboot", "emergency", "بند کریں", "ری سٹارٹ")
                    || containsAny(fullContent, "power off", "restart", "reboot", "بند کریں", "ری سٹارٹ")) {
                if (packageName.equals("android") || packageName.contains("systemui") || packageName.contains("power")) {
                    Log.w(TAG, "Blocked Power/Restart menu while locked: " + windowText);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        try {
                            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                        } catch (Exception ignored) {}
                    }
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    launchLockActivity();
                    return;
                }
            }
        }

        // If the device is locked and user navigates outside BashLock (Launcher, YouTube, WhatsApp, Settings, etc.):
        if (isLocked) {
            boolean isInputMethod = packageName.contains("inputmethod") || packageName.contains("ime") || packageName.contains("keyboard");
            if (!isInputMethod) {
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

        // 2.b) Block App Uninstall / App Info / Clear Data / Device Admin / Factory Reset
        // Monitors ALL system packages (Settings, Package Installer, Security Centers, Play Store)
        boolean isTamperPackage = packageName.contains("settings")
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
                || packageName.contains("launcher");

        if (!isTamperPackage) {
            return;
        }

        String fullContent = getFullWindowContent(event);
        boolean mentionsBashLock = fullContent.contains("bashlock") || fullContent.contains("com.bashlock");
        boolean mentionsTamperAction = containsAny(fullContent,
                "uninstall", "force stop", "clear data", "clear storage", "storage", "deactivate",
                "device admin", "erase all data", "factory data reset", "reset options", "reset", "accessibility",
                "app info", "turn off", "disable", "stop", "manage device admin",
                "ان انسٹال", "فورس اسٹاپ", "ڈیلیٹ", "غیر فعال", "فیکٹری ری سیٹ");

        boolean isFactoryReset = containsAny(fullContent,
                "erase all data", "factory data reset", "factory reset", "فیکٹری ری سیٹ");

        if ((mentionsBashLock && mentionsTamperAction) || isFactoryReset) {
            Log.w(TAG, "Security Policy: Tamper attempt detected on BashLock! Action: " + fullContent);
            blockTamperAttempt();
        }
    }

    private void blockTamperAttempt() {
        // Immediately execute performGlobalAction(GLOBAL_ACTION_BACK) followed by GLOBAL_ACTION_HOME within 50ms
        try {
            performGlobalAction(GLOBAL_ACTION_BACK);
        } catch (Exception ignored) {}

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                performGlobalAction(GLOBAL_ACTION_HOME);
            } catch (Exception ignored) {}
        }, 50);

        try {
            Intent lockIntent = new Intent(this, MainActivity.class);
            lockIntent.setAction("com.bashlock.apk.FORCE_LOCK");
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(lockIntent);
        } catch (Exception e) {
            Log.e(TAG, "blockTamperAttempt startActivity: " + e.getMessage());
        }

        try {
            Toast.makeText(getApplicationContext(), "Protected by Shopkeeper", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
    }

    private boolean containsAny(String source, String... targets) {
        if (source == null) return false;
        String lower = source.toLowerCase();
        for (String t : targets) {
            if (lower.contains(t.toLowerCase())) return true;
        }
        return false;
    }

    private String getWindowText(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) sb.append(cs).append(" ");
            }
        }
        AccessibilityNodeInfo source = event.getSource();
        if (source != null) {
            collectTextRecursively(source, sb);
            source.recycle();
        }
        return sb.toString();
    }

    private String getFullWindowContent(AccessibilityEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence cs : event.getText()) {
                if (cs != null) sb.append(cs).append(" ");
            }
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            collectTextRecursively(root, sb);
            root.recycle();
        } else {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                collectTextRecursively(src, sb);
                src.recycle();
            }
        }
        return sb.toString().toLowerCase();
    }

    private void collectTextRecursively(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        CharSequence txt = node.getText();
        if (txt != null) sb.append(txt).append(" ");
        CharSequence desc = node.getContentDescription();
        if (desc != null) sb.append(desc).append(" ");
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTextRecursively(child, sb);
                child.recycle();
            }
        }
    }

    private void launchLockActivity() {
        try {
            Intent serviceIntent = new Intent(this, BashLockGuardService.class);
            serviceIntent.putExtra("FORCE_GUARD_OVERLAY", true);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start GuardService from AccessibilityService: " + e.getMessage());
        }

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

        // Requirement 4: Ensure guard service is running
        ensureGuardServiceRunning();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);

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
