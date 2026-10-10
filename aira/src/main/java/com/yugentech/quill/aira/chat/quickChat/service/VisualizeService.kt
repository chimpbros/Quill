package com.yugentech.quill.aira.chat.quickChat.service
import android.util.Base64
import com.yugentech.quill.aira.provider.*
data class VisualizeResult(val imageBase64:String,val mimeType:String)
class VisualizeService(private val gateway:DirectAiGateway) {
 suspend fun visualize(bookTitle:String,bookAuthor:String,passage:String):VisualizeResult {
  val artifact=gateway.image(PromptLibrary.image(bookTitle,bookAuthor,passage))
  return VisualizeResult(Base64.encodeToString(artifact.bytes,Base64.NO_WRAP),artifact.mimeType)
 }
}
