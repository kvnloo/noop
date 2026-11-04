package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android consent gate must precede device discovery, regardless of scanner type. */
class BleScanGateTest {
    @Test
    fun missingPermissions_precedesRadioPrompt() {
        assertEquals(
            BleScanAction.REQUEST_PERMISSION,
            bleScanAction(permissionsGranted = false, hasAdapter = true, bluetoothEnabled = false),
        )
    }

    @Test
    fun radioOff_requestsSystemEnableInsteadOfScanning() {
        assertEquals(
            BleScanAction.REQUEST_ENABLE,
            bleScanAction(permissionsGranted = true, hasAdapter = true, bluetoothEnabled = false),
        )
    }

    @Test
    fun radioAlreadyEnabled_scansWithoutPrompt() {
        assertEquals(
            BleScanAction.START_SCAN,
            bleScanAction(permissionsGranted = true, hasAdapter = true, bluetoothEnabled = true),
        )
    }

    @Test
    fun noBluetoothAdapter_neverScans() {
        assertEquals(
            BleScanAction.UNAVAILABLE,
            bleScanAction(permissionsGranted = true, hasAdapter = false, bluetoothEnabled = false),
        )
    }

    @Test
    fun deniedOrIncompleteEnable_neverResumes() {
        assertFalse(shouldResumeBleScan(accepted = false, bluetoothEnabled = false))
        assertFalse(shouldResumeBleScan(accepted = false, bluetoothEnabled = true))
        assertFalse(shouldResumeBleScan(accepted = true, bluetoothEnabled = false))
        assertTrue(shouldResumeBleScan(accepted = true, bluetoothEnabled = true))
    }
}
