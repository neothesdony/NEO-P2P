package com.neop2p.`data`.local.dao

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.neop2p.`data`.local.entity.EscrowEntity
import javax.`annotation`.processing.Generated
import kotlin.ByteArray
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
public class EscrowDao_Impl(
  __db: RoomDatabase,
) : EscrowDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfEscrowEntity: EntityInsertAdapter<EscrowEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfEscrowEntity = object : EntityInsertAdapter<EscrowEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `escrows` (`escrow_id`,`offer_id`,`type`,`funding_tx_id`,`payout_tx_id`,`deposit_amount_sats`,`trade_amount_sats`,`fee_amount_sats`,`fee_address`,`buyer_peer_id`,`seller_peer_id`,`status`,`buyer_signature`,`seller_signature`,`channel_point`,`created_at`,`released_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: EscrowEntity) {
        statement.bindText(1, entity.escrow_id)
        statement.bindText(2, entity.offer_id)
        statement.bindText(3, entity.type)
        val _tmpFunding_tx_id: String? = entity.funding_tx_id
        if (_tmpFunding_tx_id == null) {
          statement.bindNull(4)
        } else {
          statement.bindText(4, _tmpFunding_tx_id)
        }
        val _tmpPayout_tx_id: String? = entity.payout_tx_id
        if (_tmpPayout_tx_id == null) {
          statement.bindNull(5)
        } else {
          statement.bindText(5, _tmpPayout_tx_id)
        }
        statement.bindLong(6, entity.deposit_amount_sats)
        statement.bindLong(7, entity.trade_amount_sats)
        statement.bindLong(8, entity.fee_amount_sats)
        statement.bindText(9, entity.fee_address)
        statement.bindText(10, entity.buyer_peer_id)
        statement.bindText(11, entity.seller_peer_id)
        statement.bindText(12, entity.status)
        val _tmpBuyer_signature: ByteArray? = entity.buyer_signature
        if (_tmpBuyer_signature == null) {
          statement.bindNull(13)
        } else {
          statement.bindBlob(13, _tmpBuyer_signature)
        }
        val _tmpSeller_signature: ByteArray? = entity.seller_signature
        if (_tmpSeller_signature == null) {
          statement.bindNull(14)
        } else {
          statement.bindBlob(14, _tmpSeller_signature)
        }
        val _tmpChannel_point: String? = entity.channel_point
        if (_tmpChannel_point == null) {
          statement.bindNull(15)
        } else {
          statement.bindText(15, _tmpChannel_point)
        }
        statement.bindLong(16, entity.created_at)
        val _tmpReleased_at: Long? = entity.released_at
        if (_tmpReleased_at == null) {
          statement.bindNull(17)
        } else {
          statement.bindLong(17, _tmpReleased_at)
        }
      }
    }
  }

  public override suspend fun upsert(escrow: EscrowEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __insertAdapterOfEscrowEntity.insert(_connection, escrow)
  }

  public override fun getAllEscrows(): Flow<List<EscrowEntity>> {
    val _sql: String = "SELECT * FROM escrows ORDER BY created_at DESC"
    return createFlow(__db, false, arrayOf("escrows")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        val _columnIndexOfEscrowId: Int = getColumnIndexOrThrow(_stmt, "escrow_id")
        val _columnIndexOfOfferId: Int = getColumnIndexOrThrow(_stmt, "offer_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfFundingTxId: Int = getColumnIndexOrThrow(_stmt, "funding_tx_id")
        val _columnIndexOfPayoutTxId: Int = getColumnIndexOrThrow(_stmt, "payout_tx_id")
        val _columnIndexOfDepositAmountSats: Int = getColumnIndexOrThrow(_stmt,
            "deposit_amount_sats")
        val _columnIndexOfTradeAmountSats: Int = getColumnIndexOrThrow(_stmt, "trade_amount_sats")
        val _columnIndexOfFeeAmountSats: Int = getColumnIndexOrThrow(_stmt, "fee_amount_sats")
        val _columnIndexOfFeeAddress: Int = getColumnIndexOrThrow(_stmt, "fee_address")
        val _columnIndexOfBuyerPeerId: Int = getColumnIndexOrThrow(_stmt, "buyer_peer_id")
        val _columnIndexOfSellerPeerId: Int = getColumnIndexOrThrow(_stmt, "seller_peer_id")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfBuyerSignature: Int = getColumnIndexOrThrow(_stmt, "buyer_signature")
        val _columnIndexOfSellerSignature: Int = getColumnIndexOrThrow(_stmt, "seller_signature")
        val _columnIndexOfChannelPoint: Int = getColumnIndexOrThrow(_stmt, "channel_point")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfReleasedAt: Int = getColumnIndexOrThrow(_stmt, "released_at")
        val _result: MutableList<EscrowEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: EscrowEntity
          val _tmpEscrow_id: String
          _tmpEscrow_id = _stmt.getText(_columnIndexOfEscrowId)
          val _tmpOffer_id: String
          _tmpOffer_id = _stmt.getText(_columnIndexOfOfferId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpFunding_tx_id: String?
          if (_stmt.isNull(_columnIndexOfFundingTxId)) {
            _tmpFunding_tx_id = null
          } else {
            _tmpFunding_tx_id = _stmt.getText(_columnIndexOfFundingTxId)
          }
          val _tmpPayout_tx_id: String?
          if (_stmt.isNull(_columnIndexOfPayoutTxId)) {
            _tmpPayout_tx_id = null
          } else {
            _tmpPayout_tx_id = _stmt.getText(_columnIndexOfPayoutTxId)
          }
          val _tmpDeposit_amount_sats: Long
          _tmpDeposit_amount_sats = _stmt.getLong(_columnIndexOfDepositAmountSats)
          val _tmpTrade_amount_sats: Long
          _tmpTrade_amount_sats = _stmt.getLong(_columnIndexOfTradeAmountSats)
          val _tmpFee_amount_sats: Long
          _tmpFee_amount_sats = _stmt.getLong(_columnIndexOfFeeAmountSats)
          val _tmpFee_address: String
          _tmpFee_address = _stmt.getText(_columnIndexOfFeeAddress)
          val _tmpBuyer_peer_id: String
          _tmpBuyer_peer_id = _stmt.getText(_columnIndexOfBuyerPeerId)
          val _tmpSeller_peer_id: String
          _tmpSeller_peer_id = _stmt.getText(_columnIndexOfSellerPeerId)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpBuyer_signature: ByteArray?
          if (_stmt.isNull(_columnIndexOfBuyerSignature)) {
            _tmpBuyer_signature = null
          } else {
            _tmpBuyer_signature = _stmt.getBlob(_columnIndexOfBuyerSignature)
          }
          val _tmpSeller_signature: ByteArray?
          if (_stmt.isNull(_columnIndexOfSellerSignature)) {
            _tmpSeller_signature = null
          } else {
            _tmpSeller_signature = _stmt.getBlob(_columnIndexOfSellerSignature)
          }
          val _tmpChannel_point: String?
          if (_stmt.isNull(_columnIndexOfChannelPoint)) {
            _tmpChannel_point = null
          } else {
            _tmpChannel_point = _stmt.getText(_columnIndexOfChannelPoint)
          }
          val _tmpCreated_at: Long
          _tmpCreated_at = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpReleased_at: Long?
          if (_stmt.isNull(_columnIndexOfReleasedAt)) {
            _tmpReleased_at = null
          } else {
            _tmpReleased_at = _stmt.getLong(_columnIndexOfReleasedAt)
          }
          _item =
              EscrowEntity(_tmpEscrow_id,_tmpOffer_id,_tmpType,_tmpFunding_tx_id,_tmpPayout_tx_id,_tmpDeposit_amount_sats,_tmpTrade_amount_sats,_tmpFee_amount_sats,_tmpFee_address,_tmpBuyer_peer_id,_tmpSeller_peer_id,_tmpStatus,_tmpBuyer_signature,_tmpSeller_signature,_tmpChannel_point,_tmpCreated_at,_tmpReleased_at)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getEscrow(escrowId: String): Flow<EscrowEntity?> {
    val _sql: String = "SELECT * FROM escrows WHERE escrow_id = ?"
    return createFlow(__db, false, arrayOf("escrows")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, escrowId)
        val _columnIndexOfEscrowId: Int = getColumnIndexOrThrow(_stmt, "escrow_id")
        val _columnIndexOfOfferId: Int = getColumnIndexOrThrow(_stmt, "offer_id")
        val _columnIndexOfType: Int = getColumnIndexOrThrow(_stmt, "type")
        val _columnIndexOfFundingTxId: Int = getColumnIndexOrThrow(_stmt, "funding_tx_id")
        val _columnIndexOfPayoutTxId: Int = getColumnIndexOrThrow(_stmt, "payout_tx_id")
        val _columnIndexOfDepositAmountSats: Int = getColumnIndexOrThrow(_stmt,
            "deposit_amount_sats")
        val _columnIndexOfTradeAmountSats: Int = getColumnIndexOrThrow(_stmt, "trade_amount_sats")
        val _columnIndexOfFeeAmountSats: Int = getColumnIndexOrThrow(_stmt, "fee_amount_sats")
        val _columnIndexOfFeeAddress: Int = getColumnIndexOrThrow(_stmt, "fee_address")
        val _columnIndexOfBuyerPeerId: Int = getColumnIndexOrThrow(_stmt, "buyer_peer_id")
        val _columnIndexOfSellerPeerId: Int = getColumnIndexOrThrow(_stmt, "seller_peer_id")
        val _columnIndexOfStatus: Int = getColumnIndexOrThrow(_stmt, "status")
        val _columnIndexOfBuyerSignature: Int = getColumnIndexOrThrow(_stmt, "buyer_signature")
        val _columnIndexOfSellerSignature: Int = getColumnIndexOrThrow(_stmt, "seller_signature")
        val _columnIndexOfChannelPoint: Int = getColumnIndexOrThrow(_stmt, "channel_point")
        val _columnIndexOfCreatedAt: Int = getColumnIndexOrThrow(_stmt, "created_at")
        val _columnIndexOfReleasedAt: Int = getColumnIndexOrThrow(_stmt, "released_at")
        val _result: EscrowEntity?
        if (_stmt.step()) {
          val _tmpEscrow_id: String
          _tmpEscrow_id = _stmt.getText(_columnIndexOfEscrowId)
          val _tmpOffer_id: String
          _tmpOffer_id = _stmt.getText(_columnIndexOfOfferId)
          val _tmpType: String
          _tmpType = _stmt.getText(_columnIndexOfType)
          val _tmpFunding_tx_id: String?
          if (_stmt.isNull(_columnIndexOfFundingTxId)) {
            _tmpFunding_tx_id = null
          } else {
            _tmpFunding_tx_id = _stmt.getText(_columnIndexOfFundingTxId)
          }
          val _tmpPayout_tx_id: String?
          if (_stmt.isNull(_columnIndexOfPayoutTxId)) {
            _tmpPayout_tx_id = null
          } else {
            _tmpPayout_tx_id = _stmt.getText(_columnIndexOfPayoutTxId)
          }
          val _tmpDeposit_amount_sats: Long
          _tmpDeposit_amount_sats = _stmt.getLong(_columnIndexOfDepositAmountSats)
          val _tmpTrade_amount_sats: Long
          _tmpTrade_amount_sats = _stmt.getLong(_columnIndexOfTradeAmountSats)
          val _tmpFee_amount_sats: Long
          _tmpFee_amount_sats = _stmt.getLong(_columnIndexOfFeeAmountSats)
          val _tmpFee_address: String
          _tmpFee_address = _stmt.getText(_columnIndexOfFeeAddress)
          val _tmpBuyer_peer_id: String
          _tmpBuyer_peer_id = _stmt.getText(_columnIndexOfBuyerPeerId)
          val _tmpSeller_peer_id: String
          _tmpSeller_peer_id = _stmt.getText(_columnIndexOfSellerPeerId)
          val _tmpStatus: String
          _tmpStatus = _stmt.getText(_columnIndexOfStatus)
          val _tmpBuyer_signature: ByteArray?
          if (_stmt.isNull(_columnIndexOfBuyerSignature)) {
            _tmpBuyer_signature = null
          } else {
            _tmpBuyer_signature = _stmt.getBlob(_columnIndexOfBuyerSignature)
          }
          val _tmpSeller_signature: ByteArray?
          if (_stmt.isNull(_columnIndexOfSellerSignature)) {
            _tmpSeller_signature = null
          } else {
            _tmpSeller_signature = _stmt.getBlob(_columnIndexOfSellerSignature)
          }
          val _tmpChannel_point: String?
          if (_stmt.isNull(_columnIndexOfChannelPoint)) {
            _tmpChannel_point = null
          } else {
            _tmpChannel_point = _stmt.getText(_columnIndexOfChannelPoint)
          }
          val _tmpCreated_at: Long
          _tmpCreated_at = _stmt.getLong(_columnIndexOfCreatedAt)
          val _tmpReleased_at: Long?
          if (_stmt.isNull(_columnIndexOfReleasedAt)) {
            _tmpReleased_at = null
          } else {
            _tmpReleased_at = _stmt.getLong(_columnIndexOfReleasedAt)
          }
          _result =
              EscrowEntity(_tmpEscrow_id,_tmpOffer_id,_tmpType,_tmpFunding_tx_id,_tmpPayout_tx_id,_tmpDeposit_amount_sats,_tmpTrade_amount_sats,_tmpFee_amount_sats,_tmpFee_address,_tmpBuyer_peer_id,_tmpSeller_peer_id,_tmpStatus,_tmpBuyer_signature,_tmpSeller_signature,_tmpChannel_point,_tmpCreated_at,_tmpReleased_at)
        } else {
          _result = null
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateStatus(escrowId: String, status: String) {
    val _sql: String = "UPDATE escrows SET status = ? WHERE escrow_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, status)
        _argIndex = 2
        _stmt.bindText(_argIndex, escrowId)
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
