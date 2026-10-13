package com.yugentech.quill.aira.provider

import com.yugentech.quill.domain.ai.*
import org.junit.Assert.*
import org.junit.Test

class ContextSettingsTest {
 @Test fun reportedCapacityWinsAndKnownModelRepairsMissingMetadata() {
  for(provider in listOf(AiProvider.OPENAI,AiProvider.CHATGPT)) {
   assertEquals(1050000,ContextCapacity.resolve(provider,"gpt-6-luna"))
   assertEquals(64000,ContextCapacity.resolve(provider,"gpt-6-luna",64000))
   assertEquals(1050000,ContextCapacity.resolve(provider,"gpt-6-luna",0))
  }
  assertEquals(32768,ContextCapacity.resolve(AiProvider.OPENROUTER,"unknown"))
  assertEquals(32768,ContextCapacity.resolve(AiProvider.GEMINI,"unknown",-1))
 }
 @Test fun manualValuesRejectBlankMalformedSmallAndOverflowNumbers() {
  for(value in listOf(""," ","8191","-8192","1,050,000","2147483648","99999999999999999999")) assertNull(ContextCapacity.parseManual(value))
  assertEquals(8192,ContextCapacity.requireManual("8192"))
  assertEquals(Int.MAX_VALUE,ContextCapacity.requireManual("2147483647"))
 }
 @Test fun updatingExistingChatGptChoiceRequiresNoFreshCatalog() {
  val existing=AiConnection(AiProvider.CHATGPT,"gpt-6-luna")
  ConnectionSelection.validate(existing.copy(contextTokens=64000),existing,null,emptyList())
 }
 @Test fun differentChatGptChoiceNeedsItsOwnProviderCatalog() {
  val choice=AiConnection(AiProvider.CHATGPT,"other")
  for(catalogProvider in listOf(null,AiProvider.OPENAI)) {
   try { ConnectionSelection.validate(choice,AiConnection(AiProvider.CHATGPT,"gpt-6-luna"),catalogProvider,listOf(AiModel("other")));fail("Expected model validation") }
   catch(_:IllegalArgumentException) { }
  }
  ConnectionSelection.validate(choice,null,AiProvider.CHATGPT,listOf(AiModel("other")))
 }
}
