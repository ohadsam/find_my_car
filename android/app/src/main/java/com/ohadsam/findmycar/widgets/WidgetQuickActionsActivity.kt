package com.ohadsam.findmycar.widgets

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ohadsam.findmycar.MainActivity
import com.ohadsam.findmycar.R

/**
 * Small floating dialog opened from the "⋮" button on the Active Parking
 * widget. A real AppWidget can't intercept long-press (the launcher reserves
 * that gesture for its own move/resize/remove chrome), so this is the
 * closest equivalent to a widget context menu: a tap-to-open popup with a
 * few direct actions.
 *
 * Each button just launches MainActivity with the same ?action= deep-link
 * mechanism the Quick Save widget already uses (see MainActivity.java) —
 * no native business logic here, the existing JS in app.js does the actual
 * work once the WebView reloads with that query param.
 */
class WidgetQuickActionsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_quick_actions)

        findViewById<android.widget.Button>(R.id.actionSaveBtn).setOnClickListener { launchAction("save") }
        findViewById<android.widget.Button>(R.id.actionSwapBtn).setOnClickListener { launchAction("swap") }
        findViewById<android.widget.Button>(R.id.actionEndBtn).setOnClickListener { launchAction("end") }
        findViewById<android.widget.Button>(R.id.actionVehiclesBtn).setOnClickListener { launchAction("vehicles") }
    }

    private fun launchAction(action: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(QuickSaveWidgetProvider.EXTRA_ACTION, action)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }
}
