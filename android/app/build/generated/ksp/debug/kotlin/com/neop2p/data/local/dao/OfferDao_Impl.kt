package com.neop2p.`data`.local.dao

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.neop2p.`data`.local.entity.TradeOfferEntity
import javax.`annotation`.processing.Generated
import kotlin.Double
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
public class OfferDao_Impl(
  __db: RoomDatabase,
) : OfferDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfTradeOfferEntity: EntityInsertAdapter<TradeOfferEntity>

  private val __deleteAdapterOfTradeOfferEntity: EntityDeleteOrUpdateAdapter<TradeOfferEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfTradeOfferEntity = object : EntityInsertAdapter<TradeOfferEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `trade_offers` (`offer_id`,`creator_peer_id`,`type`,`asset`,`fiat_amount`,`crypto_amount_sats`,`price_per_unit`,`fee_percent`,`fee_sats`,`fiat_methods`,`status`,`created_at`,`nostr_event_id`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: TradeOfferEntity) {
        statement.bindText(1, entity.offer_id)
        statement.bindText(2, entity.creator_peer_id)
        statement.bindText(3, entity.type)
        statement.bindText(4, entity.asset)
        statement.bindLong(5, entity.fiat_amount)
        statement.bindLong(6, entity.crypto_amount_sats)
        statement.bindDouble(7, entity.price_per_unit)
        statement.bindDouble(8, entity.fee_percent)
        statement.bindLong(9, entity.fee_sats)
        statement.bindText(10, entity.fiat_methods)
        statement.bindText(11, entity.status)
        statement.bindLong(12, entity.created_at)
        val _tmpNostr_event_id: String? = entity.nostr_event_id
        if (_tmpNostr_event_id == null) {
          statement.bindNull(13)
        } else {
          statement.bindText(13, _tmpNostr_event_id)
        }
      }
    }
    this.__deleteAdapterOfTradeOfferEntity = object :
        EntityDeleteOrUpdateAdapter<TradeOfferEntity>() {
      protected override fun createQuery(): String =
          "DELETE FROM `trade_offers` WHERE `offer_id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: TradeOfferEntity) {
        statement.bindText(1, entity.offer_id)
      }
    }
  }

  public override suspend fun upsert(offer: TradeOfferEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __insertAdapterOfTradeOfferEntity.insert(_connection, offer)
  }

  public override suspend fun delete(offer: TradeOfferEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __deleteAdapterOfTradeOfferEntity.handle(_connection, offer)
  }

  public override fun getAllOffers(): Flow<List<TradeOfferEntity>> {
    val _sql: String = "SELECT * FROM trade_offers ORDER BY created_at DESC"
    return createFlow(__db, false, arrayOf("trade_offers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfOfferId: Int = getColumnIndexOrThrow(_stmt, "offer_id")
        val _columnIndexOfCreatorPeerId: Int = getColumnIndexOrThrow(_stmt, "creator_peer_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfAsset: Int = getColumnIndexOrThrow(_stmt, "asset")
        val _columnIndexOfFiatAmount: Int = getColumnIndexOrThrow(_stmt, "fiat_amount")
        val _columnIndexOfCryptoAmountSats: Int = getColumnIndexOrThrow(_stmt, "crypto_amount_sats")
        val _columnIndexOfPricePerUnit: Int = getColumnIndexOrThrow(_stmt, "price_per_unit")
        val _columnIndexOfFeePercent: Int = getColumnIndexOrThrow(_stmt, "fee_percent")
        val _columnIndexOfFeeSats: Int = getColumnIndexOrThrow(_stmt, "fee_sats")
        val _columnIndexOfFiatMethods: Int = getColumnIndexOrThrow(_stmt, "fiat_methods")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfNostrEventId: Int = getColumnIndexOrThrow(_stmt, "nostr_event_id")
        val _result: MutableList<TradeOfferEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: TradeOfferEntity
          val _tmpOffer_id: String
          _tmpOffer_id = _stmt.getText(_columnIndexOfOfferId)
          val _tmpCreator_peer_id: String
          _tmpCreator_peer_id = _stmt.getText(_columnIndexOfCreatorPeerId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpAsset: String
          _tmpAsset = _stmt.getText(_columnIndexOfAsset)
          val _tmpFiat_amount: Long
          _tmpFiat_amount = _stmt.getLong(_columnIndexOfFiatAmount)
          val _tmpCrypto_amount_sats: Long
          _tmpCrypto_amount_sats = _stmt.getLong(_columnIndexOfCryptoAmountSats)
          val _tmpPrice_per_unit: Double
          _tmpPrice_per_unit = _stmt.getDouble(_columnIndexOfPricePerUnit)
          val _tmpFee_percent: Double
          _tmpFee_percent = _stmt.getDouble(_columnIndexOfFeePercent)
          val _tmpFee_sats: Long
          _tmpFee_sats = _stmt.getLong(_columnIndexOfFeeSats)
          val _tmpFiat_methods: String
          _tmpFiat_methods = _stmt.getText(_columnIndexOfFiatMethods)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpCreated_at: Long
          _tmpCreated_at = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpNostr_event_id: String?
          if (_stmt.isNull(_columnIndexOfNostrEventId)) {
            _tmpNostr_event_id = null
          } else {
            _tmpNostr_event_id = _stmt.getText(_columnIndexOfNostrEventId)
          }
          _item =
              TradeOfferEntity(_tmpOffer_id,_tmpCreator_peer_id,_tmpType,_tmpAsset,_tmpFiat_amount,_tmpCrypto_amount_sats,_tmpPrice_per_unit,_tmpFee_percent,_tmpFee_sats,_tmpFiat_methods,_tmpStatus,_tmpCreated_at,_tmpNostr_event_id)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getOffer(offerId: String): Flow<TradeOfferEntity?> {
    val _sql: String = "SELECT * FROM trade_offers WHERE offer_id = ?"
    return createFlow(__db, false, arrayOf("trade_offers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, offerId)
        val _columnIndexOfOfferId: Int = getColumnIndexOrThrow(_stmt, "offer_id")
        val _columnIndexOfCreatorPeerId: Int = getColumnIndexOrThrow(_stmt, "creator_peer_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfAsset: Int = getColumnIndexOrThrow(_stmt, "asset")
        val _columnIndexOfFiatAmount: Int = getColumnIndexOrThrow(_stmt, "fiat_amount")
        val _columnIndexOfCryptoAmountSats: Int = getColumnIndexOrThrow(_stmt, "crypto_amount_sats")
        val _columnIndexOfPricePerUnit: Int = getColumnIndexOrThrow(_stmt, "price_per_unit")
        val _columnIndexOfFeePercent: Int = getColumnIndexOrThrow(_stmt, "fee_percent")
        val _columnIndexOfFeeSats: Int = getColumnIndexOrThrow(_stmt, "fee_sats")
        val _columnIndexOfFiatMethods: Int = getColumnIndexOrThrow(_stmt, "fiat_methods")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfNostrEventId: Int = getColumnIndexOrThrow(_stmt, "nostr_event_id")
        val _result: TradeOfferEntity?
        if (_stmt.step()) {
          val _tmpOffer_id: String
          _tmpOffer_id = _stmt.getText(_columnIndexOfOfferId)
          val _tmpCreator_peer_id: String
          _tmpCreator_peer_id = _stmt.getText(_columnIndexOfCreatorPeerId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpAsset: String
          _tmpAsset = _stmt.getText(_columnIndexOfAsset)
          val _tmpFiat_amount: Long
          _tmpFiat_amount = _stmt.getLong(_columnIndexOfFiatAmount)
          val _tmpCrypto_amount_sats: Long
          _tmpCrypto_amount_sats = _stmt.getLong(_columnIndexOfCryptoAmountSats)
          val _tmpPrice_per_unit: Double
          _tmpPrice_per_unit = _stmt.getDouble(_columnIndexOfPricePerUnit)
          val _tmpFee_percent: Double
          _tmpFee_percent = _stmt.getDouble(_columnIndexOfFeePercent)
          val _tmpFee_sats: Long
          _tmpFee_sats = _stmt.getLong(_columnIndexOfFeeSats)
          val _tmpFiat_methods: String
          _tmpFiat_methods = _stmt.getText(_columnIndexOfFiatMethods)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpCreated_at: Long
          _tmpCreated_at = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpNostr_event_id: String?
          if (_stmt.isNull(_columnIndexOfNostrEventId)) {
            _tmpNostr_event_id = null
          } else {
            _tmpNostr_event_id = _stmt.getText(_columnIndexOfNostrEventId)
          }
          _result =
              TradeOfferEntity(_tmpOffer_id,_tmpCreator_peer_id,_tmpType,_tmpAsset,_tmpFiat_amount,_tmpCrypto_amount_sats,_tmpPrice_per_unit,_tmpFee_percent,_tmpFee_sats,_tmpFiat_methods,_tmpStatus,_tmpCreated_at,_tmpNostr_event_id)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getOffersByStatus(status: String): Flow<List<TradeOfferEntity>> {
    val _sql: String = "SELECT * FROM trade_offers WHERE status = ? ORDER BY created_at DESC"
    return createFlow(__db, false, arrayOf("trade_offers")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, status)
        val _columnIndexOfOfferId: Int = getColumnIndexOrThrow(_stmt, "offer_id")
        val _columnIndexOfCreatorPeerId: Int = getColumnIndexOrThrow(_stmt, "creator_peer_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfAsset: Int = getColumnIndexOrThrow(_stmt, "asset")
        val _columnIndexOfFiatAmount: Int = getColumnIndexOrThrow(_stmt, "fiat_amount")
        val _columnIndexOfCryptoAmountSats: Int = getColumnIndexOrThrow(_stmt, "crypto_amount_sats")
        val _columnIndexOfPricePerUnit: Int = getColumnIndexOrThrow(_stmt, "price_per_unit")
        val _columnIndexOfFeePercent: Int = getColumnIndexOrThrow(_stmt, "fee_percent")
        val _columnIndexOfFeeSats: Int = getColumnIndexOrThrow(_stmt, "fee_sats")
        val _columnIndexOfFiatMethods: Int = getColumnIndexOrThrow(_stmt, "fiat_methods")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfNostrEventId: Int = getColumnIndexOrThrow(_stmt, "nostr_event_id")
        val _result: MutableList<TradeOfferEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: TradeOfferEntity
          val _tmpOffer_id: String
          _tmpOffer_id = _stmt.getText(_columnIndexOfOfferId)
          val _tmpCreator_peer_id: String
          _tmpCreator_peer_id = _stmt.getText(_columnIndexOfCreatorPeerId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpAsset: String
          _tmpAsset = _stmt.getText(_columnIndexOfAsset)
          val _tmpFiat_amount: Long
          _tmpFiat_amount = _stmt.getLong(_columnIndexOfFiatAmount)
          val _tmpCrypto_amount_sats: Long
          _tmpCrypto_amount_sats = _stmt.getLong(_columnIndexOfCryptoAmountSats)
          val _tmpPrice_per_unit: Double
          _tmpPrice_per_unit = _stmt.getDouble(_columnIndexOfPricePerUnit)
          val _tmpFee_percent: Double
          _tmpFee_percent = _stmt.getDouble(_columnIndexOfFeePercent)
          val _tmpFee_sats: Long
          _tmpFee_sats = _stmt.getLong(_columnIndexOfFeeSats)
          val _tmpFiat_methods: String
          _tmpFiat_methods = _stmt.getText(_columnIndexOfFiatMethods)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpCreated_at: Long
          _tmpCreated_at = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpNostr_event_id: String?
          if (_stmt.isNull(_columnIndexOfNostrEventId)) {
            _tmpNostr_event_id = null
          } else {
            _tmpNostr_event_id = _stmt.getText(_columnIndexOfNostrEventId)
          }
          _item =
              TradeOfferEntity(_tmpOffer_id,_tmpCreator_peer_id,_tmpType,_tmpAsset,_tmpFiat_amount,_tmpCrypto_amount_sats,_tmpPrice_per_unit,_tmpFee_percent,_tmpFee_sats,_tmpFiat_methods,_tmpStatus,_tmpCreated_at,_tmpNostr_event_id)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateStatus(offerId: String, status: String) {
    val _sql: String = "UPDATE trade_offers SET status = ? WHERE offer_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, status)
        _argIndex = 2
        _stmt.bindText(_argIndex, offerId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
