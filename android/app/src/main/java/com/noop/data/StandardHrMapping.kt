package com.noop.data

import com.noop.protocol.StandardHrContact

/** Durable mapping for standard-BLE contact readings; legacy HR rows have no companion event. */
object StandardHrMapping {
    const val CONTACT_EVENT_KIND = "STANDARD_HR_CONTACT"

    fun contactEvent(ts: Long, contact: StandardHrContact): EventEntry = EventEntry(
        ts = ts,
        kind = CONTACT_EVENT_KIND,
        payloadJSON = "{\"contact\":\"${contact.storageValue}\"}",
    )

    fun contactSample(row: EventRow): StandardHrContactSample? {
        val prefix = "{\"contact\":\""
        val suffix = "\"}"
        if (!row.payloadJSON.startsWith(prefix) || !row.payloadJSON.endsWith(suffix)) return null
        val raw = row.payloadJSON.substring(prefix.length, row.payloadJSON.length - suffix.length)
        return StandardHrContact.fromStorageValue(raw)?.let { StandardHrContactSample(row.ts, it) }
    }
}

data class StandardHrContactSample(val ts: Long, val contact: StandardHrContact)