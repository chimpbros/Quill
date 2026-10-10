package com.yugentech.quill.aira.chat.quickChat.service
import com.yugentech.quill.aira.chat.quickChat.model.QuickChatPayload
import com.yugentech.quill.aira.provider.*
class QuickChatService(private val gateway:DirectAiGateway) {
 fun getQuickChatResponse(payload:QuickChatPayload) = gateway.text(
  PromptLibrary.quick(payload.actionType.name,payload.bookTitle,payload.bookAuthor,payload.query),
  "PASSAGES:\n${payload.context}\nREQUEST:\n${payload.query.orEmpty()}")
}
