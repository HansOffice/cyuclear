package org.cyuCBMclean.cyuclear.service

import java.util.UUID

interface DepositBufferSessionView {
    val playerId: UUID
    val sessionId: UUID
}
