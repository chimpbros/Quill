package com.yugentech.quill.aira.provider
import com.yugentech.quill.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestData
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class HttpAiProviderTest {
 private val request=TextRequest("Use the supplied evidence.",listOf(AiMessage("user","Question")))
 private fun provider(data:String,status:HttpStatusCode=HttpStatusCode.OK,verify:(HttpRequestData)->Unit={}):HttpAiProvider {
  val client=HttpClient(MockEngine { req -> verify(req);respond(data,status,headersOf(HttpHeaders.ContentType,"text/event-stream")) })
  return HttpAiProvider(client,CredentialResolver { "dummy-test-credential" })
 }
 @Test fun responsesAreCompletedOnlyByTerminalSuccess()=runTest {
  val api=provider("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\ndata: {\"type\":\"response.completed\"}\n\n") { req ->
   assertEquals("Bearer dummy-test-credential",req.headers[HttpHeaders.Authorization])
   val body=Json.parseToJsonElement((req.body as TextContent).text).jsonObject
   assertEquals(false,body["store"]!!.jsonPrimitive.boolean);assertEquals(true,body["stream"]!!.jsonPrimitive.boolean)
   assertFalse(body.containsKey("temperature"));assertFalse(body.containsKey("max_output_tokens"));assertTrue(body.containsKey("instructions"))
  }
  val events=api.generate(AiConnection(AiProvider.CHATGPT,"allowed-model"),request).toList()
  assertEquals(AiEvent.Partial("Hello"),events.first());assertEquals(AiEvent.Completed("Hello"),events.last());api.client.close()
 }
 @Test fun interruptedResponsesAreNeverCompleted()=runTest {
  val api=provider("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n")
  val events=api.generate(AiConnection(AiProvider.OPENAI,"test-model"),request).toList()
  assertTrue(events.last() is AiEvent.Failed);assertFalse(events.any { it is AiEvent.Completed });api.client.close()
 }
 @Test fun subscriptionLimitAfterPartialOutputFails()=runTest {
  val api=provider("data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n")
  val events=api.generate(AiConnection(AiProvider.CHATGPT,"test-model"),request).toList()
  assertEquals(AiErrorKind.LIMIT,(events.last() as AiEvent.Failed).error.kind);assertFalse(events.any { it is AiEvent.Completed });api.client.close()
 }
 @Test fun openRouterStopsOnItsFinishReason()=runTest {
  val api=provider("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n") { req -> assertEquals("openrouter.ai",req.url.host) }
  assertEquals(AiEvent.Completed("Hi"),api.generate(AiConnection(AiProvider.OPENROUTER,"test/model"),request).toList().last());api.client.close()
 }
 @Test fun openRouterLengthLimitIsNotSuccess()=runTest {
  val api=provider("data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n")
  assertTrue(api.generate(AiConnection(AiProvider.OPENROUTER,"test/model"),request).toList().last() is AiEvent.Failed);api.client.close()
 }
 @Test fun geminiUsesHeaderKeyAndNativeRoles()=runTest {
  val api=provider("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hi\"}]},\"finishReason\":\"STOP\"}]}\n\n") { req ->
   assertEquals("dummy-test-credential",req.headers["x-goog-api-key"]);assertFalse(req.url.toString().contains("dummy-test-credential"))
   val body=Json.parseToJsonElement((req.body as TextContent).text).jsonObject
   assertTrue(body.containsKey("systemInstruction"));assertTrue(body.containsKey("contents"));assertFalse(body.containsKey("messages"))
  }
  assertEquals(AiEvent.Completed("Hi"),api.generate(AiConnection(AiProvider.GEMINI,"test-model"),request).toList().last());api.client.close()
 }
 @Test fun geminiBlockedOutputFails()=runTest {
  val api=provider("data: {\"candidates\":[{\"finishReason\":\"SAFETY\"}]}\n\n")
  assertTrue(api.generate(AiConnection(AiProvider.GEMINI,"test"),request).toList().last() is AiEvent.Failed);api.client.close()
 }
 @Test fun authenticationErrorsRemainAuthenticationErrors()=runTest {
  val api=provider("{\"error\":{\"code\":\"invalid_api_key\"}}",HttpStatusCode.Unauthorized)
  assertEquals(AiErrorKind.AUTHENTICATION,(api.generate(AiConnection(AiProvider.OPENAI,"test"),request).toList().last() as AiEvent.Failed).error.kind);api.client.close()
 }
 @Test fun imageApiPreservesMimeType()=runTest {
  val api=provider("{\"data\":[{\"b64_json\":\"aW1hZ2U=\",\"media_type\":\"image/webp\"}]}")
  val image=api.generateImage(AiConnection(AiProvider.OPENROUTER,"image-model"),"A scene")
  assertEquals("image/webp",image.mimeType);assertEquals("image",String(image.bytes));api.client.close()
 }
 @Test fun subscriptionImagesAreRejectedBeforeNetwork()=runTest {
  val api=provider("") { fail("A subscription image request must not reach the network") }
  try { api.generateImage(AiConnection(AiProvider.CHATGPT,"test"),"A scene");fail("Expected unsupported feature") } catch(e:AiException) { assertEquals(AiErrorKind.MODEL,e.kind) }
  api.client.close()
 }
 @Test fun subscriptionCatalogUsesAccountSpecificVisibility()=runTest {
  val api=provider("{\"models\":[{\"slug\":\"visible\",\"display_name\":\"Visible\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hidden\"}]}")
  assertEquals(listOf("visible"),api.models(AiConnection(AiProvider.CHATGPT,"")).map { it.id });api.client.close()
 }
}
