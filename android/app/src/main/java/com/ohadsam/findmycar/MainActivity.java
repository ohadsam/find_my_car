package com.ohadsam.findmycar;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.ohadsam.findmycar.widgets.QuickSaveWidgetProvider;
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
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(BluetoothClassicPlugin.class);
        registerPlugin(WidgetDataPlugin.class);
        super.onCreate(savedInstanceState);
        applyLaunchIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyLaunchIntent(intent);
    }

    // Reuses the PWA's own "?action=..." query-param handling (js/app.js
    // #init()) — widgets just need to get the WebView to that URL, no
    // separate native business logic for save/swap/end/vehicles.
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
