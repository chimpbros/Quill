package com.yugentech.quill.aira.provider

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.yugentech.quill.domain.ai.*
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import java.util.Base64
import java.util.UUID

class ChatGptOAuth(private val context:Context, private val client:HttpClient, private val vault:CredentialStore) {
    private val json=Json { ignoreUnknownKeys=true }
    private val mutex=Mutex()
    private val issuer="https://auth.openai.com"
    private val resource="https://api.openai.com/v1"
    private fun random()=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private fun record()=vault.get("CHATGPT")?.let { json.parseToJsonElement(it).jsonObject }
    fun connected()=record()!=null
    fun accountLabel()=record()?.string("email")?.ifBlank { "ChatGPT account connected" }
    suspend fun disconnect():Boolean = mutex.withLock {
        val saved=record()
        var revoked=saved==null
        try {
            if(saved!=null && saved.string("refresh_token").isNotBlank()) {
                val response=client.get("$issuer/.well-known/openid-configuration")
                require(response.status.isSuccess())
                val endpoint=json.parseToJsonElement(response.bodyAsText()).jsonObject.string("revocation_endpoint")
                val uri=Uri.parse(endpoint);require(uri.scheme=="https" && uri.host=="auth.openai.com")
                repeat(2) { attempt ->
                    if(!revoked) {
                        val result=client.submitForm(endpoint,Parameters.build {
                            append("token",saved.string("refresh_token"));append("token_type_hint","refresh_token");append("client_id",saved.string("client_id"))
                        })
                        revoked=result.status.value==200
                        if(!revoked && attempt==0) delay(500)
                    }
                }
            }
        } catch(e:CancellationException) { throw e } catch(_:Exception) { revoked=false }
        finally { vault.remove("CHATGPT") }
        revoked
    }

