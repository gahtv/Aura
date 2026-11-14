package com.hans.aura.data

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The name this device is known by elsewhere on the network.
 *
 * Google TV exposes the Cast device name in Settings.Global.DEVICE_NAME
 * ("Living Room TV"), which is what the user already sees in the Google Home
 * app and in their Cast list. Falling back to Build.MODEL keeps two different
 * TVs distinguishable, and both beat a hardcoded "Android TV".
 */
object DeviceNames {
    @Volatile private var name: String = "Android TV"

    fun init(context: Context) {
        val global = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()

        val model = Build.MODEL?.trim()

        name = global?.takeIf { it.isNotEmpty() }
            ?: model?.takeIf { it.isNotEmpty() }
            ?: "Android TV"
    }

    fun get(): String = name
}
