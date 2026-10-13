package com.yugentech.quill.aira.provider
import com.yugentech.quill.domain.ai.*
import com.yugentech.quill.aira.intent.repository.IntentParser
import com.yugentech.quill.aira.intent.model.*
import org.junit.Assert.*
import org.junit.Test
class GroundingTest {
 @Test fun malformedClassificationKeepsBookGrounding() {
  for(raw in listOf("not json","{}","{\"isRAG\":true,\"queryVariations\":[],\"entities\":[],\"keywords\":[],\"queryIntent\":\"general\"}")) {
   val result=IntentParser.parse(raw,"Who is Alice?");assertTrue(result is Intent.BookRelated)
   assertEquals(listOf("Who is Alice?"),(result as Intent.BookRelated).queryVariations)
  }
 }
 @Test fun explicitValidGeneralClassificationIsAllowed() {
  assertEquals(Intent.General,IntentParser.parse("{\"isRAG\":false,\"queryVariations\":[],\"entities\":[],\"keywords\":[],\"queryIntent\":\"general\"}","What is gravity?"))
 }
 @Test fun quotedBooleanIsNotAValidGeneralClassification() {
  assertTrue(IntentParser.parse("{\"isRAG\":\"false\",\"queryVariations\":[],\"entities\":[],\"keywords\":[],\"queryIntent\":\"general\"}","Who is Mira?") is Intent.BookRelated)
 }
 @Test fun characterAndChapterRoutingIsPreserved() {
  val result=IntentParser.parse("{\"isRAG\":true,\"queryVariations\":[\"Alice\"],\"entities\":[\"Alice\"],\"keywords\":[],\"queryIntent\":\"character_profile\",\"characterName\":\"Alice\",\"isChapterScoped\":true}","Who is Alice?") as Intent.BookRelated
  assertEquals("Alice",result.characterName);assertEquals(QueryIntent.CHARACTER_PROFILE,result.intent);assertTrue(result.isChapterScoped)
 }
 @Test fun oldHistoryIsTrimmedBeforeCurrentEvidence() {
  val request=TextRequest("Grounding",listOf(AiMessage("user","x".repeat(24000)),AiMessage("assistant","y".repeat(8000)),AiMessage("user","Recent question"),AiMessage("assistant","Recent answer"),AiMessage("user","Current evidence and question")))
  val fitted=ContextBudget.fit(AiConnection(AiProvider.OPENAI,"test",contextTokens=8192),request)
  assertEquals(request.messages.takeLast(3),fitted.messages)
 }
 @Test fun oversizedCurrentEvidenceAndInstructionsArePassedIntact() {
  val request=TextRequest("Grounding ".repeat(10000),listOf(AiMessage("user","x".repeat(150000))))
  assertEquals(request,ContextBudget.fit(AiConnection(AiProvider.CHATGPT,"test",contextTokens=8192),request))
 }
 @Test fun unicodeEvidenceIsNeverCutAndEstimatesUseLongArithmetic() {
  val request=TextRequest("Grounding",listOf(AiMessage("user","ก你好🌙".repeat(10000))))
  assertEquals(request,ContextBudget.fit(AiConnection(AiProvider.OPENAI,"test",contextTokens=8192),request))
  assertEquals(1L,ContextBudget.estimateTokens("abcd"))
  assertEquals(10L,ContextBudget.estimateTokens("你好🌙"))
  assertEquals(request,ContextBudget.fit(AiConnection(AiProvider.OPENAI,"test",contextTokens=Int.MAX_VALUE),request))
 }
}
