package com.shilapi.xcertplay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Disconnects the head unit's own hands-free and Bluetooth-music roles (HFP client, A2DP sink)
 * using the public profile-proxy APIs. Those roles do not need BLUETOOTH_PRIVILEGED. If the stack
 * auto-reconnects the iPhone, a receiver drops the same profiles again while CarPlay is holding
 * them off.
 */
internal object NativeBluetoothProfiles {
    private const val TAG = "DiPlay-NativeBt"
    private const val A2DP_SINK = 11
    private const val AVRCP_CONTROLLER = 12
    private const val HEADSET_CLIENT = 16
    private const val PBAP_CLIENT = 17
    private val PROFILE_IDS = intArrayOf(HEADSET_CLIENT, A2DP_SINK, AVRCP_CONTROLLER, PBAP_CLIENT)
    private const val PRIORITY_OFF = 0
    private const val PRIORITY_ON = 100
    private const val POLICY_FORBIDDEN = 0
    private const val POLICY_ALLOWED = 100

    private val proxies = ConcurrentHashMap<Int, BluetoothProfile>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var guard: BroadcastReceiver? = null
    @Volatile private var guardedAddress: String? = null

    @SuppressLint("MissingPermission")
    fun forbid(context: Context, address: String): Boolean {
        val app = context.applicationContext
        val device = device(address) ?: return false
        bind(app)
        val changed = apply(device, connect = false)
        Log.i(TAG, "in-process forbid address=$address changed=$changed")
        watch(app, address)
        return changed
    }

    @SuppressLint("MissingPermission")
    fun allow(context: Context, address: String): Boolean {
        unwatch(context.applicationContext)
        val device = device(address) ?: return false
        bind(context.applicationContext)
        val changed = apply(device, connect = true)
        Log.i(TAG, "in-process allow address=$address changed=$changed")
        return changed
    }

    fun close(context: Context) {
        unwatch(context.applicationContext)
        val adapter = adapter() ?: return
        for ((id, proxy) in proxies) {
            runCatching { adapter.closeProfileProxy(id, proxy) }
        }
        proxies.clear()
    }

    @Suppress("DEPRECATION")
    private fun adapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    private fun device(address: String): BluetoothDevice? =
        runCatching { adapter()?.getRemoteDevice(address) }.getOrNull()

    private fun bind(context: Context) {
        val adapter = adapter() ?: return
        val missing = PROFILE_IDS.filter { !proxies.containsKey(it) }
        if (missing.isEmpty()) return
        val latch = CountDownLatch(missing.size)
        for (id in missing) {
            val accepted = runCatching {
                adapter.getProfileProxy(
                    context,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            proxies[profile] = proxy
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            proxies.remove(profile)
                        }
                    },
                    id,
                )
            }.getOrDefault(false)
            if (!accepted) latch.countDown()
        }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
    }

    private fun apply(device: BluetoothDevice, connect: Boolean): Boolean {
        var changed = false
        for (id in PROFILE_IDS) {
            val proxy = proxies[id] ?: continue
            if (connect) {
                changed = invoke(proxy, "setConnectionPolicy", device, POLICY_ALLOWED) || changed
                changed = invoke(proxy, "setPriority", device, PRIORITY_ON) || changed
                changed = invoke(proxy, "connect", device) || changed
            } else {
                changed = invoke(proxy, "setConnectionPolicy", device, POLICY_FORBIDDEN) || changed
                changed = invoke(proxy, "setPriority", device, PRIORITY_OFF) || changed
                changed = invoke(proxy, "disconnect", device) || changed
            }
        }
        if (!connect) {
            changed = invoke(device, "disconnect") || changed
            changed = invoke(device, "cancelBondingProcess") || changed
        }
        return changed
    }

    private fun invoke(target: Any, name: String, vararg args: Any): Boolean = runCatching {
        val method = target.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: return false
        val result = method.invoke(target, *args)
        result !is Boolean || result
    }.onFailure { error ->
        Log.w(TAG, "in-process $name failed: ${error.javaClass.simpleName}")
    }.getOrDefault(false)

    private fun watch(context: Context, address: String) {
        guardedAddress = address
        if (guard != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val held = guardedAddress ?: return
                @Suppress("DEPRECATION")
                val device = intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                    ?: return
                if (!held.equals(device.address, ignoreCase = true)) return
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                    state == BluetoothProfile.STATE_CONNECTED ||
                    state == BluetoothProfile.STATE_CONNECTING
                ) {
                    Log.i(TAG, "in-process guard saw ${intent.action}; disconnecting again")
                    apply(device, connect = false)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED")
        }
        main.post {
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("DEPRECATION")
                    context.registerReceiver(receiver, filter)
                }
                guard = receiver
            }.onFailure { Log.w(TAG, "could not watch Bluetooth reconnects", it) }
        }
    }

    private fun unwatch(context: Context) {
        guardedAddress = null
        val receiver = guard ?: return
        guard = null
        main.post { runCatching { context.unregisterReceiver(receiver) } }
    }
}
