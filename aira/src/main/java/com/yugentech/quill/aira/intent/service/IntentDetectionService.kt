package com.yugentech.quill.aira.intent.service
import com.yugentech.quill.aira.provider.*
class IntentDetectionService(private val gateway:DirectAiGateway) {
 suspend fun detectIntent(query:String,title:String,author:String,selectedText:String?,history:List<com.yugentech.quill.domain.ai.AiMessage> = emptyList()):String = gateway.classify(
  PromptLibrary.classifier,"BOOK: $title by $author\nSELECTION: ${selectedText.orEmpty()}\nUse the previous discussion turns to resolve follow-up references.\nREQUEST: $query",history)
}
