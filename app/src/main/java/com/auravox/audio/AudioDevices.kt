package com.auravox.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Which microphone the engine should open.
 *
 * Android does not hand a Bluetooth headset microphone to an app just because
 * the headset is connected. The capture path has to be put into communication
 * mode first, and it will never be the fast low-latency path — so asking for
 * one silently lands back on the phone's own microphone, which is exactly what
 * it looks like from the outside: "it is still using the phone mic".
 */
object AudioDevices {

    private const val TAG = "AuraVox"
    private const val SCO_TIMEOUT_MS = 4000L

    enum class Kind { AUTO, BUILT_IN, WIRED, BLUETOOTH, USB }

    data class MicOption(val id: Int, val label: String, val kind: Kind) {
        /** Bluetooth capture has no fast path, so the engine must relax its request. */
        val needsCommunicationMode: Boolean get() = kind == Kind.BLUETOOTH
    }

    val auto = MicOption(0, "Automático", Kind.AUTO)

    fun inputs(context: Context): List<MicOption> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return listOf(auto)

        val found = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { device ->
                val kind = kindOf(device.type) ?: return@mapNotNull null
                MicOption(device.id, label(device, kind), kind)
            }
        }.getOrDefault(emptyList())

        // Several entries can share a name on devices that expose both a mono
        // and a stereo capture path; the id is what actually differs
        return listOf(auto) + found.distinctBy { it.id }
    }

    private fun kindOf(type: Int): Kind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> Kind.BUILT_IN
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> Kind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> Kind.USB
        else -> null
    }

    private fun label(device: AudioDeviceInfo, kind: Kind): String {
        val name = device.productName?.toString()?.trim().orEmpty()
        val prefix = when (kind) {
            Kind.BUILT_IN -> "Microfone do celular"
            Kind.WIRED -> "Fone com fio"
            Kind.BLUETOOTH -> "Bluetooth"
            Kind.USB -> "USB"
            Kind.AUTO -> "Automático"
        }
        return if (name.isEmpty() || name.equals("android", true)) prefix else "$prefix · $name"
    }

    /**
     * Routes capture to the Bluetooth headset and waits for the link to come
     * up. Blocking, up to four seconds; call it off the main thread.
     *
     * Returns false when the link never connects, and the caller should fall
     * back rather than record four minutes of silence.
     */
    fun enableBluetooth(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } ?: return false
                am.setCommunicationDevice(target)
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
                // The link comes up asynchronously and there is no callback
                // worth the plumbing here, so this polls for it
                val deadline = System.currentTimeMillis() + SCO_TIMEOUT_MS
                while (System.currentTimeMillis() < deadline) {
                    @Suppress("DEPRECATION")
                    if (am.isBluetoothScoOn) return true
                    Thread.sleep(100)
                }
                false
            }
        }.getOrElse {
            Log.w(TAG, "bluetooth capture failed: ${it.message}")
            false
        }
    }

    fun disableBluetooth(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
            }
        }
    }
}
