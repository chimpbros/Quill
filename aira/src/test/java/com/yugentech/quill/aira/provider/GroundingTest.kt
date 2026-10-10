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
  val request=TextRequest("Grounding",listOf(AiMessage("user","x".repeat(12000)),AiMessage("assistant","y".repeat(1000)),AiMessage("user","Current evidence and question")))
  val fitted=ContextBudget.fit(AiConnection(AiProvider.OPENAI,"test",contextTokens=8192),request)
  assertEquals("Current evidence and question",fitted.messages.last().text);assertTrue(fitted.messages.size<request.messages.size)
 }
 @Test fun oversizedCurrentChapterIsReportedRatherThanTruncated() {
  try { ContextBudget.fit(AiConnection(AiProvider.CHATGPT,"test",contextTokens=8192),TextRequest("Grounding",listOf(AiMessage("user","x".repeat(10000)))));fail("Expected context error") }
  catch(e:AiException) { assertEquals(AiErrorKind.CONTEXT,e.kind) }
 }
}
