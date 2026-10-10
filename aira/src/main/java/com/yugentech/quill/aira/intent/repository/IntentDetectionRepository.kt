package com.yugentech.quill.aira.intent.repository
import com.yugentech.quill.aira.intent.model.Intent
import com.yugentech.quill.aira.intent.service.IntentDetectionService
class IntentDetectionRepository(private val detectionService:IntentDetectionService) {
 suspend fun detectIntent(query:String,title:String,author:String,selectedText:String?=null):Intent {
  val raw=detectionService.detectIntent(query,title,author,selectedText)
  return IntentParser.parse(raw,query)
 }
}
