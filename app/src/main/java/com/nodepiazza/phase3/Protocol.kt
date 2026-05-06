package com.nodepiazza.phase3

import java.util.UUID

object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000001")
    val EMBEDDING_CHAR_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000002")
    val CHAT_CHAR_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000003")
    val PROMPTS_CHAR_UUID: UUID = UUID.fromString("9d3c4e10-8e2d-4fa0-a1b2-000000000004")

    const val EMBEDDING_DIMS = 128
    const val MATCH_THRESHOLD = 0.55f
    const val MAX_PROMPTS_PER_DEVICE = 8
    const val MAX_PROMPT_BYTES = 256
    const val REQUESTED_MTU = 247
}
