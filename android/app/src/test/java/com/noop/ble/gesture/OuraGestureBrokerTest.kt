package com.noop.ble.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OuraGestureBrokerTest {
    private val token = "synthetic_fixture_token_not_a_credential"
    @Test fun pairingRequiresOwnerHttpsOriginAndDistinctToken() {
        assertEquals("https://broker.example" to token,
            OuraGestureBroker.parsePairing("z0live://pair?url=https%3A%2F%2Fbroker.example&token=$token"))
    }
    @Test fun malformedUntrustedLinksFailClosed() {
        listOf(
            "z0live://pair?url=http%3A%2F%2Fbroker.example&token=$token",
            "z0live://pair?url=https%3A%2F%2Fu%3Ap%40broker.example&token=$token",
            "z0live://pair?url=https%3A%2F%2Fbroker.example%2Fpath&token=$token",
            "z0live://pair?url=https%3A%2F%2Fbroker.example&token=$token&token=$token",
            "z0live://pair?url=https%3A%2F%2Fbroker.example&token=bad",
            "https://pair?url=https%3A%2F%2Fbroker.example&token=$token"
        ).forEach { link -> assertThrows(IllegalArgumentException::class.java) { OuraGestureBroker.parsePairing(link) } }
    }
}
