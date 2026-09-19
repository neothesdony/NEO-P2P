package com.neop2p.admind.store

import com.neop2p.data.p2p.PendingResolution
import com.neop2p.data.p2p.ResolutionStore
import java.nio.file.Path
import java.sql.ResultSet

/**
 * SQLite-JDBC implementation of [ResolutionStore] for the headless daemon
 * (Phase 1c). Mirrors `PendingArbitrationStore`'s `pending_resolution_*`
 * SharedPreferences rows (the app side is the twin).
 *
 * `targets` is a comma-joined list — RNS peerIds are base58, comma-free.
 */
class SqliteResolutionStore(private val dbPath: Path) : ResolutionStore {

    init {
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS pending_resolutions (" +
                        "escrow_id TEXT NOT NULL PRIMARY KEY, " +
                        "decision TEXT NOT NULL, " +
                        "arbitrator_sig_hex TEXT NOT NULL, " +
                        "notes TEXT, " +
                        "seller_refund_address TEXT, " +
                        "signed_tx_hex TEXT, " +
                        "targets TEXT NOT NULL)"
                )
            }
        }
    }

    override fun save(resolution: PendingResolution) {
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO pending_resolutions (" +
                    "escrow_id, decision, arbitrator_sig_hex, notes, seller_refund_address, signed_tx_hex, targets" +
                    ") VALUES (?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, resolution.escrowId)
                ps.setString(2, resolution.decision)
                ps.setString(3, resolution.arbitratorSigHex)
                ps.setString(4, resolution.notes)
                ps.setString(5, resolution.sellerRefundAddress)
                ps.setString(6, resolution.signedTxHex)
                ps.setString(7, resolution.targets.joinToString(","))
                ps.executeUpdate()
            }
        }
    }

    override fun load(escrowId: String): PendingResolution? =
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement("SELECT * FROM pending_resolutions WHERE escrow_id = ?").use { ps ->
                ps.setString(1, escrowId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.toRecord() else null }
            }
        }

    override fun all(): List<PendingResolution> =
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM pending_resolutions ORDER BY escrow_id").use { rs ->
                    buildList { while (rs.next()) add(rs.toRecord()) }
                }
            }
        }

    override fun remove(escrowId: String) {
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement("DELETE FROM pending_resolutions WHERE escrow_id = ?").use { ps ->
                ps.setString(1, escrowId)
                ps.executeUpdate()
            }
        }
    }

    override fun clear() {
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM pending_resolutions") }
        }
    }

    private fun ResultSet.toRecord(): PendingResolution = PendingResolution(
        escrowId = getString("escrow_id"),
        decision = getString("decision"),
        arbitratorSigHex = getString("arbitrator_sig_hex"),
        notes = getString("notes"),
        sellerRefundAddress = getString("seller_refund_address"),
        signedTxHex = getString("signed_tx_hex"),
        targets = getString("targets").split(",").filter { it.isNotBlank() },
    )
}
