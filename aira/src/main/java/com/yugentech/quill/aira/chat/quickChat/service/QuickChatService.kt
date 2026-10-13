package com.yugentech.quill.aira.chat.quickChat.service
import com.yugentech.quill.aira.chat.quickChat.model.QuickChatPayload
import com.yugentech.quill.aira.chat.quickChat.model.QuickChatType
import com.yugentech.quill.aira.provider.*
import com.yugentech.quill.domain.ai.*

object QuickChatRequests {
 fun build(payload:QuickChatPayload):TextRequest {
  val instructions=PromptLibrary.quick(payload.actionType.name,payload.bookTitle,payload.bookAuthor,payload.query)
  val message=if(payload.actionType==QuickChatType.EXPLAIN_THIS) {
   // Match the exact original wrapper used by the approved live comparison.
   "CONTEXT:\n      ${payload.context}\n\n      QUESTION/TEXT:\n      ${payload.query?.takeIf { it.isNotEmpty() } ?: "Process the context above based on your instructions."}".trim()
  } else "PASSAGES:\n${payload.context}\nREQUEST:\n${payload.query.orEmpty()}"
  return TextRequest(instructions,listOf(AiMessage("user",message)))
 }
}

class QuickChatService(private val gateway:DirectAiGateway) {
 fun getQuickChatResponse(payload:QuickChatPayload):kotlinx.coroutines.flow.Flow<com.yugentech.quill.aira.response.AiraResponse> {
  val request=QuickChatRequests.build(payload)
  return gateway.text(request.instructions,request.messages.single().text)
 }
}