    suspend fun signIn():String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val previous=record() ?: vault.get("chatgpt_registration")?.let { json.parseToJsonElement(it).jsonObject }
            val host=vault.get("chatgpt_host") ?: "urn:uuid:${UUID.randomUUID()}".also { vault.put("chatgpt_host",it) }
            val state=random(); val nonce=random(); val verifier=random()
            val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout=1000
                val callback="http://127.0.0.1:${server.localPort}/auth/callback"
                val params=linkedMapOf("client_id" to (previous?.string("client_id") ?: "dynamic_agent_client"),
                    "ext_agent_host_id" to host,"response_type" to "code","redirect_uri" to callback,
                    "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
                    "resource" to resource,"state" to state,"nonce" to nonce,"code_challenge_method" to "S256","code_challenge" to challenge)
                if(previous==null) params["agent_name_hint"]="QuillPersonal"
                else if(previous.string("id_token").isNotBlank()) params["id_token_hint"]=previous.string("id_token")
                val url=Uri.parse("$issuer/api/accounts/authorize").buildUpon().apply { params.forEach { (key,value)->appendQueryParameter(key,value) } }.build()
                withContext(Dispatchers.Main) { context.startActivity(Intent(Intent.ACTION_VIEW,url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                val query=withTimeout(180000) {
                    var received:Map<String,String>?=null
                    while(received==null) {
                        ensureActive()
                        val socket=try { server.accept() } catch(_:SocketTimeoutException) { continue }
                        socket.use { incoming ->
                            incoming.soTimeout=3000
                            val requestLine=runCatching { incoming.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
                            val target=requestLine.split(' ').getOrNull(1).orEmpty()
                            val uri=runCatching { Uri.parse(target) }.getOrNull()
                            val values=runCatching { uri?.queryParameterNames?.associateWith { uri.getQueryParameter(it).orEmpty() } }.getOrNull().orEmpty()
                            val valid=requestLine.startsWith("GET ") && uri?.path=="/auth/callback" && MessageDigest.isEqual(values["state"].orEmpty().toByteArray(),state.toByteArray())
                            val text=if(valid) "Return to Quill Personal to finish connecting." else "Invalid sign-in callback."
                            val bytes=text.toByteArray()
                            incoming.getOutputStream().write("HTTP/1.1 ${if(valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()+bytes)
                            if(valid) received=values
                        }
                    }
                    received!!
                }
                if(query.containsKey("error")) throw AiException(AiErrorKind.AUTHENTICATION,"ChatGPT sign-in was declined. Your previous connection is unchanged.")
                val clientId=query["client_id"] ?: previous?.string("client_id") ?: throw AiException(AiErrorKind.AUTHENTICATION,"ChatGPT did not issue a client registration.")
                if(clientId=="dynamic_agent_client" || (previous!=null && clientId!=previous.string("client_id"))) throw AiException(AiErrorKind.AUTHENTICATION,"Unexpected ChatGPT client registration.")
                val code=query["code"]?.takeIf { it.isNotBlank() } ?: throw AiException(AiErrorKind.AUTHENTICATION,"No authorization code was returned.")
                val tokens=exchange(mapOf("grant_type" to "authorization_code","client_id" to clientId,"code" to code,"code_verifier" to verifier,"redirect_uri" to callback,"resource" to resource))
                val claims=validateIdToken(tokens.string("id_token"),clientId,nonce)
                if(previous!=null && claims.string("sub")!=previous.string("subject")) throw AiException(AiErrorKind.AUTHENTICATION,"The signed-in account differs from the saved connection. Disconnect first to change accounts.")
                save(tokens,clientId,host,claims,previous)
                claims.string("email").ifBlank { "ChatGPT connected" }
            }
        }
    }
    private suspend fun exchange(parameters:Map<String,String>):JsonObject {
        val response=client.submitForm("$issuer/api/accounts/oauth/token",Parameters.build { parameters.forEach { (k,v)->append(k,v) } })
        if(!response.status.isSuccess()) throw AiException(AiErrorKind.AUTHENTICATION,"ChatGPT authorization expired or was rejected. Sign in again.")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }
    private fun save(tokens:JsonObject,clientId:String,host:String,claims:JsonObject,old:JsonObject?) {
        val scopes=tokens.string("scope").ifBlank { old?.string("scope").orEmpty() }
        if(!scopes.split(' ').containsAll(listOf("resource.invoke","chatgpt.tokens.use.direct"))) throw AiException(AiErrorKind.AUTHENTICATION,"This account has not authorized ChatGPT plan usage. Sign in and enable plan access.")
        val access=tokens.string("access_token")
        require(access.isNotBlank()) { "Missing access token" }
        val expiry=tokens["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        vault.put("CHATGPT",buildJsonObject {
            put("client_id",clientId); put("ext_agent_host_id",host); put("subject",claims.string("sub"))
            put("email",claims.string("email")); put("scope",scopes); put("access_token",access)
            put("refresh_token",tokens.string("refresh_token").ifBlank { old?.string("refresh_token").orEmpty() })
            put("id_token",tokens.string("id_token").ifBlank { old?.string("id_token").orEmpty() })
            put("expires_at",System.currentTimeMillis()+expiry*1000)
        }.toString())
        vault.put("chatgpt_registration",buildJsonObject {
            put("client_id",clientId);put("subject",claims.string("sub"));put("email",claims.string("email"))
        }.toString())
    }
    suspend fun accessToken():String = mutex.withLock {
        val old=record() ?: throw AiException(AiErrorKind.SETUP,"Connect ChatGPT in AI Connections first.")
        if((old["expires_at"]?.jsonPrimitive?.longOrNull ?: 0L)>System.currentTimeMillis()+60000) return@withLock old.string("access_token")
        val refresh=old.string("refresh_token")
        if(refresh.isBlank()) throw AiException(AiErrorKind.AUTHENTICATION,"ChatGPT sign-in expired. Sign in again.")
        val tokens=exchange(mapOf("grant_type" to "refresh_token","refresh_token" to refresh,"client_id" to old.string("client_id"),"resource" to resource))
        val claims=if(tokens.string("id_token").isNotBlank()) validateIdToken(tokens.string("id_token"),old.string("client_id"),null) else buildJsonObject { put("sub",old.string("subject"));put("email",old.string("email")) }
        if(claims.string("sub")!=old.string("subject")) throw AiException(AiErrorKind.AUTHENTICATION,"ChatGPT returned a different account. Reconnect.")
        save(tokens,old.string("client_id"),old.string("ext_agent_host_id"),claims,old)
        tokens.string("access_token")
    }
    private suspend fun validateIdToken(token:String,clientId:String,nonce:String?):JsonObject {
        val parts=token.split('.')
        if(parts.size!=3) throw AiException(AiErrorKind.AUTHENTICATION,"Invalid ChatGPT identity token.")
        val header=json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]))).jsonObject
        require(header.string("alg")=="RS256") { "Unsupported identity signature" }
        val configResponse=client.get("$issuer/.well-known/openid-configuration")
        require(configResponse.status.isSuccess())
        val config=json.parseToJsonElement(configResponse.bodyAsText()).jsonObject
        require(config.string("issuer")==issuer)
        val jwksUri=Uri.parse(config.string("jwks_uri"))
        require(jwksUri.scheme=="https" && jwksUri.host=="auth.openai.com")
        val jwksResponse=client.get(jwksUri.toString()); require(jwksResponse.status.isSuccess())
        val jwks=json.parseToJsonElement(jwksResponse.bodyAsText()).jsonObject
        val jwk=jwks["keys"]!!.jsonArray.first { it.jsonObject.string("kid")==header.string("kid") }.jsonObject
        require(jwk.string("kty")=="RSA")
        val decoder=Base64.getUrlDecoder()
        val key=KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1,decoder.decode(jwk.string("n"))),BigInteger(1,decoder.decode(jwk.string("e")))))
        val signature=Signature.getInstance("SHA256withRSA").apply { initVerify(key);update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)) }
        require(signature.verify(decoder.decode(parts[2]))) { "Invalid identity signature" }
        val claims=json.parseToJsonElement(String(decoder.decode(parts[1]),Charsets.UTF_8)).jsonObject
        val audience=claims["aud"]
        require(claims.string("iss")==issuer && claims.string("sub").isNotBlank())
        require((audience as? JsonPrimitive)?.contentOrNull==clientId || (audience as? JsonArray)?.any { it.jsonPrimitive.content==clientId }==true)
        require((claims["exp"]?.jsonPrimitive?.longOrNull ?: 0)>System.currentTimeMillis()/1000)
        if(nonce!=null) require(claims.string("nonce")==nonce)
        return claims
    }
}
