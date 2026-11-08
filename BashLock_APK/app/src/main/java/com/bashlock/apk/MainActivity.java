package com.bashlock.apk;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.lang.reflect.Method;

/**
 * MainActivity: Full-Screen Kiosk WebView Wrapper for BashLock.
 * 
 * Features:
 * 1) Loads the live public HTTPS /lock-app endpoint.
 * 2) Implements Android LockTask (Kiosk Mode) via startLockTask().
 * 3) Implements SYSTEM_ALERT_WINDOW floating overlay to block status bar & navigation.
 * 4) Suppresses Back, Home, and Recent Apps hardware buttons when locked.
 * 5) Enforces Device Administrator privileges to prevent unauthorized uninstallation.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "BashLock_Main";
    
    // Requirement 2: Public HTTPS link of /lock-app
    public static final String DEFAULT_PUBLIC_HTTPS_URL = "https://liberty-opposite-applying-www.trycloudflare.com/lock-app";

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private SwipeRefreshLayout mSwipeRefresh;
    private SharedPreferences mPrefs;
    private DevicePolicyManager mDpm;
    private ComponentName mAdminComponent;
    private WindowManager mWindowManager;
    private View mSystemAlertOverlayView;
    private boolean mIsLocked = false;
    private Handler mMainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make window fullscreen & keep screen awake
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences("BashLock_Config", MODE_PRIVATE);
        mDpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mAdminComponent = new ComponentName(this, BashLockDeviceAdminReceiver.class);
        mWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mMainHandler = new Handler(Looper.getMainLooper());

        mWebView = findViewById(R.id.webView);
        mProgressBar = findViewById(R.id.progressBar);
        mSwipeRefresh = findViewById(R.id.swipeRefresh);

        setupWebView();
        setupSwipeRefresh();

        // Check & prompt Device Administration & System Alert Window permissions
        promptDeviceAdmin();
        promptOverlayPermission();

        // Restore previous lock state if device was rebooted while locked
        mIsLocked = mPrefs.getBoolean("is_locked", false);
        if (mIsLocked) {
            setKioskLockState(true);
        }

        // Load the public HTTPS lock-app URL
        loadLockAppUrl();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersiveFullscreen();
        if (mIsLocked) {
            ensureLockTaskActive();
        }
    }

    /**
     * Requirement 1 & 3: Hide Android Status Bar and Navigation Bar (Immersive Sticky Mode)
     */
    private void applyImmersiveFullscreen() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveFullscreen();
        } else if (mIsLocked) {
            // Requirement 3: If user tries to pull down notifications or quick settings, collapse immediately
            collapseStatusBar();
            // Re-assert focus and bring activity back to top
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        }
    }

    private void collapseStatusBar() {
        try {
            @SuppressLint("WrongConstant") Object service = getSystemService("statusbar");
            Class<?> statusbarManager = Class.forName("android.app.StatusBarManager");
            Method collapse = statusbarManager.getMethod("collapsePanels");
            collapse.invoke(service);
        } catch (Exception ignored) {}
    }

    /**
     * Requirement 2: Configure accelerated WebView loading public HTTPS endpoint
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Enable hardware acceleration
        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Bind WebBridge interface for real-time two-way communication
        mWebView.addJavascriptInterface(new WebBridge(this), "BashLockNative");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mProgressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mProgressBar.setVisibility(View.GONE);
                mSwipeRefresh.setRefreshing(false);
                // Inject auto-detection script to notify native layer of current lock state
                injectStateObserver();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mProgressBar.setVisibility(View.GONE);
                    mSwipeRefresh.setRefreshing(false);
                    Toast.makeText(MainActivity.this, "Connecting to BashLock Cloud...", Toast.LENGTH_SHORT).show();
                }
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mProgressBar.setProgress(newProgress);
            }
        });
    }

    private void setupSwipeRefresh() {
        mSwipeRefresh.setOnRefreshListener(() -> {
            if (!mIsLocked) {
                mWebView.reload();
            } else {
                mSwipeRefresh.setRefreshing(false);
            }
        });
    }

    private void loadLockAppUrl() {
        String savedCode = mPrefs.getString("setup_code", "").trim();
        String url = DEFAULT_PUBLIC_HTTPS_URL;
        if (!savedCode.isEmpty()) {
            url += "/" + savedCode;
        }
        Log.i(TAG, "Loading lock-app URL: " + url);
        mWebView.loadUrl(url);
    }

    private void injectStateObserver() {
        // Monitors DOM mutations or status flips in lock_app.html
        String js = "(function() {"
                + "  setInterval(function() {"
                + "    var isLocked = typeof isLockedState !== 'undefined' ? isLockedState : (document.getElementById('view-locked') && !document.getElementById('view-locked').classList.contains('hidden'));"
                + "    if (window.BashLockNative) {"
                + "      window.BashLockNative.setLockState(!!isLocked);"
                + "    }"
                + "  }, 2000);"
                + "})();";
        mWebView.evaluateJavascript(js, null);
    }

    /**
     * Requirement 3: Kiosk Mode / LockTask & SYSTEM_ALERT_WINDOW Enforcement
     */
    public void setKioskLockState(boolean isLocked) {
        this.mIsLocked = isLocked;
        mPrefs.edit().putBoolean("is_locked", isLocked).apply();

        if (isLocked) {
            Log.w(TAG, "LOCK ACTIVATED: Engaging startLockTask() and System Overlay Guard");
            ensureLockTaskActive();
            showSystemAlertOverlay();
            mSwipeRefresh.setEnabled(false);
        } else {
            Log.i(TAG, "LOCK DEACTIVATED: Disengaging startLockTask() and removing Overlay");
            stopKioskLockTask();
            removeSystemAlertOverlay();
            mSwipeRefresh.setEnabled(true);
        }
    }

    private void ensureLockTaskActive() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
                    startLockTask();
                }
            } else {
                startLockTask();
            }
        } catch (Exception e) {
            Log.w(TAG, "startLockTask warning: " + e.getMessage());
        }
    }

    private void stopKioskLockTask() {
        try {
            stopLockTask();
        } catch (Exception ignored) {}
    }

    /**
     * Requirement 3: SYSTEM_ALERT_WINDOW floating invisible bar covering top status shade
     */
    private void showSystemAlertOverlay() {
        if (!hasOverlayPermission() || mSystemAlertOverlayView != null) {
            return;
        }

        try {
            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    getStatusBarHeight() + 20,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP;

            FrameLayout overlay = new FrameLayout(this);
            overlay.setBackgroundColor(Color.TRANSPARENT);
            overlay.setOnTouchListener((v, event) -> {
                collapseStatusBar();
                return true; // Intercept touches to prevent status bar pulling
            });

            mSystemAlertOverlayView = overlay;
            mWindowManager.addView(mSystemAlertOverlayView, params);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add system alert overlay: " + e.getMessage());
        }
    }

    private void removeSystemAlertOverlay() {
        if (mSystemAlertOverlayView != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mSystemAlertOverlayView);
            } catch (Exception ignored) {}
            mSystemAlertOverlayView = null;
        }
    }

    private int getStatusBarHeight() {
        int result = 50;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }

    /**
     * Requirement 4: Device Administrator Management
     */
    public boolean isDeviceAdminActive() {
        return mDpm != null && mDpm.isAdminActive(mAdminComponent);
    }

    public void promptDeviceAdmin() {
        if (mDpm != null && !mDpm.isAdminActive(mAdminComponent)) {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mAdminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "BashLock Device Administrator policy prevents unauthorized uninstallation under installment terms.");
            startActivity(intent);
        }
    }

    public boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    public void promptOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    // ---------------- Hardware Button Suppression (Requirement 3) ---------------- //

    @Override
    public void onBackPressed() {
        if (mIsLocked) {
            // Completely disable Back button when locked
            Toast.makeText(this, "Device is locked under installment policy. Enter Master PIN.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mIsLocked) {
            // Block Home, Recent Apps, Volume keys from escaping lock
            if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
                collapseStatusBar();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        removeSystemAlertOverlay();
        super.onDestroy();
    }
}
