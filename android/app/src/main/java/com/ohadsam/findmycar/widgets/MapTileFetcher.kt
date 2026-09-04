package com.ohadsam.findmycar.widgets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/**
 * Composites a small 2x2 grid of OpenStreetMap raster tiles centered on the
 * given coordinate (same tile source the app's Leaflet map already uses via
 * tile.openstreetmap.org) and draws a pin — for the mini-map widget.
 * Widgets render via RemoteViews, which can't host a WebView/Leaflet
 * instance, so this stays a plain bitmap rather than embedding the real map.
 */
object MapTileFetcher {
    private const val ZOOM = 16
    private const val TILE_SIZE = 256
    private const val USER_AGENT = "FindMyCarAndroid/1.0 (+https://github.com/ohadsam/find_my_car)"

    fun fetchAsync(lat: Double, lng: Double, callback: (Bitmap?) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        thread {
            val bitmap = try { fetch(lat, lng) } catch (e: Exception) { null }
            handler.post { callback(bitmap) }
        }
    }

    private fun fetch(lat: Double, lng: Double): Bitmap? {
        val n = 2.0.pow(ZOOM)
        val xTileF = (lng + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat)
        val yTileF = (1.0 - ln(tan(latRad) + 1.0 / Math.cos(latRad)) / PI) / 2.0 * n

        val xTile = floor(xTileF).toInt()
        val yTile = floor(yTileF).toInt()

        val composite = Bitmap.createBitmap(TILE_SIZE * 2, TILE_SIZE * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)

        for (dx in 0..1) {
            for (dy in 0..1) {
                val tile = downloadTile(ZOOM, xTile + dx, yTile + dy) ?: continue
                canvas.drawBitmap(tile, (dx * TILE_SIZE).toFloat(), (dy * TILE_SIZE).toFloat(), null)
            }
        }

        // Pin at the coordinate's fractional position within the composite
        val px = ((xTileF - xTile) * TILE_SIZE).toFloat()
        val py = ((yTileF - yTile) * TILE_SIZE).toFloat()
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val pin  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E94B3C") }
        canvas.drawCircle(px, py, 12f, ring)
        canvas.drawCircle(px, py, 8f, pin)

        // Crop to a centered square around the pin for a tighter widget image
        val cropSize = TILE_SIZE
        val cropLeft = (px - cropSize / 2f).coerceIn(0f, (TILE_SIZE * 2 - cropSize).toFloat())
        val cropTop  = (py - cropSize / 2f).coerceIn(0f, (TILE_SIZE * 2 - cropSize).toFloat())
        return Bitmap.createBitmap(composite, cropLeft.toInt(), cropTop.toInt(), cropSize, cropSize)
    }

    private fun downloadTile(z: Int, x: Int, y: Int): Bitmap? {
        val conn = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        return try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }
}
