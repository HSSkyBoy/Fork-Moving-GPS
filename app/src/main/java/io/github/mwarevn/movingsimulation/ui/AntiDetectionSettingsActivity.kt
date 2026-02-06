package io.github.mwarevn.movingsimulation.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import io.github.mwarevn.movingsimulation.R
import io.github.mwarevn.movingsimulation.databinding.ActivityAntiDetectionSettingsBinding
import io.github.mwarevn.movingsimulation.utils.PrefManager

/**
 * Activity for configuring Advanced Anti-Detection features
 * Streamlined to only show essential advanced features
 */
class AntiDetectionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAntiDetectionSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAntiDetectionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSwitches()
        setupResetButton()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.title_anti_detection)
        }
    }

    private fun setupSwitches() {
        // Advanced Feature 1: Sensor Spoofing
        setupSwitch(
            binding.switchSensorSpoof,
            PrefManager.enableSensorSpoof,
            R.string.sensor_spoof_title,
            R.string.sensor_spoof_desc
        ) { PrefManager.enableSensorSpoof = it }

        // Advanced Feature 2: Network Simulation
        setupSwitch(
            binding.switchNetworkSimulation,
            PrefManager.enableNetworkSimulation,
            R.string.network_sim_title,
            R.string.network_sim_desc
        ) { PrefManager.enableNetworkSimulation = it }

        // Advanced Feature 3: Advanced Randomization
        setupSwitch(
            binding.switchAdvancedRandomization,
            PrefManager.enableAdvancedRandomization,
            R.string.adv_random_title,
            R.string.adv_random_desc
        ) { PrefManager.enableAdvancedRandomization = it }
    }

    private fun setupSwitch(
        switch: SwitchCompat,
        currentValue: Boolean,
        titleRes: Int,
        descRes: Int,
        onChanged: (Boolean) -> Unit
    ) {
        // Set initial state
        switch.isChecked = currentValue

        // Variable to track if we're programmatically changing the switch
        var isUpdating = false

        // Set up listener
        switch.setOnCheckedChangeListener { _, isChecked ->
            // Ignore if we're programmatically updating
            if (isUpdating) return@setOnCheckedChangeListener

            // Show confirmation dialog
            showFeatureInfo(
                getString(titleRes),
                getString(descRes),
                isChecked,
                onConfirm = {
                    // User confirmed - save the change
                    onChanged(isChecked)
                    // 使用你提供的正確 ID: msg_feature_updated_restart
                    Toast.makeText(this, R.string.msg_feature_updated_restart, Toast.LENGTH_SHORT).show()
                },
                onCancel = {
                    // User cancelled - revert switch silently
                    isUpdating = true
                    switch.isChecked = !isChecked
                    isUpdating = false
                }
            )
        }
    }

    private fun showFeatureInfo(
        title: String,
        description: String,
        enabling: Boolean,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(description)
            .setPositiveButton(if (enabling) android.R.string.ok else android.R.string.cancel) { _, _ ->
                onConfirm()
            }
            .setNegativeButton(R.string.btn_cancel) { dialog, _ ->
                dialog.dismiss()
                onCancel()
            }
            .setCancelable(false)
            .show()
    }

    private fun setupResetButton() {
        binding.btnResetToDefault.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_reset_title)
                .setMessage(R.string.dialog_reset_message)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    PrefManager.resetAntiDetectionToDefault()
                    Toast.makeText(this, R.string.msg_feature_updated_restart, Toast.LENGTH_SHORT).show()
                    recreate()
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
