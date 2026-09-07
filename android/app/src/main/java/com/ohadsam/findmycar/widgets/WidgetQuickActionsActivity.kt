package com.ohadsam.findmycar.widgets

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ohadsam.findmycar.MainActivity
import com.ohadsam.findmycar.R
import com.ohadsam.findmycar.WidgetActionReceiver
import com.ohadsam.findmycar.WidgetDataPlugin
import org.json.JSONArray

/**
 * Small floating dialog opened from the "⋮" button on every widget. A real
 * AppWidget can't intercept long-press (the launcher reserves that gesture
 * for its own move/resize/remove chrome), so this tap-to-open popup with a
 * vehicle picker + direct actions is the closest equivalent to a widget
 * context menu.
 *
 * Save/Swap/End run headlessly via WidgetActionReceiver — broadcast only,
 * this Activity never launches MainActivity for those, so choosing an
 * action never navigates away from whatever the user was doing. Only
 * "ניהול רכבים" (add/edit a vehicle) still opens the app, since that
 * genuinely needs real UI.
 */
class WidgetQuickActionsActivity : AppCompatActivity() {
    private data class VehicleEntry(val id: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_quick_actions)

        val vehicles = readVehicles()
        val spinner = findViewById<Spinner>(R.id.actionVehicleSpinner)
        val emptyMsg = findViewById<TextView>(R.id.actionEmptyMsg)
        val actionsGroup = findViewById<View>(R.id.actionButtonsGroup)

        if (vehicles.isEmpty()) {
            spinner.visibility = View.GONE
            actionsGroup.visibility = View.GONE
            emptyMsg.visibility = View.VISIBLE
        } else {
            spinner.visibility = View.VISIBLE
            actionsGroup.visibility = View.VISIBLE
            emptyMsg.visibility = View.GONE

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, vehicles.map { it.label })
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter

            val activeId = activeVehicleId()
            val activeIndex = vehicles.indexOfFirst { it.id == activeId }
            if (activeIndex >= 0) spinner.setSelection(activeIndex)

            findViewById<Button>(R.id.actionSaveBtn).setOnClickListener {
                runHeadless("save", vehicles[spinner.selectedItemPosition].id)
            }
            findViewById<Button>(R.id.actionSwapBtn).setOnClickListener {
                runHeadless("swap", vehicles[spinner.selectedItemPosition].id)
            }
            findViewById<Button>(R.id.actionEndBtn).setOnClickListener {
                runHeadless("end", vehicles[spinner.selectedItemPosition].id)
            }
        }

        findViewById<Button>(R.id.actionVehiclesBtn).setOnClickListener { openAppForVehicles() }
    }

    private fun runHeadless(action: String, vehicleId: String) {
        val intent = Intent(this, WidgetActionReceiver::class.java).apply {
            putExtra(QuickSaveWidgetProvider.EXTRA_ACTION, action)
            putExtra(WidgetActionReceiver.EXTRA_VEHICLE_ID, vehicleId)
        }
        sendBroadcast(intent)
        Toast.makeText(this, "מבצע…", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun openAppForVehicles() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(QuickSaveWidgetProvider.EXTRA_ACTION, "vehicles")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun activeVehicleId(): String {
        val prefs = getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
        return prefs.getString(WidgetDataPlugin.KEY_ACTIVE_VEHICLE_ID, "") ?: ""
    }

    private fun readVehicles(): List<VehicleEntry> {
        val prefs = getSharedPreferences(WidgetDataPlugin.PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(WidgetDataPlugin.KEY_VEHICLES_JSON, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val icon = o.optString("icon", "🚗")
                val name = o.optString("name", "")
                VehicleEntry(o.getString("id"), "$icon $name".trim())
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
