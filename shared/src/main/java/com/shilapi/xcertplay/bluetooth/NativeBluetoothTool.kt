package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder

/**
 * Runs as root through `app_process`, not in DiPlay. Changes the head unit's own hands-free (HFP
 * client) and Bluetooth music (A2DP sink) link to one iPhone. Setting a connection policy needs
 * BLUETOOTH_PRIVILEGED, which an ordinary app never has, and the profile Binders are taken with
 * `peekService` because a process without an app context cannot bind them.
 *
 * Prints one line, [NativeBluetoothProtocol.HEADER]|action|hfp|a2dpSink, with 1 for a profile the
 * Bluetooth stack accepted the change on.
 */
object NativeBluetoothTool {
    private const val POLICY_FORBIDDEN = 0
    private const val POLICY_ALLOWED = 100

    private val PROFILES = listOf(
        "android.bluetooth.IBluetoothHeadsetClient" to "com.android.bluetooth/.hfpclient.HeadsetClientService",
        "android.bluetooth.IBluetoothA2dpSink" to "com.android.bluetooth/.a2dpsink.A2dpSinkService",
    )

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        val action = args.getOrNull(0)?.let(NativeBluetoothProtocol.Action::parse)
        val address = args.getOrNull(1)?.takeIf(NativeBluetoothProtocol::validAddress)
        if (action == null || address == null) {
            println("${NativeBluetoothProtocol.HEADER}|invalid|0|0")
            return
        }
        val result = apply(action, address)
        println(NativeBluetoothProtocol.line(action, result.hfp, result.a2dpSink))
    }

    internal fun apply(action: NativeBluetoothProtocol.Action, address: String): NativeBluetoothProtocol.Result {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
        val results = PROFILES.map { (descriptor, component) ->
            val service = profile(descriptor, component) ?: return@map false
            when (action) {
                NativeBluetoothProtocol.Action.FORBID -> {
                    val changed = call(service, "setConnectionPolicy", device, POLICY_FORBIDDEN) == true
                    call(service, "disconnect", device)
                    changed
                }
                NativeBluetoothProtocol.Action.ALLOW -> {
                    val changed = call(service, "setConnectionPolicy", device, POLICY_ALLOWED) == true
                    call(service, "connect", device)
                    changed
                }
            }
        }
        if (action == NativeBluetoothProtocol.Action.FORBID) disconnectDevice(device)
        return NativeBluetoothProtocol.Result(hfp = results[0], a2dpSink = results[1])
    }

    private fun disconnectDevice(device: BluetoothDevice) {
        runCatching {
            BluetoothDevice::class.java.methods.firstOrNull {
                it.name == "disconnect" && it.parameterTypes.isEmpty()
            }?.invoke(device)
        }
    }

    private fun profile(descriptor: String, component: String): Any? = runCatching {
        val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val peek = activityManager.javaClass.getMethod("peekService", Intent::class.java, String::class.java, String::class.java)
        val intent = Intent(descriptor).setComponent(ComponentName.unflattenFromString(component))
        val binder = peek.invoke(activityManager, intent, null, "com.android.shell") as? IBinder ?: return null
        Class.forName("$descriptor\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)
    }.getOrNull()

    private fun call(service: Any, name: String, vararg args: Any): Any? = runCatching {
        service.javaClass.methods.first { it.name == name && it.parameterTypes.size == args.size }.invoke(service, *args)
    }.getOrNull()
}

/** The command DiPlay sends through adb and the one line [NativeBluetoothTool] answers with. */
internal object NativeBluetoothProtocol {
    const val HEADER = "DIPLAY_NATIVE_BT_V1"
    private val ADDRESS = Regex("[0-9A-F]{2}(?::[0-9A-F]{2}){5}")

    enum class Action(val word: String) {
        FORBID("forbid"),
        ALLOW("allow");

        companion object {
            fun parse(word: String): Action? = entries.firstOrNull { it.word == word }
        }
    }

    class Result(val hfp: Boolean, val a2dpSink: Boolean) {
        val any: Boolean get() = hfp || a2dpSink
    }

    fun validAddress(address: String): Boolean = ADDRESS.matches(address)

    fun command(apk: String, action: Action, address: String, root: Boolean): String {
        require(validAddress(address))
        val quoted = "'" + apk.replace("'", "'\"'\"'") + "'"
        val tool = "app_process /system/bin ${NativeBluetoothTool::class.java.name} ${action.word} $address"
        return if (root) "CLASSPATH=$quoted $tool" else "su -c \"env CLASSPATH=$quoted $tool\""
    }

    fun line(action: Action, hfp: Boolean, a2dpSink: Boolean): String =
        "$HEADER|${action.word}|${if (hfp) 1 else 0}|${if (a2dpSink) 1 else 0}"

    fun parse(output: String?, action: Action): Result? {
        val fields = output?.lineSequence()?.map(String::trim)?.lastOrNull { it.startsWith("$HEADER|") }?.split('|')
            ?: return null
        if (fields.size != 4 || fields[1] != action.word || fields[2] !in BITS || fields[3] !in BITS) return null
        return Result(hfp = fields[2] == "1", a2dpSink = fields[3] == "1")
    }

    private val BITS = setOf("0", "1")
}
