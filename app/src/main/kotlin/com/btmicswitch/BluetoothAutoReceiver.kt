package com.btmicswitch

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BluetoothAutoReceiver
 *
 * Listens for Bluetooth headset connect/disconnect events system-wide.
 * When a BT device connects → automatically starts the BT mic service (if auto-trigger enabled).
 * When a BT device disconnects → automatically stops the service.
 *
 * The auto-trigger feature can be toggled in the app UI via SharedPreferences.
 */
class BluetoothAutoReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "BTAutoReceiver"
        const val PREFS_NAME = "btmicswitch_prefs"
        const val KEY_AUTO_TRIGGER = "auto_trigger_enabled"
        const val KEY_SERVICE_RUNNING = "service_running"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoTriggerEnabled = prefs.getBoolean(KEY_AUTO_TRIGGER, false)

        Log.d(TAG, "BT event: ${intent.action}, autoTrigger=$autoTriggerEnabled")

        when (intent.action) {
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "BT headset connected: ${device?.address}")
                        if (autoTriggerEnabled) {
                            Log.d(TAG, "Auto-trigger ON — starting BT mic service")
                            val serviceIntent = Intent(context, BluetoothScoService::class.java).apply {
                                action = BluetoothScoService.ACTION_BT_CONNECTED
                            }
                            ContextCompat.startForegroundService(context, serviceIntent)
                            prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "BT headset disconnected: ${device?.address}")
                        // Always stop service on disconnect regardless of auto-trigger setting
                        val serviceIntent = Intent(context, BluetoothScoService::class.java).apply {
                            action = BluetoothScoService.ACTION_STOP
                        }
                        context.startService(serviceIntent)
                        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
                    }
                }
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                Log.d(TAG, "ACL connected")
                if (autoTriggerEnabled) {
                    // Small delay to let HFP profile connect after ACL
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        val serviceIntent = Intent(context, BluetoothScoService::class.java).apply {
                            action = BluetoothScoService.ACTION_BT_CONNECTED
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                        prefs.edit().putBoolean(KEY_SERVICE_RUNNING, true).apply()
                    }, 1500)
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d(TAG, "ACL disconnected")
                val serviceIntent = Intent(context, BluetoothScoService::class.java).apply {
                    action = BluetoothScoService.ACTION_STOP
                }
                context.startService(serviceIntent)
                prefs.edit().putBoolean(KEY_SERVICE_RUNNING, false).apply()
            }
        }
    }
}
