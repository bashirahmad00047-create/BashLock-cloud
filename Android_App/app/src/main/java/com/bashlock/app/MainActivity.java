package com.bashlock.app;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Main Android Wrapper Activity for BashLock.
 * Hosts the mobile companion app inside an accelerated WebKit WebView,
 * maintains native Android bridge interfaces, and ensures background guard persistence.
 */
public class MainActivity extends AppCompatActivity {
    private static final String DEFAULT_TUNNEL_URL = "https://liberty-opposite-applying-www.trycloudflare.com";
    private WebView mWebView;
    private ProgressBar mProgressBar;
    private SharedPreferences mPrefs;
    private DevicePolicyManager mDpm;
    private ComponentName mAdminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences("BashLockPrefs", MODE_PRIVATE);
        mDpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mAdminComponent = new ComponentName(this, BashLockAdminReceiver.class);

        // 1. Start Persistent Background Device Guard Service
        startGuardService();

        // 2. Check if phone is in LOCKED state
        if (mPrefs.getBoolean("is_locked", false)) {
            Intent lockIntent = new Intent(this, LockActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(lockIntent);
        }

        // 3. Initialize WebView and UI
        mProgressBar = findViewById(R.id.progressBar);
        mWebView = findViewById(R.id.webView);

        setupWebView();
        checkDeviceAdmin();
        loadLockApp();
    }

    private void startGuardService() {
        Intent serviceIntent = new Intent(this, LockGuardService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void checkDeviceAdmin() {
        if (mDpm != null && !mDpm.isAdminActive(mAdminComponent)) {
            // Prompt Device Admin during initial shop setup
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, mAdminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "BashLock Device Administrator policy protects installment security and enables remote locking.");
            startActivity(intent);
        }
    }

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

        // Hardware acceleration
        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Add JavaScript Interface Bridge
        mWebView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mProgressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mProgressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    mProgressBar.setVisibility(View.GONE);
                    // If network fails, load local offline fallback
                    loadOfflineFallback();
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

    private void loadLockApp() {
        String pairedCode = mPrefs.getString("paired_code", "");
        String serverUrl = mPrefs.getString("server_url", DEFAULT_TUNNEL_URL);

        String targetUrl = serverUrl + "/lock-app";
        if (!pairedCode.isEmpty()) {
            targetUrl += "/" + pairedCode;
        }

        if (isNetworkAvailable()) {
            mWebView.loadUrl(targetUrl);
        } else {
            loadOfflineFallback();
        }
    }

    private void loadOfflineFallback() {
        String pairedCode = mPrefs.getString("paired_code", "----");
        String balance = mPrefs.getString("balance", "30,000");
        String dueDate = mPrefs.getString("due_date", "2026-11-20");

        String offlineHtml = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<style>body{background:#070B14;color:#FFF;font-family:sans-serif;padding:24px;text-align:center;}"
                + ".card{background:#0F172A;border:1px solid #1E293B;padding:20px;border-radius:16px;margin-top:20px;}"
                + ".btn{background:#10B981;color:#FFF;border:none;padding:12px 24px;border-radius:12px;font-weight:bold;margin-top:20px;cursor:pointer;}"
                + "</style></head><body>"
                + "<h2>🛡️ BashLock Mobile Guard</h2>"
                + "<div class='card'>"
                + "<p style='color:#34D399;font-weight:bold;'>OFFLINE GUARD ACTIVE</p>"
                + "<p>Paired Code: <strong style='font-family:monospace;'>" + pairedCode + "</strong></p>"
                + "<p>Kul Baqaya: <strong>PKR " + balance + "</strong></p>"
                + "<p>Agli Tareekh: <strong>" + dueDate + "</strong></p>"
                + "</div>"
                + "<button class='btn' onclick='location.reload()'>🔄 Retry Internet Connection</button>"
                + "</body></html>";

        mWebView.loadDataWithBaseURL(null, offlineHtml, "text/html", "UTF-8", null);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
