package com.neop2p.`data`.local

import androidx.room.InvalidationTracker
import androidx.room.RoomOpenDelegate
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.room.util.TableInfo
import androidx.room.util.TableInfo.Companion.read
import androidx.room.util.dropFtsSyncTriggers
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.neop2p.`data`.local.dao.ChatMessageDao
import com.neop2p.`data`.local.dao.ChatMessageDao_Impl
import com.neop2p.`data`.local.dao.EscrowDao
import com.neop2p.`data`.local.dao.EscrowDao_Impl
import com.neop2p.`data`.local.dao.OfferDao
import com.neop2p.`data`.local.dao.OfferDao_Impl
import com.neop2p.`data`.local.dao.PeerDao
import com.neop2p.`data`.local.dao.PeerDao_Impl
import javax.`annotation`.processing.Generated
import kotlin.Lazy
import kotlin.String
import kotlin.Suppress
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.collections.Set
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.reflect.KClass

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class AppDatabase_Impl : AppDatabase() {
  private val _peerDao: Lazy<PeerDao> = lazy {
    PeerDao_Impl(this)
  }

  private val _offerDao: Lazy<OfferDao> = lazy {
    OfferDao_Impl(this)
  }

  private val _escrowDao: Lazy<EscrowDao> = lazy {
    EscrowDao_Impl(this)
  }

  private val _chatMessageDao: Lazy<ChatMessageDao> = lazy {
    ChatMessageDao_Impl(this)
  }

  protected override fun createOpenDelegate(): RoomOpenDelegate {
    val _openDelegate: RoomOpenDelegate = object : RoomOpenDelegate(1,
        "141642e36cffd2e97814a9d046015865", "b0b4768b0cfa3ad1bf040e3681967eb9") {
      public override fun createAllTables(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `peers` (`peer_id` TEXT NOT NULL, `nickname` TEXT NOT NULL, `nostr_pubkey` TEXT NOT NULL, `ln_node_id` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `reputation_score` REAL NOT NULL, `total_trades` INTEGER NOT NULL, `last_seen` INTEGER NOT NULL, `relay_hints` TEXT NOT NULL, `multiaddrs` TEXT NOT NULL, PRIMARY KEY(`peer_id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `trade_offers` (`offer_id` TEXT NOT NULL, `creator_peer_id` TEXT NOT NULL, `type` TEXT NOT NULL, `asset` TEXT NOT NULL, `fiat_amount` INTEGER NOT NULL, `crypto_amount_sats` INTEGER NOT NULL, `price_per_unit` REAL NOT NULL, `fee_percent` REAL NOT NULL, `fee_sats` INTEGER NOT NULL, `fiat_methods` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `nostr_event_id` TEXT, PRIMARY KEY(`offer_id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `escrows` (`escrow_id` TEXT NOT NULL, `offer_id` TEXT NOT NULL, `type` TEXT NOT NULL, `funding_tx_id` TEXT, `payout_tx_id` TEXT, `deposit_amount_sats` INTEGER NOT NULL, `trade_amount_sats` INTEGER NOT NULL, `fee_amount_sats` INTEGER NOT NULL, `fee_address` TEXT NOT NULL, `buyer_peer_id` TEXT NOT NULL, `seller_peer_id` TEXT NOT NULL, `status` TEXT NOT NULL, `buyer_signature` BLOB, `seller_signature` BLOB, `channel_point` TEXT, `created_at` INTEGER NOT NULL, `released_at` INTEGER, PRIMARY KEY(`escrow_id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `chat_messages` (`message_id` TEXT NOT NULL, `offer_id` TEXT NOT NULL, `sender_peer_id` TEXT NOT NULL, `ciphertext` BLOB NOT NULL, `ratchet_key` BLOB, `is_read` INTEGER NOT NULL, `sent_at` INTEGER NOT NULL, `delivered_at` INTEGER, `file_attachment` BLOB, PRIMARY KEY(`message_id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS `payment_proofs` (`proof_id` TEXT NOT NULL, `escrow_id` TEXT NOT NULL, `media_type` TEXT NOT NULL, `encrypted_data` BLOB NOT NULL, `received_at` INTEGER NOT NULL, `verified` INTEGER NOT NULL, PRIMARY KEY(`proof_id`))")
        connection.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        connection.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '141642e36cffd2e97814a9d046015865')")
      }

      public override fun dropAllTables(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS `peers`")
        connection.execSQL("DROP TABLE IF EXISTS `trade_offers`")
        connection.execSQL("DROP TABLE IF EXISTS `escrows`")
        connection.execSQL("DROP TABLE IF EXISTS `chat_messages`")
        connection.execSQL("DROP TABLE IF EXISTS `payment_proofs`")
      }

      public override fun onCreate(connection: SQLiteConnection) {
      }

      public override fun onOpen(connection: SQLiteConnection) {
        internalInitInvalidationTracker(connection)
      }

      public override fun onPreMigrate(connection: SQLiteConnection) {
        dropFtsSyncTriggers(connection)
      }

      public override fun onPostMigrate(connection: SQLiteConnection) {
      }

      public override fun onValidateSchema(connection: SQLiteConnection):
          RoomOpenDelegate.ValidationResult {
        val _columnsPeers: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPeers.put("peer_id", TableInfo.Column("peer_id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("nickname", TableInfo.Column("nickname", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("nostr_pubkey", TableInfo.Column("nostr_pubkey", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("ln_node_id", TableInfo.Column("ln_node_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("reputation_score", TableInfo.Column("reputation_score", "REAL", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("total_trades", TableInfo.Column("total_trades", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("last_seen", TableInfo.Column("last_seen", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("relay_hints", TableInfo.Column("relay_hints", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPeers.put("multiaddrs", TableInfo.Column("multiaddrs", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPeers: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesPeers: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoPeers: TableInfo = TableInfo("peers", _columnsPeers, _foreignKeysPeers,
            _indicesPeers)
        val _existingPeers: TableInfo = read(connection, "peers")
        if (!_infoPeers.equals(_existingPeers)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |peers(com.neop2p.data.local.entity.PeerEntity).
              | Expected:
              |""".trimMargin() + _infoPeers + """
              |
              | Found:
              |""".trimMargin() + _existingPeers)
        }
        val _columnsTradeOffers: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsTradeOffers.put("offer_id", TableInfo.Column("offer_id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("creator_peer_id", TableInfo.Column("creator_peer_id", "TEXT", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("type", TableInfo.Column("type", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("asset", TableInfo.Column("asset", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("fiat_amount", TableInfo.Column("fiat_amount", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("crypto_amount_sats", TableInfo.Column("crypto_amount_sats",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("price_per_unit", TableInfo.Column("price_per_unit", "REAL", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("fee_percent", TableInfo.Column("fee_percent", "REAL", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("fee_sats", TableInfo.Column("fee_sats", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("fiat_methods", TableInfo.Column("fiat_methods", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("status", TableInfo.Column("status", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsTradeOffers.put("nostr_event_id", TableInfo.Column("nostr_event_id", "TEXT", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysTradeOffers: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesTradeOffers: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoTradeOffers: TableInfo = TableInfo("trade_offers", _columnsTradeOffers,
            _foreignKeysTradeOffers, _indicesTradeOffers)
        val _existingTradeOffers: TableInfo = read(connection, "trade_offers")
        if (!_infoTradeOffers.equals(_existingTradeOffers)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |trade_offers(com.neop2p.data.local.entity.TradeOfferEntity).
              | Expected:
              |""".trimMargin() + _infoTradeOffers + """
              |
              | Found:
              |""".trimMargin() + _existingTradeOffers)
        }
        val _columnsEscrows: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsEscrows.put("escrow_id", TableInfo.Column("escrow_id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("offer_id", TableInfo.Column("offer_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("type", TableInfo.Column("type", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("funding_tx_id", TableInfo.Column("funding_tx_id", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("payout_tx_id", TableInfo.Column("payout_tx_id", "TEXT", false, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("deposit_amount_sats", TableInfo.Column("deposit_amount_sats",
            "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("trade_amount_sats", TableInfo.Column("trade_amount_sats", "INTEGER",
            true, 0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("fee_amount_sats", TableInfo.Column("fee_amount_sats", "INTEGER", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("fee_address", TableInfo.Column("fee_address", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("buyer_peer_id", TableInfo.Column("buyer_peer_id", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("seller_peer_id", TableInfo.Column("seller_peer_id", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("status", TableInfo.Column("status", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("buyer_signature", TableInfo.Column("buyer_signature", "BLOB", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("seller_signature", TableInfo.Column("seller_signature", "BLOB", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("channel_point", TableInfo.Column("channel_point", "TEXT", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("created_at", TableInfo.Column("created_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsEscrows.put("released_at", TableInfo.Column("released_at", "INTEGER", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysEscrows: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesEscrows: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoEscrows: TableInfo = TableInfo("escrows", _columnsEscrows, _foreignKeysEscrows,
            _indicesEscrows)
        val _existingEscrows: TableInfo = read(connection, "escrows")
        if (!_infoEscrows.equals(_existingEscrows)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |escrows(com.neop2p.data.local.entity.EscrowEntity).
              | Expected:
              |""".trimMargin() + _infoEscrows + """
              |
              | Found:
              |""".trimMargin() + _existingEscrows)
        }
        val _columnsChatMessages: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsChatMessages.put("message_id", TableInfo.Column("message_id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("offer_id", TableInfo.Column("offer_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("sender_peer_id", TableInfo.Column("sender_peer_id", "TEXT", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("ciphertext", TableInfo.Column("ciphertext", "BLOB", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("ratchet_key", TableInfo.Column("ratchet_key", "BLOB", false, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("is_read", TableInfo.Column("is_read", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("sent_at", TableInfo.Column("sent_at", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("delivered_at", TableInfo.Column("delivered_at", "INTEGER", false,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsChatMessages.put("file_attachment", TableInfo.Column("file_attachment", "BLOB",
            false, 0, null, TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysChatMessages: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesChatMessages: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoChatMessages: TableInfo = TableInfo("chat_messages", _columnsChatMessages,
            _foreignKeysChatMessages, _indicesChatMessages)
        val _existingChatMessages: TableInfo = read(connection, "chat_messages")
        if (!_infoChatMessages.equals(_existingChatMessages)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |chat_messages(com.neop2p.data.local.entity.ChatMessageEntity).
              | Expected:
              |""".trimMargin() + _infoChatMessages + """
              |
              | Found:
              |""".trimMargin() + _existingChatMessages)
        }
        val _columnsPaymentProofs: MutableMap<String, TableInfo.Column> = mutableMapOf()
        _columnsPaymentProofs.put("proof_id", TableInfo.Column("proof_id", "TEXT", true, 1, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPaymentProofs.put("escrow_id", TableInfo.Column("escrow_id", "TEXT", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        _columnsPaymentProofs.put("media_type", TableInfo.Column("media_type", "TEXT", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPaymentProofs.put("encrypted_data", TableInfo.Column("encrypted_data", "BLOB", true,
            0, null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPaymentProofs.put("received_at", TableInfo.Column("received_at", "INTEGER", true, 0,
            null, TableInfo.CREATED_FROM_ENTITY))
        _columnsPaymentProofs.put("verified", TableInfo.Column("verified", "INTEGER", true, 0, null,
            TableInfo.CREATED_FROM_ENTITY))
        val _foreignKeysPaymentProofs: MutableSet<TableInfo.ForeignKey> = mutableSetOf()
        val _indicesPaymentProofs: MutableSet<TableInfo.Index> = mutableSetOf()
        val _infoPaymentProofs: TableInfo = TableInfo("payment_proofs", _columnsPaymentProofs,
            _foreignKeysPaymentProofs, _indicesPaymentProofs)
        val _existingPaymentProofs: TableInfo = read(connection, "payment_proofs")
        if (!_infoPaymentProofs.equals(_existingPaymentProofs)) {
          return RoomOpenDelegate.ValidationResult(false, """
              |payment_proofs(com.neop2p.data.local.entity.PaymentProofEntity).
              | Expected:
              |""".trimMargin() + _infoPaymentProofs + """
              |
              | Found:
              |""".trimMargin() + _existingPaymentProofs)
        }
        return RoomOpenDelegate.ValidationResult(true, null)
      }
    }
    return _openDelegate
  }

  protected override fun createInvalidationTracker(): InvalidationTracker {
    val _shadowTablesMap: MutableMap<String, String> = mutableMapOf()
    val _viewTables: MutableMap<String, Set<String>> = mutableMapOf()
    return InvalidationTracker(this, _shadowTablesMap, _viewTables, "peers", "trade_offers",
        "escrows", "chat_messages", "payment_proofs")
  }

  public override fun clearAllTables() {
    super.performClear(false, "peers", "trade_offers", "escrows", "chat_messages", "payment_proofs")
  }

  protected override fun getRequiredTypeConverterClasses(): Map<KClass<*>, List<KClass<*>>> {
    val _typeConvertersMap: MutableMap<KClass<*>, List<KClass<*>>> = mutableMapOf()
    _typeConvertersMap.put(PeerDao::class, PeerDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(OfferDao::class, OfferDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(EscrowDao::class, EscrowDao_Impl.getRequiredConverters())
    _typeConvertersMap.put(ChatMessageDao::class, ChatMessageDao_Impl.getRequiredConverters())
    return _typeConvertersMap
  }

  public override fun getRequiredAutoMigrationSpecClasses(): Set<KClass<out AutoMigrationSpec>> {
    val _autoMigrationSpecsSet: MutableSet<KClass<out AutoMigrationSpec>> = mutableSetOf()
    return _autoMigrationSpecsSet
  }

  public override
      fun createAutoMigrations(autoMigrationSpecs: Map<KClass<out AutoMigrationSpec>, AutoMigrationSpec>):
      List<Migration> {
    val _autoMigrations: MutableList<Migration> = mutableListOf()
    return _autoMigrations
  }

  public override fun peerDao(): PeerDao = _peerDao.value

  public override fun offerDao(): OfferDao = _offerDao.value

  public override fun escrowDao(): EscrowDao = _escrowDao.value

  public override fun chatMessageDao(): ChatMessageDao = _chatMessageDao.value
}
