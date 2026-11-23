package com.ohadsam.findmycar

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app update: downloads the new APK from this project's GitHub release and
 * hands it to Android's package installer — so an update is one tap inside the
 * app, with no browser, file manager or CI artifact in the way. JS decides WHEN
 * (js/updater.js compares CFG.version with the latest release); this only does
 * what the browser cannot: stream a file to disk and ask the system to install it.
 *
 * Safety, in layers: only an https URL under this repository's releases is
 * accepted, every redirect hop is re-checked (a download URL always redirects
 * to GitHub's asset CDN), the file is verified against the release's SHA-256
 * when JS has one, and — the real guarantee — Android refuses to install over
 * the existing app unless it is signed with the same key (android/app/debug.keystore
 * is committed for exactly this reason).
 *
 * Installing needs the "install unknown apps" permission for this app. That
 * cannot be granted from a dialog: [downloadAndInstall] sends the user to the
 * right settings page and reports `needsPermission`, and the JS asks again once
 * they are back.
 */
@CapacitorPlugin(name = "AppUpdate")
class AppUpdatePlugin : Plugin() {
    companion object {
        private const val TAG = "FMC-AppUpdate"
        private const val ALLOWED_PATH_PREFIX = "/ohadsam/find_my_car/releases/download/"
        private const val APK_NAME = "FindMyCar-update.apk"
        private const val MAX_REDIRECTS = 5
        private const val PROGRESS_STEP_MS = 400L
        private val busy = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
    }

    private fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    private fun updatesDir(ctx: Context) = File(ctx.cacheDir, "updates")

    @PluginMethod
    fun status(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: AppUpdate.status() called")
        // A leftover file from an earlier update is garbage once it is running.
        if (!busy.get()) try { updatesDir(context).deleteRecursively() } catch (e: Exception) { /* best effort */ }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val ret = JSObject()
        ret.put("canInstall", canInstall(context))
        ret.put("versionName", info.versionName ?: "")
        ret.put("busy", busy.get())
        call.resolve(ret)
    }

    @PluginMethod
    fun openInstallSettings(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: AppUpdate.openInstallSettings() called")
        call.resolve(JSObject().put("opened", openUnknownSourcesSettings()))
    }

    @PluginMethod
    fun cancelDownload(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: AppUpdate.cancelDownload() called")
        cancelled.set(true)
        call.resolve()
    }

    /**
     * @param url the release asset URL (https://github.com/ohadsam/find_my_car/releases/download/...)
     * @param sha256 optional lowercase hex digest the file must match
     * Resolves `{status}`: `installing` (the system installer is on screen),
     * `needsPermission`, `cancelled`, `busy` or `failed` (+ `message`).
     */
    @PluginMethod
    fun downloadAndInstall(call: PluginCall) {
        NativeLogStore.add(context, TAG, "BRIDGE", "← JS: AppUpdate.downloadAndInstall() called")
        val url = call.getString("url") ?: ""
        val sha256 = call.getString("sha256")?.lowercase()?.takeIf { it.length == 64 }
        if (!isAllowedStart(url)) {
            call.resolve(result("failed", "כתובת העדכון אינה מורשית"))
            return
        }
        if (!canInstall(context)) {
            val opened = openUnknownSourcesSettings()
            NativeLogStore.add(context, TAG, "UPDATE", "install permission missing — opened settings=$opened")
            call.resolve(result("needsPermission", null))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            call.resolve(result("busy", null))
            return
        }
        cancelled.set(false)
        Thread {
            var outcome: JSObject
            try {
                outcome = download(url, sha256)
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                NativeLogStore.add(context, TAG, "UPDATE", "download FAILED — ${e.message}")
                outcome = result("failed", e.message ?: "ההורדה נכשלה")
            } finally {
                busy.set(false)
            }
            call.resolve(outcome)
        }.start()
    }

    private fun isAllowedStart(url: String): Boolean = try {
        val u = URL(url)
        u.protocol == "https" && u.host == "github.com" && u.path.startsWith(ALLOWED_PATH_PREFIX)
    } catch (e: Exception) {
        false
    }

    /** GitHub serves release assets through its own CDN; nothing else is followed. */
    private fun isAllowedHop(u: URL): Boolean =
        u.protocol == "https" && (u.host == "github.com" || u.host.endsWith(".githubusercontent.com"))

    private fun download(startUrl: String, sha256: String?): JSObject {
        var current = URL(startUrl)
        var conn: HttpURLConnection? = null
        for (hop in 0..MAX_REDIRECTS) {
            if (!isAllowedHop(current)) return result("failed", "הורדה נחסמה — כתובת לא מורשית")
            conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location") ?: return result("failed", "הפניה ללא כתובת")
                conn.disconnect()
                current = URL(current, next)
                continue
            }
            if (code != 200) return result("failed", "השרת החזיר $code")
            break
        }
        val c = conn ?: return result("failed", "ההורדה נכשלה")
        val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val dir = updatesDir(context).apply { mkdirs() }
        val file = File(dir, APK_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        var lastEmit = 0L
        NativeLogStore.add(context, TAG, "UPDATE", "download started (${if (total > 0) total / 1024 else -1} KB)")
        c.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled.get()) {
                        file.delete()
                        NativeLogStore.add(context, TAG, "UPDATE", "download cancelled by the user")
                        return result("cancelled", null)
                    }
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= PROGRESS_STEP_MS) {
                        lastEmit = now
                        notifyListeners("updateProgress", JSObject().put("downloaded", done).put("total", total))
                    }
                }
            }
        }
        c.disconnect()
        notifyListeners("updateProgress", JSObject().put("downloaded", done).put("total", total))
        if (total > 0 && done != total) {
            file.delete()
            return result("failed", "ההורדה נקטעה — נסה שוב")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha256 != null && actual != sha256) {
            file.delete()
            NativeLogStore.add(context, TAG, "UPDATE", "checksum MISMATCH — file discarded")
            return result("failed", "הקובץ שהורד אינו תקין (סכום ביקורת שונה)")
        }
        NativeLogStore.add(context, TAG, "UPDATE", "download complete ($done bytes, sha256 ${if (sha256 != null) "verified" else "not provided"}) — opening the installer")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        (activity ?: context).startActivity(intent)
        return result("installing", null)
    }

    private fun openUnknownSourcesSettings(): Boolean = try {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (activity ?: context).startActivity(i)
        true
    } catch (e: Exception) {
        false
    }

    private fun result(status: String, message: String?): JSObject {
        val o = JSObject().put("status", status)
        if (message != null) o.put("message", message)
        return o
    }
}
