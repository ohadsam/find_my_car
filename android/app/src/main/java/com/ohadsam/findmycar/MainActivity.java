package com.ohadsam.findmycar;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.ohadsam.findmycar.widgets.QuickSaveWidgetProvider;

public class MainActivity extends BridgeActivity {
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

    // Reuses the PWA's own "?action=save" query-param handling (js/app.js
    // #init()) — the Quick Save widget just needs to get the WebView to that
    // URL, no separate native save path.
    private void applyLaunchIntent(Intent intent) {
        if (intent == null) return;
        if (!"save".equals(intent.getStringExtra(QuickSaveWidgetProvider.EXTRA_ACTION))) return;

        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView == null) return;
        webView.post(() -> {
            String currentUrl = webView.getUrl();
            String base = currentUrl != null ? currentUrl.split("\\?")[0] : "https://localhost/index.html";
            webView.loadUrl(base + "?action=save");
        });
    }
}
