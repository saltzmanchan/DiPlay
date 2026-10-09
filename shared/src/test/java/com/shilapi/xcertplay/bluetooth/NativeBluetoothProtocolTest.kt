package com.shilapi.xcertplay.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBluetoothProtocolTest {
    private val address = "44:A7:F4:77:D1:46"

    @Test
    fun rootCommandRunsTheToolFromTheQuotedApk() {
        val command = NativeBluetoothProtocol.command("/data/app/x'y/base.apk", NativeBluetoothProtocol.Action.FORBID, address, root = true)
        assertEquals(
            "CLASSPATH='/data/app/x'\"'\"'y/base.apk' app_process /system/bin " +
                "${NativeBluetoothTool::class.java.name} forbid $address",
            command,
        )
    }

    @Test
    fun shellCommandEscalatesWithSu() {
        val command = NativeBluetoothProtocol.command("/a.apk", NativeBluetoothProtocol.Action.ALLOW, address, root = false)
        assertTrue(command.startsWith("su 0 env CLASSPATH='/a.apk' app_process "))
        assertTrue(command.endsWith(" allow $address"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun commandRejectsAnAddressThatCouldCarryShellText() {
        NativeBluetoothProtocol.command("/a.apk", NativeBluetoothProtocol.Action.FORBID, "$address; reboot", root = true)
    }

    @Test
    fun parseReadsTheToolLineAfterOtherOutput() {
        val output = "W/BluetoothAdapter: warning\n" + NativeBluetoothProtocol.line(NativeBluetoothProtocol.Action.FORBID, true, false)
        val result = NativeBluetoothProtocol.parse(output, NativeBluetoothProtocol.Action.FORBID)!!
        assertTrue(result.hfp)
        assertFalse(result.a2dpSink)
        assertTrue(result.any)
    }

    @Test
    fun parseRejectsMissingWrongActionAndMalformedLines() {
        assertNull(NativeBluetoothProtocol.parse(null, NativeBluetoothProtocol.Action.FORBID))
        assertNull(NativeBluetoothProtocol.parse("su: permission denied", NativeBluetoothProtocol.Action.FORBID))
        val allowLine = NativeBluetoothProtocol.line(NativeBluetoothProtocol.Action.ALLOW, true, true)
        assertNull(NativeBluetoothProtocol.parse(allowLine, NativeBluetoothProtocol.Action.FORBID))
        assertNull(NativeBluetoothProtocol.parse("${NativeBluetoothProtocol.HEADER}|forbid|1|2", NativeBluetoothProtocol.Action.FORBID))
    }

    @Test
    fun addressesMustBeUppercaseColonSeparated() {
        assertTrue(NativeBluetoothProtocol.validAddress(address))
        assertFalse(NativeBluetoothProtocol.validAddress("44:a7:f4:77:d1:46"))
        assertFalse(NativeBluetoothProtocol.validAddress("44A7F477D146"))
    }
}
