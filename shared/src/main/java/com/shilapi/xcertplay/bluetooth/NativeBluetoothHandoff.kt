package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.AdbTls
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * After wireless CarPlay is on Wi-Fi, the head unit's own hands-free and Bluetooth music links to
 * that iPhone are turned off. The first path is in-process (HFP client / A2DP sink profile proxies)
 * because ordinary Bluetooth permission is enough to disconnect those roles on an automotive head
 * unit. Root `su` / local adbd remains a best-effort way to also set connection policy to forbidden.
 *
 * [restore] reconnects those profiles only when CarPlay has ended.
 */
object NativeBluetoothHandoff {
    private const val TAG = "DiPlay-NativeBt"
    private const val PREFS = "native_bluetooth_handoff"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RELEASED = "released_addresses"
    private const val TOOL_TIMEOUT_MILLIS = 15_000
    private val SU_BINARIES = listOf("su", "/system/xbin/su", "/system/bin/su", "/sbin/su")

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "diplay-native-bt").apply { isDaemon = true } }

    /** Dedicated pool for draining su stdout/stderr so a blocked pipe never stalls [worker]. */
    private val streams = Executors.newCachedThreadPool { Thread(it, "diplay-native-bt-io").apply { isDaemon = true } }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) restore(context)
    }

    fun release(context: Context, address: String) {
        val app = context.applicationContext
        val normalized = address.uppercase()
        if (!enabled(app) || !NativeBluetoothProtocol.validAddress(normalized)) {
            Log.w(TAG, "release skipped: enabled=${enabled(app)} addressValid=${NativeBluetoothProtocol.validAddress(normalized)}")
            return
        }
        worker.execute {
            if (!journal(app) { it + normalized }) {
                Log.w(TAG, "release skipped: journal could not be saved")
                return@execute
            }
            val local = NativeBluetoothProfiles.forbid(app, normalized)
            val root = runTool(app, NativeBluetoothProtocol.Action.FORBID, normalized)
            Log.i(TAG, "released car Bluetooth inProcess=$local hfp=${root?.hfp} a2dpSink=${root?.a2dpSink}")
        }
    }

    fun restore(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val pending = released(app)
            if (pending.isEmpty()) {
                NativeBluetoothProfiles.close(app)
                return@execute
            }
            for (address in pending) {
                val local = NativeBluetoothProfiles.allow(app, address)
                val root = runTool(app, NativeBluetoothProtocol.Action.ALLOW, address)
                Log.i(TAG, "restored car Bluetooth inProcess=$local hfp=${root?.hfp} a2dpSink=${root?.a2dpSink}")
                if (local || root?.any == true) journal(app) { it - address }
            }
            NativeBluetoothProfiles.close(app)
        }
    }

    private fun runTool(
        context: Context,
        action: NativeBluetoothProtocol.Action,
        address: String,
    ): NativeBluetoothProtocol.Result? {
        val apk = context.applicationInfo.sourceDir
        val asRoot = NativeBluetoothProtocol.command(apk, action, address, root = true)
        val throughSu = NativeBluetoothProtocol.command(apk, action, address, root = false)
        val fromSu = parseTool(execSu(asRoot), action, "su")
        if (fromSu != null) return fromSu
        return parseTool(execAdb(context, asRoot, throughSu), action, "adb")
    }

    private fun parseTool(output: String?, action: NativeBluetoothProtocol.Action, via: String): NativeBluetoothProtocol.Result? {
        if (output == null) return null
        val result = NativeBluetoothProtocol.parse(output, action)
        if (result == null) Log.w(TAG, "tool via $via did not answer: ${output.take(240)}")
        return result
    }

    /**
     * Runs [command] as root through a one-shot `su -c` process, mirroring 哪吒美式's root runner:
     * the classic `{su, "-c", command}` argv (not the Magisk `su 0 sh -c` form), stdout and stderr
     * drained on separate threads so neither pipe can block the other, and a bounded wait that force
     * kills the process on timeout. The built-in `su` on this head unit only accepts the `-c` form.
     */
    private fun execSu(command: String): String? {
        for (su in SU_BINARIES) {
            val result = runCatching { runSu(su, command) }.getOrNull() ?: continue
            if (result.exitCode == 0 || result.stdout.isNotBlank()) {
                Log.i(TAG, "su binary=$su exit=${result.exitCode} ran the Bluetooth tool")
                return result.stdout
            }
            if (result.stderr.isNotBlank()) Log.w(TAG, "su binary=$su exit=${result.exitCode}: ${result.stderr.take(240)}")
        }
        return null
    }

    private class SuResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runSu(su: String, command: String): SuResult {
        val process = ProcessBuilder(su, "-c", command).redirectErrorStream(false).start()
        process.outputStream.close()
        val stdout = streams.submit<String> { process.inputStream.bufferedReader().use { it.readText() } }
        val stderr = streams.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
        val finished = process.waitFor(TOOL_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "su binary=$su timed out after ${TOOL_TIMEOUT_MILLIS}ms")
            return SuResult(-1, drain(stdout), drain(stderr))
        }
        return SuResult(process.exitValue(), drain(stdout), drain(stderr))
    }

    private fun drain(future: java.util.concurrent.Future<String>): String =
        runCatching { future.get(5, TimeUnit.SECONDS) }.getOrDefault("")

    private fun execAdb(context: Context, asRoot: String, throughSu: String): String? {
        val key = AdbKeys.load(context)
        for (port in AdbTls.candidatePorts()) {
            LocalAdb(key, port = port).use { adb ->
                val access = adb.connect(mayAsk = false)
                if (access != LocalAdb.Access.READY) {
                    Log.w(TAG, "ADB port=$port access=$access")
                    return@use
                }
                val root = adb.shell("id -u") == "0"
                if (!root && adb.shell("su -c id -u") != "0") {
                    Log.w(TAG, "ADB port=$port has no root")
                    return@use
                }
                Log.i(TAG, "ADB port=$port root=$root")
                return adb.shell(if (root) asRoot else throughSu, TOOL_TIMEOUT_MILLIS)
            }
        }
        return null
    }

    private fun released(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_RELEASED, emptySet()).orEmpty()
            .filterTo(LinkedHashSet(), NativeBluetoothProtocol::validAddress)

    private fun journal(context: Context, change: (Set<String>) -> Set<String>): Boolean =
        prefs(context).edit().putStringSet(KEY_RELEASED, change(released(context))).commit()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
