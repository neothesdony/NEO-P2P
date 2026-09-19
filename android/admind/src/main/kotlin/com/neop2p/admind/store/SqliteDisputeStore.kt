package com.neop2p.admind.store

import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.DisputeStore
import java.nio.file.Path
import java.sql.ResultSet

/**
 * SQLite-JDBC implementation of [DisputeStore] for the headless daemon. The
 * table mirrors Room's `arbitrator_disputes` (the app is the party-side twin).
 */
class SqliteDisputeStore(private val dbPath: Path) : DisputeStore {

    init {
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS arbitrator_disputes (" +
                        "escrow_id TEXT NOT NULL PRIMARY KEY, " +
                        "opened_by TEXT NOT NULL, " +
                        "reason TEXT NOT NULL, " +
                        "opened_at INTEGER NOT NULL, " +
                        "redeem_script_hex TEXT, " +
                        "psbt_hex TEXT, " +
                        "refund_tx_hex TEXT, " +
                        "deposit_sats INTEGER, " +
                        "funding_script_type TEXT, " +
                        "seller_refund_address TEXT, " +
                        "buyer_peer_id TEXT, " +
                        "seller_peer_id TEXT, " +
                        "buyer_btc_address TEXT, " +
                        "buyer_pubkey_hex TEXT, " +
                        "seller_pubkey_hex TEXT, " +
                        "seller_refund_attestation TEXT, " +
                        "buyer_address_attestation TEXT, " +
                        "offer_id TEXT, " +
                        "trade_sats INTEGER, " +
                        "received_at INTEGER NOT NULL, " +
                        "resolved INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }
    }

    override fun getById(escrowId: String): DisputeRecord? =
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement("SELECT * FROM arbitrator_disputes WHERE escrow_id = ?").use { ps ->
                ps.setString(1, escrowId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
            }
        }

    override fun countUnresolvedBySender(openedBy: String): Int =
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM arbitrator_disputes WHERE resolved = 0 AND opened_by = ?"
            ).use { ps ->
                ps.setString(1, openedBy)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    override fun upsert(record: DisputeRecord) {
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO arbitrator_disputes (" +
                    "escrow_id, opened_by, reason, opened_at, redeem_script_hex, psbt_hex, " +
                    "refund_tx_hex, deposit_sats, funding_script_type, seller_refund_address, " +
                    "buyer_peer_id, seller_peer_id, buyer_btc_address, buyer_pubkey_hex, " +
                    "seller_pubkey_hex, seller_refund_attestation, buyer_address_attestation, " +
                    "offer_id, trade_sats, received_at, resolved" +
                    ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, record.escrowId)
                ps.setString(2, record.openedBy)
                ps.setString(3, record.reason)
                ps.setLong(4, record.openedAt)
                ps.setString(5, record.redeemScriptHex)
                ps.setString(6, record.psbtHex)
                ps.setString(7, record.refundTxHex)
                Sqlite.setNullableLong(ps, 8, record.depositSats)
                ps.setString(9, record.fundingScriptType)
                ps.setString(10, record.sellerRefundAddress)
                ps.setString(11, record.buyerPeerId)
                ps.setString(12, record.sellerPeerId)
                ps.setString(13, record.buyerBtcAddress)
                ps.setString(14, record.buyerPubkeyHex)
                ps.setString(15, record.sellerPubkeyHex)
                ps.setString(16, record.sellerRefundAttestation)
                ps.setString(17, record.buyerAddressAttestation)
                ps.setString(18, record.offerId)
                Sqlite.setNullableLong(ps, 19, record.tradeSats)
                ps.setLong(20, record.receivedAt)
                ps.setInt(21, if (record.resolved) 1 else 0)
                ps.executeUpdate()
            }
        }
    }

    override fun markResolved(escrowId: String) {
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement("UPDATE arbitrator_disputes SET resolved = 1 WHERE escrow_id = ?").use { ps ->
                ps.setString(1, escrowId)
                ps.executeUpdate()
            }
        }
    }

    override fun all(): List<DisputeRecord> =
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM arbitrator_disputes ORDER BY opened_at DESC").use { rs ->
                    buildList { while (rs.next()) add(rs.toRecord()) }
                }
            }
        }

    override fun clear() {
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM arbitrator_disputes") }
        }
    }

    private fun ResultSet.toRecord(): DisputeRecord = DisputeRecord(
        escrowId = getString("escrow_id"),
        openedBy = getString("opened_by"),
        reason = getString("reason"),
        openedAt = getLong("opened_at"),
        redeemScriptHex = getString("redeem_script_hex"),
        psbtHex = getString("psbt_hex"),
        refundTxHex = getString("refund_tx_hex"),
        depositSats = nullableLong("deposit_sats"),
        fundingScriptType = getString("funding_script_type"),
        sellerRefundAddress = getString("seller_refund_address"),
        buyerPeerId = getString("buyer_peer_id"),
        sellerPeerId = getString("seller_peer_id"),
        buyerBtcAddress = getString("buyer_btc_address"),
        buyerPubkeyHex = getString("buyer_pubkey_hex"),
        sellerPubkeyHex = getString("seller_pubkey_hex"),
        sellerRefundAttestation = getString("seller_refund_attestation"),
        buyerAddressAttestation = getString("buyer_address_attestation"),
        offerId = getString("offer_id"),
        tradeSats = nullableLong("trade_sats"),
        receivedAt = getLong("received_at"),
        resolved = getInt("resolved") != 0,
    )

    private fun ResultSet.nullableLong(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }
}
