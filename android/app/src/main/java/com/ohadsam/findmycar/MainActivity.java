package com.ohadsam.findmycar;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.ohadsam.findmycar.widgets.QuickSaveWidgetProvider;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends BridgeActivity {
    // Every deep-link value a widget (Quick Save, or the Active Parking
    // widget's quick-actions popup) is allowed to request — an explicit
    // allowlist instead of passing the extra straight into the URL query
    // string unchecked.
    private static final Set<String> VALID_ACTIONS = new HashSet<>(
        Arrays.asList("save", "swap", "end", "vehicles")
    );

    // Lets WidgetActionReceiver reach the already-running WebView (if the
    // app is alive in the background — the common case, since
    // ParkingForegroundService + KeepRunning keep it that way) and run a
    // widget action via evaluateJavascript() without ever bringing this
    // Activity to the foreground. WeakReference so this never keeps the
    // Activity alive past its normal lifecycle.
    private static WeakReference<MainActivity> activeInstance;

    // Separate from activeInstance/getActiveWebView() on purpose: that one
    // reflects whether the Activity object exists at all (onCreate..onDestroy
    // — true through the entire backgrounded/paused period, since KeepRunning
    // keeps the Activity+WebView alive without destroying them), while this
    // reflects whether it's actually visible right now (onResume..onPause).
    // GpsDecisionEngine's live counterpart, navigator.geolocation.watchPosition()
    // in js/app.js, is subject to Android's own background-location throttling
    // tied to Activity visibility — KeepRunning keeps the JS engine executing,
    // but does not keep the WebView's Geolocation API delivering updates once
    // the Activity is merely paused (not destroyed). ParkingForegroundService's
    // maybeRecordPendingGpsSuggestion() must key off THIS, not getActiveWebView(),
    // or it silently no-ops for the entire paused-but-alive window under the
    // wrong assumption that "the live JS path already handles it" — the bug
    // that shipped before this field existed (GpsDecisionEngine's own shadow
    // watch, running off a plain LocationManager request from inside the
    // foreground Service rather than the WebView, is NOT subject to this
    // throttling, which is why GPS-SHADOW log entries could appear during a
    // window where no live GPS suggestion or notification ever did).
    private static volatile boolean foreground = false;

    public static boolean isForeground() {
        return foreground;
    }

    public static WebView getActiveWebView() {
        MainActivity a = activeInstance != null ? activeInstance.get() : null;
        return (a != null && a.getBridge() != null) ? a.getBridge().getWebView() : null;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BluetoothClassicPlugin.class);
        registerPlugin(WidgetDataPlugin.class);
        super.onCreate(savedInstanceState);
        activeInstance = new WeakReference<>(this);

        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView != null) {
            // Separate from Capacitor's own bridge — only used so
            // WidgetActionReceiver's injected JS can report back a result
            // string (for a Toast) after running headlessly. Safe: this
            // WebView only ever loads our own bundled content, never
            // arbitrary/remote pages.
            webView.addJavascriptInterface(new WidgetJsBridge(getApplicationContext()), "AndroidWidgetBridge");
        }

        applyLaunchIntent(getIntent());
    }

    @Override
    public void onDestroy() {
        if (activeInstance != null && activeInstance.get() == this) activeInstance = null;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyLaunchIntent(intent);
    }

    // Reuses the PWA's own "?action=..." query-param handling (js/app.js
    // #init()) — widgets just need to get the WebView to that URL, no
    // separate native business logic for save/swap/end/vehicles. Only used
    // as a fallback now (WidgetActionReceiver handles the headless path) —
    // for when the app isn't already running in the background.
    private void applyLaunchIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra(QuickSaveWidgetProvider.EXTRA_ACTION);
        if (!VALID_ACTIONS.contains(action)) return;

        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView == null) return;
        webView.post(() -> {
            String currentUrl = webView.getUrl();
            String base = currentUrl != null ? currentUrl.split("\\?")[0] : "https://localhost/index.html";
            webView.loadUrl(base + "?action=" + action);
        });
    }
}
