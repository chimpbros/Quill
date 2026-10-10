package com.yugentech.quill.aira.provider

import com.yugentech.quill.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.util.Base64

fun interface CredentialResolver { suspend fun resolve(provider: AiProvider): String }

class HttpAiProvider(val client: HttpClient, private val credentials: CredentialResolver) : TextProvider, ImageProvider {
    private val json = Json { ignoreUnknownKeys = true }
    private fun endpoint(c: AiConnection) = when(c.provider) {
        AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/${c.model.encodeURLPath()}:streamGenerateContent?alt=sse"
        else -> "https://api.openai.com/v1/responses"
    }
    private suspend fun HttpRequestBuilder.authorize(c: AiConnection) {
        val credential = credentials.resolve(c.provider)
        if(c.provider == AiProvider.GEMINI) header("x-goog-api-key", credential) else bearerAuth(credential)
        header(HttpHeaders.UserAgent, "QuillPersonal/4.1.0")
        contentType(ContentType.Application.Json)
    }
    private suspend fun checked(response: HttpResponse): JsonObject {
        val body = response.bodyAsText()
        val objectBody = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (!response.status.isSuccess()) throw WireProtocol.error(response.status.value, objectBody?.get("error")?.jsonObject?.string("code"))
        return objectBody ?: throw AiException(AiErrorKind.INCOMPLETE,"The provider returned an unreadable response.")
    }
    override fun generate(connection: AiConnection, request: TextRequest): Flow<AiEvent> = flow {
        try {
            val fitted = ContextBudget.fit(connection, request)
            var result = ""
            var complete = false
            val statement = client.preparePost(endpoint(connection)) {
                authorize(connection); setBody(WireProtocol.textBody(connection, fitted).toString())
            }
            statement.execute { response ->
                if (!response.status.isSuccess()) { checked(response); return@execute }
                val channel = response.bodyAsChannel()
                val data = StringBuilder()
                suspend fun event() {
                    if(data.isEmpty()) return
                    val raw = data.toString(); data.clear()
                    if(raw == "[DONE]") return
                    val obj = json.parseToJsonElement(raw).jsonObject
                    obj["error"]?.let { throw WireProtocol.error(400,(it as? JsonObject)?.string("code")) }
                    when(connection.provider) {
                        AiProvider.OPENROUTER -> {
                            val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
                            result += (choice?.get("delta") as? JsonObject)?.string("content").orEmpty()
                            val reason = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
                            if(reason == "stop") complete=true
                            else if(reason != null) throw AiException(AiErrorKind.INCOMPLETE,"The model stopped before completing its answer ($reason).")
                        }
                        AiProvider.GEMINI -> {
                            val candidate = (obj["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
                            val parts = (candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                            result += parts?.joinToString("") { if(it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull==true) "" else it.jsonObject.string("text") }.orEmpty()
                            val reason=candidate?.string("finishReason")
                            if(reason == "STOP") complete=true
                            else if(!reason.isNullOrEmpty()) throw AiException(AiErrorKind.INCOMPLETE,"The model did not complete this answer ($reason).")
                        }
                        else -> when(obj.string("type")) {
                            "response.output_text.delta" -> result += obj.string("delta")
                            "response.completed" -> complete = true
                            "response.failed", "error" -> throw WireProtocol.error(400,(obj["response"] as? JsonObject)?.get("error")?.jsonObject?.string("code") ?: obj.string("code"))
                            "response.incomplete" -> throw AiException(AiErrorKind.INCOMPLETE,"The model did not complete this answer.")
                        }
                    }
                    if(result.isNotEmpty()) emit(AiEvent.Partial(result))
                }
                while(!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if(line.isEmpty()) event()
                    else if(line.startsWith("data:")) { if(data.isNotEmpty()) data.append('\n'); data.append(line.removePrefix("data:").trimStart()) }
                }
                event()
            }
            if(!complete || result.isBlank()) throw AiException(AiErrorKind.INCOMPLETE,"The stream ended without a complete answer. Retry when the connection is stable.")
            emit(AiEvent.Completed(result))
        } catch(e: CancellationException) { throw e }
        catch(e: AiException) { emit(AiEvent.Failed(e)) }
        catch(_: Exception) { emit(AiEvent.Failed(AiException(AiErrorKind.NETWORK,"Unable to reach the provider. Check your connection and retry."))) }
    }
    override suspend fun classify(connection:AiConnection,request:TextRequest):String {
        var result:String?=null
        generate(connection,request).collect { when(it) { is AiEvent.Completed -> result=it.text; is AiEvent.Failed -> throw it.error; else -> Unit } }
        return result ?: throw AiException(AiErrorKind.INCOMPLETE,"No classification returned.")
    }
    override suspend fun test(connection:AiConnection) = classify(connection,TextRequest("Reply briefly.",listOf(AiMessage("user","Say: Connected"))))
    override suspend fun models(connection:AiConnection):List<AiModel> {
        val url=when(connection.provider) {
            AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
            AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1/models"
            else -> "https://api.openai.com/v1/models"
        }
        val body=checked(client.get(url) { authorize(connection) })
        val array=(body[if(connection.provider==AiProvider.GEMINI || connection.provider==AiProvider.CHATGPT) "models" else "data"] as? JsonArray) ?: JsonArray(emptyList())
        return array.mapNotNull { element ->
            val item=element.jsonObject
            if(connection.provider==AiProvider.CHATGPT && item.string("visibility")!="list") return@mapNotNull null
            val id=when(connection.provider) { AiProvider.GEMINI -> item.string("name").removePrefix("models/"); AiProvider.CHATGPT -> item.string("slug"); else -> item.string("id") }
            if(id.isBlank()) return@mapNotNull null
            AiModel(id,item.string("display_name").ifBlank { item.string("displayName").ifBlank { item.string("name").ifBlank { id } } },
                (item["context_length"] ?: item["inputTokenLimit"])?.jsonPrimitive?.intOrNull ?: 32768,
                ((item["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray)?.any { it.jsonPrimitive.content=="image" } ?: id.contains("image",true))
        }
    }
    override suspend fun generateImage(connection:AiConnection,prompt:String):ImageArtifact {
        if(connection.provider==AiProvider.CHATGPT) throw AiException(AiErrorKind.MODEL,"ChatGPT subscription access does not support image generation. Configure an image API connection.")
        val url=when(connection.provider) {
            AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/${connection.model.encodeURLPath()}:generateContent"
            AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1/images"
            else -> "https://api.openai.com/v1/images/generations"
        }
        val body=buildJsonObject {
            if(connection.provider==AiProvider.GEMINI) {
                put("contents",buildJsonArray { add(buildJsonObject { put("parts",buildJsonArray { add(buildJsonObject { put("text",prompt) }) }) }) })
                put("generationConfig",buildJsonObject { put("responseModalities",buildJsonArray { add("TEXT"); add("IMAGE") }) })
            } else { put("model",connection.model); put("prompt",prompt); put("n",1); if(connection.provider==AiProvider.OPENAI) put("size","1024x1024") }
        }
        val response=checked(client.post(url) { authorize(connection); setBody(body.toString()) })
        if(connection.provider==AiProvider.GEMINI) {
            val parts=response["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            val inline=parts?.firstOrNull { it.jsonObject.containsKey("inlineData") }?.jsonObject?.get("inlineData")?.jsonObject
            if(inline!=null) return ImageArtifact(Base64.getDecoder().decode(inline.string("data")),inline.string("mimeType").ifBlank { "image/png" })
        } else {
            val item=response["data"]?.jsonArray?.firstOrNull()?.jsonObject
            if(item?.string("b64_json")?.isNotBlank()==true) return ImageArtifact(Base64.getDecoder().decode(item.string("b64_json")),item.string("media_type").ifBlank { "image/png" })
            val urlString=item?.string("url").orEmpty()
            if(urlString.startsWith("data:image/")) return ImageArtifact(Base64.getDecoder().decode(urlString.substringAfter(",")),urlString.substringAfter("data:").substringBefore(";"))
            if(urlString.startsWith("https://")) {
                val image=client.get(urlString)
                if(image.status.isSuccess()) return ImageArtifact(image.bodyAsChannel().toByteArray(), image.headers[HttpHeaders.ContentType]?.substringBefore(';') ?: "image/png")
            }
        }
        throw AiException(AiErrorKind.INCOMPLETE,"The model returned no image. Choose an image-capable model or a more visual passage.")
    }
    suspend fun speech(text:String,voice:String):ByteArray {
        val c=AiConnection(AiProvider.OPENAI,"gpt-4o-mini-tts")
        val response=client.post("https://api.openai.com/v1/audio/speech") {
            authorize(c); setBody(buildJsonObject { put("model",c.model); put("voice",voice); put("input",text); put("response_format","mp3") }.toString())
        }
        if(!response.status.isSuccess()) checked(response)
        return response.bodyAsChannel().toByteArray()
    }
}
