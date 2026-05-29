package com.neop2p.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Identity(
    val peerId: String,
    val nickname: String,
    val nostrPubkeyHex: String,
    val lnNodeId: String,
    val createdAt: Long,
    val updatedAt: Long,
    var isBackupExported: Boolean = false
)