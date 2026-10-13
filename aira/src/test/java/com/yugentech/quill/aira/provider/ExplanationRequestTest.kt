package com.yugentech.quill.aira.provider

import com.yugentech.quill.aira.chat.quickChat.model.*
import com.yugentech.quill.aira.chat.quickChat.service.QuickChatRequests
import com.yugentech.quill.domain.ai.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ExplanationRequestTest {
 @Test fun bothReportedPassagesMatchTheActualApprovedLiveRequests() {
  val fixtures=Json.parseToJsonElement(javaClass.getResourceAsStream("/approved-explanation-requests.json")!!.bufferedReader().readText()).jsonArray
  for(fixture in fixtures) {
   val item=fixture.jsonObject
   val request=QuickChatRequests.build(QuickChatPayload(QuickChatType.EXPLAIN_THIS,item["title"]!!.jsonPrimitive.content,item["author"]!!.jsonPrimitive.content,item["passage"]!!.jsonPrimitive.content))
   assertEquals(item["expected"],WireProtocol.textBody(AiConnection(AiProvider.CHATGPT,"gpt-6-luna"),request))
  }
 }
 @Test fun otherQuickActionsKeepTheirExistingGroundingAndWrapper() {
  val request=QuickChatRequests.build(QuickChatPayload(QuickChatType.DEFINE_WORD,"Book","Author","","lantern"))
  assertTrue(request.instructions.startsWith("Never reveal unread events"))
  assertEquals("PASSAGES:\n\nREQUEST:\nlantern",request.messages.single().text)
 }
}
