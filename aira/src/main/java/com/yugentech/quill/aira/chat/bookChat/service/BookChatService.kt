package com.yugentech.quill.aira.chat.bookChat.service
import com.yugentech.quill.aira.chat.bookChat.payload.BookChatPayload
import com.yugentech.quill.aira.provider.*
class BookChatService(private val gateway:DirectAiGateway) {
 fun bookChat(payload:BookChatPayload) = gateway.text(
  PromptLibrary.book(payload.bookTitle,payload.bookAuthor,payload.queryIntent,payload.userName),
  "HIGHLIGHTED PASSAGE:\n${payload.selectedText.orEmpty()}\nPASSAGES:\n${payload.context}\nQUESTION:\n${payload.query}",payload.history)
}
