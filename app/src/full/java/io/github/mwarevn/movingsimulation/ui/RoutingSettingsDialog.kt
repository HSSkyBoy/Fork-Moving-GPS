package io.github.mwarevn.movingsimulation.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import io.github.mwarevn.movingsimulation.R
import io.github.mwarevn.movingsimulation.network.VehicleType
import io.github.mwarevn.movingsimulation.utils.PrefManager

/**
 * Dialog to configure routing settings:
 * - MapBox API key (optional, for better routing)
 * - Vehicle type selection
 */
object RoutingSettingsDialog {
    
    fun show(context: Context, onSaved: () -> Unit = {}) {
        val dialogView = LayoutInflater.from(context).inflate(
            R.layout.dialog_routing_settings, null
        )
        
        val apiKeyInput = dialogView.findViewById<EditText>(R.id.api_key_input)
        val vehicleTypeSpinner = dialogView.findViewById<Spinner>(R.id.vehicle_type_spinner)
        
        // Load current values
        apiKeyInput.setText(PrefManager.mapBoxApiKey ?: "")
        
        // Setup vehicle type spinner
        val vehicleTypes = VehicleType.values()
        val vehicleNames = vehicleTypes.map { it.displayName }
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, vehicleNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        vehicleTypeSpinner.adapter = adapter
        
        // Set current vehicle type
        val currentType = VehicleType.fromString(PrefManager.vehicleType)
        vehicleTypeSpinner.setSelection(vehicleTypes.indexOf(currentType))
        
        AlertDialog.Builder(context)
            .setTitle("Set the path")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val apiKey = apiKeyInput.text.toString().trim()
                PrefManager.mapBoxApiKey = if (apiKey.isEmpty()) null else apiKey
                
                val selectedType = vehicleTypes[vehicleTypeSpinner.selectedItemPosition]
                PrefManager.vehicleType = selectedType.name
                
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
