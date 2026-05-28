package com.neop2p.`data`.local.dao

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.neop2p.`data`.local.entity.PeerEntity
import javax.`annotation`.processing.Generated
import kotlin.Float
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class PeerDao_Impl(
  __db: RoomDatabase,
) : PeerDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfPeerEntity: EntityInsertAdapter<PeerEntity>

  private val __deleteAdapterOfPeerEntity: EntityDeleteOrUpdateAdapter<PeerEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfPeerEntity = object : EntityInsertAdapter<PeerEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `peers` (`peer_id`,`nickname`,`nostr_pubkey`,`ln_node_id`,`created_at`,`reputation_score`,`total_trades`,`last_seen`,`relay_hints`,`multiaddrs`) VALUES (?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: PeerEntity) {
        statement.bindText(1, entity.peer_id)
        statement.bindText(2, entity.nickname)
        statement.bindText(3, entity.nostr_pubkey)
        statement.bindText(4, entity.ln_node_id)
        statement.bindLong(5, entity.created_at)
        statement.bindDouble(6, entity.reputation_score.toDouble())
        statement.bindLong(7, entity.total_trades.toLong())
        statement.bindLong(8, entity.last_seen)
        statement.bindText(9, entity.relay_hints)
        statement.bindText(10, entity.multiaddrs)
      }
    }
    this.__deleteAdapterOfPeerEntity = object : EntityDeleteOrUpdateAdapter<PeerEntity>() {
      protected override fun createQuery(): String = "DELETE FROM `peers` WHERE `peer_id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: PeerEntity) {
        statement.bindText(1, entity.peer_id)
      }
    }
  }

  public override suspend fun upsert(peer: PeerEntity): Unit = performSuspending(__db, false, true)
      { _connection ->
    __insertAdapterOfPeerEntity.insert(_connection, peer)
  }

  public override suspend fun delete(peer: PeerEntity): Unit = performSuspending(__db, false, true)
      { _connection ->
    __deleteAdapterOfPeerEntity.handle(_connection, peer)
  }

  public override fun getAllPeers(): Flow<List<PeerEntity>> {
    val _sql: String = "SELECT * FROM peers ORDER BY last_seen DESC"
    return createFlow(__db, false, arrayOf("peers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfPeerId: Int = getColumnIndexOrThrow(_stmt, "peer_id")
        val _columnIndexOfNickname: Int = getColumnIndexOrThrow(_stmt, "nickname")
        val _columnIndexOfNostrPubkey: Int = getColumnIndexOrThrow(_stmt, "nostr_pubkey")
        val _columnIndexOfLnNodeId: Int = getColumnIndexOrThrow(_stmt, "ln_node_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfReputationScore: Int = getColumnIndexOrThrow(_stmt, "reputation_score")
        val _columnIndexOfTotalTrades: Int = getColumnIndexOrThrow(_stmt, "total_trades")
        val _columnIndexOfLastSeen: Int = getColumnIndexOrThrow(_stmt, "last_seen")
        val _columnIndexOfRelayHints: Int = getColumnIndexOrThrow(_stmt, "relay_hints")
        val _columnIndexOfMultiaddrs: Int = getColumnIndexOrThrow(_stmt, "multiaddrs")
        val _result: MutableList<PeerEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: PeerEntity
          val _tmpPeer_id: String
          _tmpPeer_id = _stmt.getText(_columnIndexOfPeerId)
          val _tmpNickname: String
          _tmpNickname = _stmt.getText(_columnIndexOfNickname)
          val _tmpNostr_pubkey: String
          _tmpNostr_pubkey = _stmt.getText(_columnIndexOfNostrPubkey)
          val _tmpLn_node_id: String
          _tmpLn_node_id = _stmt.getText(_columnIndexOfLnNodeId)
          val _tmpCreated_at: Long
          _tmpCreated_at = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpReputation_score: Float
          _tmpReputation_score = _stmt.getDouble(_columnIndexOfReputationScore).toFloat()
          val _tmpTotal_trades: Int
          _tmpTotal_trades = _stmt.getLong(_columnIndexOfTotalTrades).toInt()
          val _tmpLast_seen: Long
          _tmpLast_seen = _stmt.getLong(_columnIndexOfLastSeen)
          val _tmpRelay_hints: String
          _tmpRelay_hints = _stmt.getText(_columnIndexOfRelayHints)
          val _tmpMultiaddrs: String
          _tmpMultiaddrs = _stmt.getText(_columnIndexOfMultiaddrs)
          _item =
              PeerEntity(_tmpPeer_id,_tmpNickname,_tmpNostr_pubkey,_tmpLn_node_id,_tmpCreated_at,_tmpReputation_score,_tmpTotal_trades,_tmpLast_seen,_tmpRelay_hints,_tmpMultiaddrs)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getPeer(peerId: String): Flow<PeerEntity?> {
    val _sql: String = "SELECT * FROM peers WHERE peer_id = ?"
    return createFlow(__db, false, arrayOf("peers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, peerId)
        val _columnIndexOfPeerId: Int = getColumnIndexOrThrow(_stmt, "peer_id")
        val _columnIndexOfNickname: Int = getColumnIndexOrThrow(_stmt, "nickname")
        val _columnIndexOfNostrPubkey: Int = getColumnIndexOrThrow(_stmt, "nostr_pubkey")
        val _columnIndexOfLnNodeId: Int = getColumnIndexOrThrow(_stmt, "ln_node_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfReputationScore: Int = getColumnIndexOrThrow(_stmt, "reputation_score")
        val _columnIndexOfTotalTrades: Int = getColumnIndexOrThrow(_stmt, "total_trades")
        val _columnIndexOfLastSeen: Int = getColumnIndexOrThrow(_stmt, "last_seen")
        val _columnIndexOfRelayHints: Int = getColumnIndexOrThrow(_stmt, "relay_hints")
        val _columnIndexOfMultiaddrs: Int = getColumnIndexOrThrow(_stmt, "multiaddrs")
        val _result: PeerEntity?
        if (_stmt.step()) {
          val _tmpPeer_id: String
          _tmpPeer_id = _stmt.getText(_columnIndexOfPeerId)
          val _tmpNickname: String
          _tmpNickname = _stmt.getText(_columnIndexOfNickname)
          val _tmpNostr_pubkey: String
          _tmpNostr_pubkey = _stmt.getText(_columnIndexOfNostrPubkey)
          val _tmpLn_node_id: String
          _tmpLn_node_id = _stmt.getText(_columnIndexOfLnNodeId)
          val _tmpCreated_at: Long
          _tmpCreated_at = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpReputation_score: Float
          _tmpReputation_score = _stmt.getDouble(_columnIndexOfReputationScore).toFloat()
          val _tmpTotal_trades: Int
          _tmpTotal_trades = _stmt.getLong(_columnIndexOfTotalTrades).toInt()
          val _tmpLast_seen: Long
          _tmpLast_seen = _stmt.getLong(_columnIndexOfLastSeen)
          val _tmpRelay_hints: String
          _tmpRelay_hints = _stmt.getText(_columnIndexOfRelayHints)
          val _tmpMultiaddrs: String
          _tmpMultiaddrs = _stmt.getText(_columnIndexOfMultiaddrs)
          _result =
              PeerEntity(_tmpPeer_id,_tmpNickname,_tmpNostr_pubkey,_tmpLn_node_id,_tmpCreated_at,_tmpReputation_score,_tmpTotal_trades,_tmpLast_seen,_tmpRelay_hints,_tmpMultiaddrs)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun getPeerSync(peerId: String): PeerEntity? {
    val _sql: String = "SELECT * FROM peers WHERE peer_id = ?"
    return performSuspending(__db, true, false) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, peerId)
        val _columnIndexOfPeerId: Int = getColumnIndexOrThrow(_stmt, "peer_id")
        val _columnIndexOfNickname: Int = getColumnIndexOrThrow(_stmt, "nickname")
        val _columnIndexOfNostrPubkey: Int = getColumnIndexOrThrow(_stmt, "nostr_pubkey")
        val _columnIndexOfLnNodeId: Int = getColumnIndexOrThrow(_stmt, "ln_node_id")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfReputationScore: Int = getColumnIndexOrThrow(_stmt, "reputation_score")
        val _columnIndexOfTotalTrades: Int = getColumnIndexOrThrow(_stmt, "total_trades")
        val _columnIndexOfLastSeen: Int = getColumnIndexOrThrow(_stmt, "last_seen")
        val _columnIndexOfRelayHints: Int = getColumnIndexOrThrow(_stmt, "relay_hints")
        val _columnIndexOfMultiaddrs: Int = getColumnIndexOrThrow(_stmt, "multiaddrs")
        val _result: PeerEntity?
        if (_stmt.step()) {
          val _tmpPeer_id: String
          _tmpPeer_id = _stmt.getText(_columnIndexOfPeerId)
          val _tmpNickname: String
          _tmpNickname = _stmt.getText(_columnIndexOfNickname)
          val _tmpNostr_pubkey: String
          _tmpNostr_pubkey = _stmt.getText(_columnIndexOfNostrPubkey)
          val _tmpLn_node_id: String
          _tmpLn_node_id = _stmt.getText(_columnIndexOfLnNodeId)
          val _tmpCreated_at: Long
          _tmpCreated_at = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpReputation_score: Float
          _tmpReputation_score = _stmt.getDouble(_columnIndexOfReputationScore).toFloat()
          val _tmpTotal_trades: Int
          _tmpTotal_trades = _stmt.getLong(_columnIndexOfTotalTrades).toInt()
          val _tmpLast_seen: Long
          _tmpLast_seen = _stmt.getLong(_columnIndexOfLastSeen)
          val _tmpRelay_hints: String
          _tmpRelay_hints = _stmt.getText(_columnIndexOfRelayHints)
          val _tmpMultiaddrs: String
          _tmpMultiaddrs = _stmt.getText(_columnIndexOfMultiaddrs)
          _result =
              PeerEntity(_tmpPeer_id,_tmpNickname,_tmpNostr_pubkey,_tmpLn_node_id,_tmpCreated_at,_tmpReputation_score,_tmpTotal_trades,_tmpLast_seen,_tmpRelay_hints,_tmpMultiaddrs)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
