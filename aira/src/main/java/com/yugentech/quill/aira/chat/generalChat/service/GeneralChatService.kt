package com.yugentech.quill.aira.chat.generalChat.service
import com.yugentech.quill.aira.chat.generalChat.model.GeneralChatPayload
import com.yugentech.quill.aira.provider.*
class GeneralChatService(private val gateway:DirectAiGateway) {
 fun getChatResponse(payload:GeneralChatPayload) = gateway.text(PromptLibrary.general(payload.bookTitle,payload.bookAuthor),payload.query,payload.history)
}
