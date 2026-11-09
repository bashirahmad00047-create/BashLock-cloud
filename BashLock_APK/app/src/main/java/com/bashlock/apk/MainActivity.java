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

import android.os.PowerManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

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
    
    // Dynamic GitHub Remote Config URL - Allows 1-time permanent APK install!
    public static final String GITHUB_RAW_LIVE_URL = "https://raw.githubusercontent.com/bashirahmad00047-create/BashLock-cloud/main/live_url.txt";
    public static final String DEFAULT_PUBLIC_HTTPS_URL = "https://uniform-platform-terms-borders.trycloudflare.com/lock-app";

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
    private boolean mPageLoadedSuccessfully = false;
    private boolean mIsResolvingUrl = false;

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

        // Check & prompt Device Administration, System Alert Window, & Accessibility permissions
        promptDeviceAdmin();
        promptOverlayPermission();
        promptAccessibilityService();
        promptBatteryOptimization();

        // Enforce Device Owner policies (setUninstallBlocked and LockTask) if provisioned
        BashLockDeviceAdminReceiver.configureDeviceOwnerPolicies(this);

        // Start 24/7 Persistent Background Security Guard Service
        startGuardService();

        // Restore previous lock state if device was rebooted while locked
        mIsLocked = mPrefs.getBoolean("is_locked", false);
        if (mIsLocked) {
            setKioskLockState(true);
        }

        handleIncomingIntent(getIntent());

        // Load the public HTTPS lock-app URL
        loadLockAppUrl();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BashLockDeviceAdminReceiver.configureDeviceOwnerPolicies(this);
        applyImmersiveFullscreen();
        if (mIsLocked) {
            ensureLockTaskActive();
        }
        // If coming back from Device Admin or permission screen and page hasn't loaded yet, reload
        if (!mPageLoadedSuccessfully && mWebView != null) {
            loadLockAppUrl();
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
                mPageLoadedSuccessfully = true;
                // Inject auto-detection script to notify native layer of current lock state
                injectStateObserver();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mProgressBar.setVisibility(View.GONE);
                    mSwipeRefresh.setRefreshing(false);
                    // Check if tunnel URL updated on GitHub
                    fetchLiveUrlFromGitHub(true);
                    Toast.makeText(MainActivity.this, "Connecting to BashLock Cloud...", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 500) {
                    Log.w(TAG, "Cloudflare Error " + errorResponse.getStatusCode() + "; checking GitHub for updated link");
                    mProgressBar.setVisibility(View.GONE);
                    mSwipeRefresh.setRefreshing(false);
                    fetchLiveUrlFromGitHub(true);
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
                fetchLiveUrlFromGitHub(true);
                mWebView.reload();
            } else {
                mSwipeRefresh.setRefreshing(false);
            }
        });
    }

    private String getActiveBaseUrl() {
        String cached = mPrefs.getString("cached_live_url", "").trim();
        if (cached.startsWith("https://") && !cached.contains("10.")) {
            return cached;
        }
        return DEFAULT_PUBLIC_HTTPS_URL;
    }

    private void loadLockAppUrl() {
        // 1. Immediately load currently saved / default URL (no blank screen lag)
        loadUrlInternal(getActiveBaseUrl());
        // 2. Concurrently fetch the latest live URL from GitHub raw
        fetchLiveUrlFromGitHub(false);
    }

    private void fetchLiveUrlFromGitHub(boolean forceReloadOnSuccess) {
        if (mIsResolvingUrl) return;
        mIsResolvingUrl = true;

        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // Add timestamp param to prevent local or intermediate proxy caching
                URL url = new URL(GITHUB_RAW_LIVE_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestMethod("GET");
                conn.setUseCaches(false);
                conn.setRequestProperty("User-Agent", "BashLock-Android-Client");
                conn.setRequestProperty("Cache-Control", "no-cache");

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line = reader.readLine();
                    reader.close();

                    if (line != null) {
                        String fetchedUrl = line.trim();
                        if (fetchedUrl.startsWith("https://") && !fetchedUrl.contains("10.")) {
                            if (!fetchedUrl.endsWith("/lock-app")) {
                                fetchedUrl = fetchedUrl + "/lock-app";
                            }
                            String currentUrl = getActiveBaseUrl();
                            if (!fetchedUrl.equalsIgnoreCase(currentUrl) || forceReloadOnSuccess) {
                                Log.i(TAG, "Auto-Resolved fresh live URL from GitHub: " + fetchedUrl);
                                mPrefs.edit().putString("cached_live_url", fetchedUrl).apply();
                                final String targetUrl = fetchedUrl;
                                runOnUiThread(() -> {
                                    loadUrlInternal(targetUrl);
                                });
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "GitHub live_url.txt fetch warning: " + e.getMessage());
            } finally {
                mIsResolvingUrl = false;
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    private void loadUrlInternal(String baseUrl) {
        String savedCode = mPrefs.getString("setup_code", "").trim();
        String url = baseUrl;
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

        // Persist to Direct Boot storage for immediate boot-time enforcement before PIN unlock
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                Context directBootContext = createDeviceProtectedStorageContext();
                directBootContext.getSharedPreferences("BashLock_Config_DirectBoot", MODE_PRIVATE)
                        .edit().putBoolean("is_locked", isLocked).apply();
            } catch (Exception ignored) {}
        }

        // Apply Device Owner hardware lock enforcement (status bar / keyguard disable)
        BashLockDeviceAdminReceiver.setKioskEnforcement(this, isLocked);

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
            if (mDpm != null && mDpm.isDeviceOwnerApp(getPackageName())) {
                mDpm.setLockTaskPackages(mAdminComponent, new String[]{getPackageName()});
            }
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

    public boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled = 0;
        final String service = getPackageName() + "/" + AdminSecurityAccessibilityService.class.getCanonicalName();
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
        } catch (Settings.SettingNotFoundException ignored) {}

        if (accessibilityEnabled == 1) {
            String settingValue = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (settingValue != null) {
                return settingValue.contains(service);
            }
        }
        return false;
    }

    public void promptAccessibilityService() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Enable BashLock Security Service to protect against unauthorized deactivation", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        }
    }

    public void startGuardService() {
        try {
            Intent serviceIntent = new Intent(this, BashLockGuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i(TAG, "BashLockGuardService started from MainActivity");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BashLockGuardService: " + e.getMessage());
        }
    }

    public void promptBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                try {
                    @SuppressLint("BatteryLife")
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("FORCE_KIOSK_LOCK", false)) {
            Log.w(TAG, "FORCE_KIOSK_LOCK intent received from Guard Service!");
            setKioskLockState(true);
            applyImmersiveFullscreen();
            loadLockAppUrl();
        } else if (intent.getBooleanExtra("FORCE_KIOSK_UNLOCK", false)) {
            Log.i(TAG, "FORCE_KIOSK_UNLOCK intent received from Guard Service!");
            setKioskLockState(false);
            loadLockAppUrl();
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
