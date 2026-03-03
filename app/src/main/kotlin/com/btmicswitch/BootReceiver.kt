package com.btmicswitch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BTMicSwitch", "Boot completed — auto-trigger will fire when BT connects")
            // No action needed here — BluetoothAutoReceiver handles BT connect events
            // and is registered in manifest so it works after reboot automatically
        }
    }
}
