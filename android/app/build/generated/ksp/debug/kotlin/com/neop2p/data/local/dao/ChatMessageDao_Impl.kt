package com.neop2p.`data`.local.dao

import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.neop2p.`data`.local.entity.ChatMessageEntity
import javax.`annotation`.processing.Generated
import kotlin.Boolean
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
public class ChatMessageDao_Impl(
  __db: RoomDatabase,
) : ChatMessageDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfChatMessageEntity: EntityInsertAdapter<ChatMessageEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfChatMessageEntity = object : EntityInsertAdapter<ChatMessageEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR REPLACE INTO `chat_messages` (`message_id`,`offer_id`,`sender_peer_id`,`ciphertext`,`ratchet_key`,`is_read`,`sent_at`,`delivered_at`,`file_attachment`) VALUES (?,?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: ChatMessageEntity) {
        statement.bindText(1, entity.message_id)
        statement.bindText(2, entity.offer_id)
        statement.bindText(3, entity.sender_peer_id)
        statement.bindBlob(4, entity.ciphertext)
        val _tmpRatchet_key: ByteArray? = entity.ratchet_key
        if (_tmpRatchet_key == null) {
          statement.bindNull(5)
        } else {
          statement.bindBlob(5, _tmpRatchet_key)
        }
        val _tmp: Int = if (entity.is_read) 1 else 0
        statement.bindLong(6, _tmp.toLong())
        statement.bindLong(7, entity.sent_at)
        val _tmpDelivered_at: Long? = entity.delivered_at
        if (_tmpDelivered_at == null) {
          statement.bindNull(8)
        } else {
          statement.bindLong(8, _tmpDelivered_at)
        }
        val _tmpFile_attachment: ByteArray? = entity.file_attachment
        if (_tmpFile_attachment == null) {
          statement.bindNull(9)
        } else {
          statement.bindBlob(9, _tmpFile_attachment)
        }
      }
    }
  }

  public override suspend fun insert(message: ChatMessageEntity): Unit = performSuspending(__db,
      false, true) { _connection ->
    __insertAdapterOfChatMessageEntity.insert(_connection, message)
  }

  public override fun getMessages(offerId: String): Flow<List<ChatMessageEntity>> {
    val _sql: String = "SELECT * FROM chat_messages WHERE offer_id = ? ORDER BY sent_at ASC"
    return createFlow(__db, false, arrayOf("chat_messages")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, offerId)
        val _columnIndexOfMessageId: Int = getColumnIndexOrThrow(_stmt, "message_id")
        val _columnIndexOfOfferId: Int = getColumnIndexOrThrow(_stmt, "offer_id")
        val _columnIndexOfSenderPeerId: Int = getColumnIndexOrThrow(_stmt, "sender_peer_id")
        val _columnIndexOfCiphertext: Int = getColumnIndexOrThrow(_stmt, "ciphertext")
        val _columnIndexOfRatchetKey: Int = getColumnIndexOrThrow(_stmt, "ratchet_key")
        val _columnIndexOfIsRead: Int = getColumnIndexOrThrow(_stmt, "is_read")
        val _columnIndexOfSentAt: Int = getColumnIndexOrThrow(_stmt, "sent_at")
        val _columnIndexOfDeliveredAt: Int = getColumnIndexOrThrow(_stmt, "delivered_at")
        val _columnIndexOfFileAttachment: Int = getColumnIndexOrThrow(_stmt, "file_attachment")
        val _result: MutableList<ChatMessageEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ChatMessageEntity
          val _tmpMessage_id: String
          _tmpMessage_id = _stmt.getText(_columnIndexOfMessageId)
          val _tmpOffer_id: String
          _tmpOffer_id = _stmt.getText(_columnIndexOfOfferId)
          val _tmpSender_peer_id: String
          _tmpSender_peer_id = _stmt.getText(_columnIndexOfSenderPeerId)
          val _tmpCiphertext: ByteArray
          _tmpCiphertext = _stmt.getBlob(_columnIndexOfCiphertext)
          val _tmpRatchet_key: ByteArray?
          if (_stmt.isNull(_columnIndexOfRatchetKey)) {
            _tmpRatchet_key = null
          } else {
            _tmpRatchet_key = _stmt.getBlob(_columnIndexOfRatchetKey)
          }
          val _tmpIs_read: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsRead).toInt()
          _tmpIs_read = _tmp != 0
          val _tmpSent_at: Long
          _tmpSent_at = _stmt.getLong(_columnIndexOfSentAt)
          val _tmpDelivered_at: Long?
          if (_stmt.isNull(_columnIndexOfDeliveredAt)) {
            _tmpDelivered_at = null
          } else {
            _tmpDelivered_at = _stmt.getLong(_columnIndexOfDeliveredAt)
          }
          val _tmpFile_attachment: ByteArray?
          if (_stmt.isNull(_columnIndexOfFileAttachment)) {
            _tmpFile_attachment = null
          } else {
            _tmpFile_attachment = _stmt.getBlob(_columnIndexOfFileAttachment)
          }
          _item =
              ChatMessageEntity(_tmpMessage_id,_tmpOffer_id,_tmpSender_peer_id,_tmpCiphertext,_tmpRatchet_key,_tmpIs_read,_tmpSent_at,_tmpDelivered_at,_tmpFile_attachment)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override fun getUnreadMessages(offerId: String): Flow<List<ChatMessageEntity>> {
    val _sql: String = "SELECT * FROM chat_messages WHERE offer_id = ? AND is_read = 0"
    return createFlow(__db, false, arrayOf("chat_messages")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, offerId)
        val _columnIndexOfMessageId: Int = getColumnIndexOrThrow(_stmt, "message_id")
        val _columnIndexOfOfferId: Int = getColumnIndexOrThrow(_stmt, "offer_id")
        val _columnIndexOfSenderPeerId: Int = getColumnIndexOrThrow(_stmt, "sender_peer_id")
        val _columnIndexOfCiphertext: Int = getColumnIndexOrThrow(_stmt, "ciphertext")
        val _columnIndexOfRatchetKey: Int = getColumnIndexOrThrow(_stmt, "ratchet_key")
        val _columnIndexOfIsRead: Int = getColumnIndexOrThrow(_stmt, "is_read")
        val _columnIndexOfSentAt: Int = getColumnIndexOrThrow(_stmt, "sent_at")
        val _columnIndexOfDeliveredAt: Int = getColumnIndexOrThrow(_stmt, "delivered_at")
        val _columnIndexOfFileAttachment: Int = getColumnIndexOrThrow(_stmt, "file_attachment")
        val _result: MutableList<ChatMessageEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: ChatMessageEntity
          val _tmpMessage_id: String
          _tmpMessage_id = _stmt.getText(_columnIndexOfMessageId)
          val _tmpOffer_id: String
          _tmpOffer_id = _stmt.getText(_columnIndexOfOfferId)
          val _tmpSender_peer_id: String
          _tmpSender_peer_id = _stmt.getText(_columnIndexOfSenderPeerId)
          val _tmpCiphertext: ByteArray
          _tmpCiphertext = _stmt.getBlob(_columnIndexOfCiphertext)
          val _tmpRatchet_key: ByteArray?
          if (_stmt.isNull(_columnIndexOfRatchetKey)) {
            _tmpRatchet_key = null
          } else {
            _tmpRatchet_key = _stmt.getBlob(_columnIndexOfRatchetKey)
          }
          val _tmpIs_read: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsRead).toInt()
          _tmpIs_read = _tmp != 0
          val _tmpSent_at: Long
          _tmpSent_at = _stmt.getLong(_columnIndexOfSentAt)
          val _tmpDelivered_at: Long?
          if (_stmt.isNull(_columnIndexOfDeliveredAt)) {
            _tmpDelivered_at = null
          } else {
            _tmpDelivered_at = _stmt.getLong(_columnIndexOfDeliveredAt)
          }
          val _tmpFile_attachment: ByteArray?
          if (_stmt.isNull(_columnIndexOfFileAttachment)) {
            _tmpFile_attachment = null
          } else {
            _tmpFile_attachment = _stmt.getBlob(_columnIndexOfFileAttachment)
          }
          _item =
              ChatMessageEntity(_tmpMessage_id,_tmpOffer_id,_tmpSender_peer_id,_tmpCiphertext,_tmpRatchet_key,_tmpIs_read,_tmpSent_at,_tmpDelivered_at,_tmpFile_attachment)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun markAsRead(offerId: String) {
    val _sql: String = "UPDATE chat_messages SET is_read = 1 WHERE offer_id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
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
