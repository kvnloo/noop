package com.noop.data

import com.noop.protocol.StandardHrContact
import org.junit.Assert.assertEquals
import org.junit.Test

class StandardHrMappingTest {
    @Test
    fun contactUsesSwiftParityEventShape() {
        assertEquals(
            EventEntry(
                ts = 1_750_000_000L,
                kind = "STANDARD_HR_CONTACT",
                payloadJSON = "{\"contact\":\"supported_not_detected\"}",
            ),
            StandardHrMapping.contactEvent(
                1_750_000_000L,
                StandardHrContact.SUPPORTED_NOT_DETECTED,
            ),
        )
    }

    @Test
    fun contactReadDecodesTypedValueAndRejectsMalformedRows() {
        val row = EventRow(
            deviceId = "whoop-5",
            ts = 1_750_000_001L,
            kind = StandardHrMapping.CONTACT_EVENT_KIND,
            payloadJSON = "{\"contact\":\"supported_detected\"}",
        )
        assertEquals(
            StandardHrContactSample(1_750_000_001L, StandardHrContact.SUPPORTED_DETECTED),
            StandardHrMapping.contactSample(row),
        )
        assertEquals(null, StandardHrMapping.contactSample(row.copy(payloadJSON = "{}")))
    }
}
