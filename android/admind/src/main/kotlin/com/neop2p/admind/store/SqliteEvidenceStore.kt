package com.neop2p.admind.store

import com.neop2p.data.p2p.EvidenceRecord
import com.neop2p.data.p2p.EvidenceStore
import java.nio.file.Path
import java.sql.ResultSet

/**
 * SQLite-JDBC implementation of [EvidenceStore] for the headless daemon.
 * Mirrors Room's `dispute_evidence`, including the `INSERT OR REPLACE`
 * conflict strategy the app's DAO uses.
 */
class SqliteEvidenceStore(private val dbPath: Path) : EvidenceStore {

    init {
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS dispute_evidence (" +
                        "evidence_id TEXT NOT NULL PRIMARY KEY, " +
                        "escrow_id TEXT NOT NULL, " +
                        "submitter_peer_id TEXT NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "mime_type TEXT NOT NULL, " +
                        "image_data BLOB NOT NULL, " +
                        "submitted_at INTEGER NOT NULL)"
                )
            }
        }
    }

    override fun forEscrow(escrowId: String): List<EvidenceRecord> =
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement(
                "SELECT * FROM dispute_evidence WHERE escrow_id = ? ORDER BY submitted_at ASC"
            ).use { ps ->
                ps.setString(1, escrowId)
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRecord()) } }
            }
        }

    override fun insert(record: EvidenceRecord) {
        Sqlite.withConnection(dbPath) { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO dispute_evidence (" +
                    "evidence_id, escrow_id, submitter_peer_id, description, mime_type, image_data, submitted_at" +
                    ") VALUES (?,?,?,?,?,?,?)"
            ).use { ps ->
                ps.setString(1, record.evidenceId)
                ps.setString(2, record.escrowId)
                ps.setString(3, record.submitterPeerId)
                ps.setString(4, record.description)
                ps.setString(5, record.mimeType)
                ps.setBytes(6, record.imageData)
                ps.setLong(7, record.submittedAt)
                ps.executeUpdate()
            }
        }
    }

    override fun all(): List<EvidenceRecord> =
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT * FROM dispute_evidence ORDER BY submitted_at ASC").use { rs ->
                    buildList { while (rs.next()) add(rs.toRecord()) }
                }
            }
        }

    override fun clear() {
        Sqlite.withConnection(dbPath) { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM dispute_evidence") }
        }
    }

    private fun ResultSet.toRecord(): EvidenceRecord = EvidenceRecord(
        evidenceId = getString("evidence_id"),
        escrowId = getString("escrow_id"),
        submitterPeerId = getString("submitter_peer_id"),
        description = getString("description"),
        mimeType = getString("mime_type"),
        imageData = getBytes("image_data") ?: ByteArray(0),
        submittedAt = getLong("submitted_at"),
    )
}
