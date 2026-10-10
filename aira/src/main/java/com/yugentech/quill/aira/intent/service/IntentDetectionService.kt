package com.yugentech.quill.aira.intent.service
import com.yugentech.quill.aira.provider.*
class IntentDetectionService(private val gateway:DirectAiGateway) {
 suspend fun detectIntent(query:String,title:String,author:String,selectedText:String?):String = gateway.classify(
  PromptLibrary.classifier,"BOOK: $title by $author\nSELECTION: ${selectedText.orEmpty()}\nREQUEST: $query")
}
