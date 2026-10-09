package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.AdbTls
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.Executors

/**
 * While wireless CarPlay runs, the head unit's own hands-free and Bluetooth music links to that
 * iPhone are turned off, as an Apple-certified head unit does after `disableBluetooth`. Otherwise a
 * call rings on both the car's phone screen and CarPlay, and the car's Bluetooth music competes
 * with CarPlay audio. The change goes through the head unit's root adb shell ([NativeBluetoothTool]).
 *
 * The iPhone's address is journaled before the change and removed only after the links are allowed
 * again, so a crash, force stop or power loss during CarPlay is repaired by the next [restore].
 */
object NativeBluetoothHandoff {
    private const val TAG = "DiPlay-NativeBt"
    private const val PREFS = "native_bluetooth_handoff"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RELEASED = "released_addresses"
    private const val TOOL_TIMEOUT_MILLIS = 15_000

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-native-bt").apply { isDaemon = true } }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) restore(context)
    }

    /** Turns off the car's own HFP and A2DP links to [address] once CarPlay carries calls and audio. */
    fun release(context: Context, address: String) {
        val app = context.applicationContext
        val normalized = address.uppercase()
        if (!enabled(app) || !NativeBluetoothProtocol.validAddress(normalized)) return
        worker.execute {
            rootShell(app, "release") { adb, root ->
                if (!journal(app) { it + normalized }) {
                    Log.w(TAG, "release skipped: journal could not be saved")
                    return@rootShell
                }
                val result = runTool(app, adb, root, NativeBluetoothProtocol.Action.FORBID, normalized)
                Log.i(TAG, "released car Bluetooth hfp=${result?.hfp} a2dpSink=${result?.a2dpSink}")
                // An unreadable answer may still have changed a link, so only a clear "nothing changed" is forgotten.
                if (result != null && !result.any) journal(app) { it - normalized }
            }
        }
    }

    /** Allows and reconnects every link [release] turned off; kept in the journal until that succeeds. */
    fun restore(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val pending = released(app)
            if (pending.isEmpty()) return@execute
            rootShell(app, "restore") { adb, root ->
                for (address in pending) {
                    val result = runTool(app, adb, root, NativeBluetoothProtocol.Action.ALLOW, address)
                    Log.i(TAG, "restored car Bluetooth hfp=${result?.hfp} a2dpSink=${result?.a2dpSink}")
                    if (result?.any == true) journal(app) { it - address }
                }
            }
        }
    }

    /** Runs [block] only when adb answers and its shell is root, directly or through `su`. */
    private inline fun rootShell(context: Context, purpose: String, block: (LocalAdb, Boolean) -> Unit) {
        val port = AdbTls.wirelessDebuggingPort() ?: LocalAdb.DEFAULT_PORT
        LocalAdb(AdbKeys.load(context), port = port).use { adb ->
            val access = adb.connect(mayAsk = false)
            if (access != LocalAdb.Access.READY) {
                Log.w(TAG, "$purpose skipped: ADB access $access port=$port")
                return
            }
            val root = adb.shell("id -u") == "0"
            if (!root && adb.shell("su 0 id -u") != "0") {
                Log.w(TAG, "$purpose skipped: the adb shell has no root")
                return
            }
            block(adb, root)
        }
    }

    private fun runTool(
        context: Context,
        adb: LocalAdb,
        root: Boolean,
        action: NativeBluetoothProtocol.Action,
        address: String,
    ): NativeBluetoothProtocol.Result? {
        val command = NativeBluetoothProtocol.command(context.applicationInfo.sourceDir, action, address, root)
        return NativeBluetoothProtocol.parse(adb.shell(command, TOOL_TIMEOUT_MILLIS), action)
    }

    private fun released(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_RELEASED, emptySet()).orEmpty()
            .filterTo(LinkedHashSet(), NativeBluetoothProtocol::validAddress)

    private fun journal(context: Context, change: (Set<String>) -> Set<String>): Boolean =
        prefs(context).edit().putStringSet(KEY_RELEASED, change(released(context))).commit()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
