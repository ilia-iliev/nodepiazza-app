package com.nodepiazza.protocol

import java.util.UUID

object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000001")
    val CHAT_CHAR_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000003")
    val INTERESTS_CHAR_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000004")

    /** BLE attribute max length; the entire interests payload (header + entries) must fit. */
    const val MAX_PAYLOAD_BYTES = 512
    const val REQUESTED_MTU = 247
}
