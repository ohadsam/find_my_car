package com.ohadsam.findmycar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * Registered on MainActivity's WebView as "AndroidWidgetBridge" (see
 * MainActivity.onCreate). WidgetActionReceiver injects JS that calls
 * window.app.performWidgetAction(...) and reports the result string back
 * here — the only way to get a value out of an async evaluateJavascript()
 * call, since its own callback only sees the immediate (un-awaited) return.
 * Safe to expose: the WebView never loads anything but our own bundled
 * content, and every exposed method is explicitly @JavascriptInterface.
 */
class WidgetJsBridge(private val appContext: Context) {
    @JavascriptInterface
    fun onResult(message: String) {
        Log.i("FMC-WidgetBridge", "widget action result: $message")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
}
