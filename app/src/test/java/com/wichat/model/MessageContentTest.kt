package com.wichat.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every message type must survive encode → decode, or sending it fails after the upload. */
class MessageContentTest {

    private fun roundTrip(content: MessageContent) = assertEquals(content, MessageContent.decode(content.encode()))

    @Test
    fun text() = roundTrip(MessageContent.Text("hello"))

    @Test
    fun receipt() = roundTrip(MessageContent.Receipt(listOf("a", "b"), ReceiptStatus.READ))

    @Test
    fun everyAttachmentKind() {
        AttachmentKind.entries.forEach { kind ->
            roundTrip(
                MessageContent.Attachment(
                    kind = kind,
                    fileName = "file.bin",
                    mime = "application/octet-stream",
                    sizeBytes = 123,
                    sha256 = "c2hh",
                    fileKey = "a2V5",
                    nonceBase = "bm9uY2U=",
                    chunkCount = 1,
                    blobId = "blob",
                    durationMs = 4000,
                    caption = "look",
                )
            )
        }
    }
}
