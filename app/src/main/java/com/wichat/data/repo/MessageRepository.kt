package com.wichat.data.repo

import com.wichat.data.db.ConversationDao
import com.wichat.data.db.ConversationEntity
import com.wichat.data.db.MessageDao
import com.wichat.data.db.MessageEntity
import com.wichat.data.db.MessageWithAttachment
import com.wichat.model.AttachmentKind
import com.wichat.model.MessageContent
import com.wichat.model.MessageStatus
import com.wichat.model.ReceiptStatus
import kotlinx.coroutines.flow.Flow

class MessageRepository(
    private val conversations: ConversationDao,
    private val messages: MessageDao,
) {
    fun observeConversations(): Flow<List<ConversationEntity>> = conversations.observeAll()

    fun observeMessages(conversationId: String): Flow<List<MessageWithAttachment>> =
        messages.observeConversation(conversationId)

    suspend fun get(id: String): MessageEntity? = messages.get(id)

    suspend fun ensureConversation(peerId: String) =
        conversations.insertIfMissing(ConversationEntity(id = peerId, title = peerId))

    suspend fun saveOutgoing(id: String, localUserId: String, peerId: String, content: MessageContent, at: Long) {
        ensureConversation(peerId)
        messages.insert(content.toEntity(id, peerId, localUserId, outgoing = true, MessageStatus.QUEUED, at))
        conversations.onNewMessage(peerId, content.preview(), at, unreadDelta = 0)
    }

    suspend fun saveIncoming(id: String, peerId: String, content: MessageContent, at: Long, transport: String) {
        ensureConversation(peerId)
        messages.insert(
            content.toEntity(id, peerId, peerId, outgoing = false, MessageStatus.DELIVERED, at)
                .copy(transportUsed = transport)
        )
        conversations.onNewMessage(peerId, content.preview(), at, unreadDelta = 1)
    }

    suspend fun markQueued(id: String) = messages.updateStatus(id, MessageStatus.QUEUED)

    suspend fun markSent(id: String, transport: String) = messages.updateStatus(id, MessageStatus.SENT, transport)

    suspend fun markFailed(id: String) = messages.updateStatus(id, MessageStatus.FAILED)

    suspend fun applyReceipt(receipt: MessageContent.Receipt) {
        val status = when (receipt.status) {
            ReceiptStatus.DELIVERED -> MessageStatus.DELIVERED
            ReceiptStatus.READ -> MessageStatus.READ
        }
        messages.advanceStatus(receipt.messageIds, status)
    }

    /** Marks everything in the chat read and returns the ids that need a read receipt. */
    suspend fun markConversationRead(conversationId: String): List<String> {
        conversations.clearUnread(conversationId)
        val ids = messages.unreadIncomingIds(conversationId)
        if (ids.isNotEmpty()) messages.markRead(ids)
        return ids
    }

    /** Attachment messages point at an attachments row with the same id. */
    private fun MessageContent.toEntity(
        id: String, conversationId: String, senderId: String, outgoing: Boolean, status: MessageStatus, at: Long,
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        outgoing = outgoing,
        kind = if (this is MessageContent.Attachment) MessageEntity.KIND_ATTACHMENT else MessageEntity.KIND_TEXT,
        body = if (this is MessageContent.Attachment) caption else preview(),
        attachmentId = if (this is MessageContent.Attachment) id else null,
        status = status,
        createdAt = at,
    )

    private fun MessageContent.preview(): String = when (this) {
        is MessageContent.Text -> body
        is MessageContent.Attachment -> caption ?: when (kind) {
            AttachmentKind.IMAGE -> "📷 Photo"
            AttachmentKind.VOICE_NOTE -> "🎤 Voice note"
            AttachmentKind.AUDIO -> "🎵 $fileName"
            AttachmentKind.VIDEO -> "🎬 $fileName"
            AttachmentKind.DOCUMENT -> "📎 $fileName"
        }
        is MessageContent.Receipt -> ""
    }
}
