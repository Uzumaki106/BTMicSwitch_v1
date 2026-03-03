package com.btmicswitch

import android.Manifest
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.btmicswitch.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var audioManager: AudioManager
    private lateinit var prefs: SharedPreferences
    private var isServiceRunning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            startBtMicService()
        } else {
            showToast("Permissions required for Bluetooth mic routing")
            binding.toggleButton.isChecked = false
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra(BluetoothScoService.EXTRA_STATE) ?: return
            val deviceName = intent.getStringExtra(BluetoothScoService.EXTRA_DEVICE_NAME) ?: ""
            handleServiceState(state, deviceName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences(BluetoothAutoReceiver.PREFS_NAME, MODE_PRIVATE)

        setupUI()
        updateBluetoothDeviceInfo()
        syncAutoTriggerToggle()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver, IntentFilter(BluetoothScoService.BROADCAST_STATE)
        )
        updateBluetoothDeviceInfo()
        syncAutoTriggerToggle()
        // Sync main toggle with actual service state
        val serviceRunning = prefs.getBoolean(BluetoothAutoReceiver.KEY_SERVICE_RUNNING, false)
        if (serviceRunning != isServiceRunning) {
            binding.toggleButton.isChecked = serviceRunning
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
    }

    private fun setupUI() {
        // Main BT mic toggle
        binding.toggleButton.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkAndRequestPermissions()) startBtMicService()
            } else {
                stopBtMicService()
            }
        }

        // Auto-trigger toggle — starts service automatically when BT connects
        binding.autoTriggerSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(BluetoothAutoReceiver.KEY_AUTO_TRIGGER, isChecked).apply()
            if (isChecked) {
                showToast("Auto-trigger ON — BT mic will start automatically when headset connects")
            } else {
                showToast("Auto-trigger OFF — manual control only")
            }
        }
    }

    private fun syncAutoTriggerToggle() {
        val autoEnabled = prefs.getBoolean(BluetoothAutoReceiver.KEY_AUTO_TRIGGER, false)
        binding.autoTriggerSwitch.isChecked = autoEnabled
    }

    private fun handleServiceState(state: String, deviceName: String) {
        when (state) {
            BluetoothScoService.STATE_CONNECTING -> {
                setUiState(false, "Connecting…", deviceName, isLoading = true)
            }
            BluetoothScoService.STATE_ACTIVE -> {
                isServiceRunning = true
                prefs.edit().putBoolean(BluetoothAutoReceiver.KEY_SERVICE_RUNNING, true).apply()
                setUiState(true, "BT Mic Active", deviceName)
            }
            BluetoothScoService.STATE_FAILED -> {
                isServiceRunning = false
                binding.toggleButton.isChecked = false
                prefs.edit().putBoolean(BluetoothAutoReceiver.KEY_SERVICE_RUNNING, false).apply()
                setUiState(false, "Connection Failed", "Check Bluetooth connection")
                showToast("SCO connection failed. Ensure headset is connected.")
            }
            BluetoothScoService.STATE_STOPPED -> {
                isServiceRunning = false
                binding.toggleButton.isChecked = false
                prefs.edit().putBoolean(BluetoothAutoReceiver.KEY_SERVICE_RUNNING, false).apply()
                setUiState(false, "Using Internal Mic", getConnectedBtDeviceName() ?: "No device connected")
            }
            BluetoothScoService.STATE_BT_DISCONNECTED -> {
                isServiceRunning = false
                binding.toggleButton.isChecked = false
                prefs.edit().putBoolean(BluetoothAutoReceiver.KEY_SERVICE_RUNNING, false).apply()
                setUiState(false, "Using Internal Mic", "Bluetooth device disconnected")
                showToast("Bluetooth disconnected – reverted to internal mic")
            }
        }
    }

    private fun setUiState(isActive: Boolean, status: String, deviceText: String, isLoading: Boolean = false) {
        binding.statusText.text = status
        binding.deviceName.text = deviceText
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        if (isActive) {
            binding.statusIndicator.setImageResource(R.drawable.ic_indicator_active)
            binding.statusCard.setCardBackgroundColor(getColor(R.color.status_active_bg))
            binding.statusText.setTextColor(getColor(R.color.status_active_text))
        } else {
            binding.statusIndicator.setImageResource(R.drawable.ic_indicator_inactive)
            binding.statusCard.setCardBackgroundColor(getColor(R.color.status_inactive_bg))
            binding.statusText.setTextColor(getColor(R.color.status_inactive_text))
        }
    }

    private fun startBtMicService() {
        val btDevice = getConnectedBtDeviceName()
        if (btDevice == null) {
            showToast("No Bluetooth headset connected")
            binding.toggleButton.isChecked = false
            return
        }
        ContextCompat.startForegroundService(this,
            Intent(this, BluetoothScoService::class.java).apply { action = BluetoothScoService.ACTION_START })
        setUiState(false, "Connecting…", btDevice, isLoading = true)
    }

    private fun stopBtMicService() {
        startService(Intent(this, BluetoothScoService::class.java).apply {
            action = BluetoothScoService.ACTION_STOP
        })
    }

    private fun updateBluetoothDeviceInfo() {
        val name = getConnectedBtDeviceName()
        if (name != null) {
            binding.deviceName.text = name
            binding.btStatusChip.text = "Connected"
            binding.btStatusChip.setChipBackgroundColorResource(R.color.chip_connected)
        } else {
            binding.deviceName.text = "No device connected"
            binding.btStatusChip.text = "No BT Device"
            binding.btStatusChip.setChipBackgroundColorResource(R.color.chip_disconnected)
        }
    }

    private fun getConnectedBtDeviceName(): String? {
        return try {
            val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = btManager.adapter ?: return null
            if (!adapter.isEnabled) return null
            @Suppress("MissingPermission")
            adapter.bondedDevices?.firstOrNull { device ->
                try {
                    device.javaClass.getMethod("isConnected").invoke(device) as Boolean
                } catch (e: Exception) { false }
            }?.name
        } catch (e: Exception) {
            Log.e("BTMicSwitch", "Error getting BT device: ${e.message}")
            null
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        return if (needed.isEmpty()) true
        else { permissionLauncher.launch(needed.toTypedArray()); false }
    }

    private fun showToast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
