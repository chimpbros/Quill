package com.yugentech.quill.personal
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yugentech.quill.aira.provider.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.Socket
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.util.Base64
@RunWith(AndroidJUnit4::class)
class OAuthTest {
 private val context=InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
 private val vault=CredentialStore(context)
 private val pair=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
 private val browser=CompletableDeferred<Uri>()
 private var nonce=""
 private var forged=false
 private var refreshes=0
 private var revoked=false
 private val encoder=Base64.getUrlEncoder().withoutPadding()
 private val testContext=object:ContextWrapper(context) {
  override fun startActivity(intent:Intent) { browser.complete(intent.data!!) }
 }
 private fun jwt():String {
  val header=encoder.encodeToString("{\"alg\":\"RS256\",\"kid\":\"test-key\"}".toByteArray())
  val payload=encoder.encodeToString(buildJsonObject {
   put("iss","https://auth.openai.com");put("aud","oaiapp-test");put("sub","test-user");put("email","reader@example.test")
   put("exp",System.currentTimeMillis()/1000+3600);put("nonce",nonce)
  }.toString().toByteArray())
  val bytes=Signature.getInstance("SHA256withRSA").apply { initSign(pair.private);update("$header.$payload".toByteArray()) }.sign()
  if(forged)bytes[0]=(bytes[0].toInt() xor 1).toByte()
  return "$header.$payload.${encoder.encodeToString(bytes)}"
 }
 private fun client()=HttpClient(MockEngine { request ->
  val headers=headersOf(HttpHeaders.ContentType,"application/json")
  when(request.url.encodedPath) {
   "/.well-known/openid-configuration"->respond("{\"issuer\":\"https://auth.openai.com\",\"jwks_uri\":\"https://auth.openai.com/jwks\",\"revocation_endpoint\":\"https://auth.openai.com/revoke\"}",headers=headers)
   "/jwks"->{ val public=pair.public as RSAPublicKey
    respond(buildJsonObject { put("keys",buildJsonArray { add(buildJsonObject {
     put("kid","test-key");put("kty","RSA");put("n",encoder.encodeToString(public.modulus.toByteArray()));put("e",encoder.encodeToString(public.publicExponent.toByteArray()))
    }) }) }.toString(),headers=headers)
   }
   "/api/accounts/oauth/token"->{
    val form=parseQueryString(String((request.body as OutgoingContent.ByteArrayContent).bytes()))
    assertEquals("oaiapp-test",form["client_id"]);assertEquals("https://api.openai.com/v1",form["resource"])
    val refresh=form["grant_type"]=="refresh_token"
    if(refresh) { refreshes++;assertFalse(form.contains("scope"));assertEquals("refresh-1",form["refresh_token"]) }
    else {
     assertEquals("authorization_code",form["grant_type"]);assertEquals("test-code",form["code"])
     val digest=MessageDigest.getInstance("SHA-256").digest(form["code_verifier"]!!.toByteArray())
     assertEquals(browser.await().getQueryParameter("code_challenge"),encoder.encodeToString(digest))
     assertEquals(browser.await().getQueryParameter("redirect_uri"),form["redirect_uri"])
    }
    respond(buildJsonObject {
     put("access_token",if(refresh) "access-2" else "access-1");put("refresh_token",if(refresh) "refresh-2" else "refresh-1")
     put("expires_in",if(refresh) 3600 else 1)
     put("scope","openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
     if(!refresh)put("id_token",jwt())
    }.toString(),headers=headers)
   }
   "/revoke"->{
    val form=parseQueryString(String((request.body as OutgoingContent.ByteArrayContent).bytes()))
    assertEquals("refresh-2",form["token"]);assertEquals("refresh_token",form["token_type_hint"]);revoked=true
    respond("",HttpStatusCode.OK,headers)
   }
   else->error("Unexpected test endpoint")
  }
 })
 @Before fun reset() { listOf("CHATGPT","chatgpt_registration","chatgpt_host").forEach(vault::remove) }
 @After fun clear() { listOf("CHATGPT","chatgpt_registration","chatgpt_host").forEach(vault::remove) }
 private suspend fun callback(url:Uri,values:Map<String,String>):String=withContext(Dispatchers.IO) {
  val redirect=Uri.parse(url.getQueryParameter("redirect_uri"))
  val uri=redirect.buildUpon().apply { values.forEach { (key,value)->appendQueryParameter(key,value) } }.build()
  Socket("127.0.0.1",redirect.port).use { socket ->
   socket.soTimeout=5000
   socket.getOutputStream().write("GET ${uri.encodedPath}?${uri.encodedQuery} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
   socket.getInputStream().bufferedReader().readText()
  }
 }
 @Test fun browserCallbackPkceRefreshAndRevocationWorkOnAndroid()=runBlocking(Dispatchers.IO) {
  val http=client();val oauth=ChatGptOAuth(testContext,http,vault)
  try {
   val login=async { oauth.signIn() }
   val url=withTimeout(10000) { browser.await() };nonce=url.getQueryParameter("nonce")!!
   assertEquals("dynamic_agent_client",url.getQueryParameter("client_id"));assertEquals("S256",url.getQueryParameter("code_challenge_method"))
   assertEquals("QuillPersonal",url.getQueryParameter("agent_name_hint"))
   assertTrue(callback(url,mapOf("state" to "wrong","code" to "test-code")).startsWith("HTTP/1.1 400"))
   callback(url,mapOf("state" to url.getQueryParameter("state")!!,"code" to "test-code","client_id" to "oaiapp-test"))
   assertEquals("reader@example.test",login.await());assertTrue(oauth.connected())
   assertEquals("access-2",oauth.accessToken());assertEquals(1,refreshes)
   assertTrue(oauth.disconnect());assertTrue(revoked);assertFalse(oauth.connected())
   assertTrue(vault.get("chatgpt_registration")!!.contains("oaiapp-test"));assertNotNull(vault.get("chatgpt_host"))
  } finally { http.close() }
 }
 @Test fun deniedConsentDoesNotSaveTokens()=runBlocking(Dispatchers.IO) {
  val http=client();val oauth=ChatGptOAuth(testContext,http,vault)
  try {
   val login=async { runCatching { oauth.signIn() } }
   val url=withTimeout(10000) { browser.await() }
   callback(url,mapOf("state" to url.getQueryParameter("state")!!,"error" to "access_denied"))
   val failure=login.await().exceptionOrNull()
   assertTrue(failure is com.yugentech.quill.domain.ai.AiException)
   assertTrue(failure!!.message!!.contains("declined"));assertNull(vault.get("CHATGPT"))
  } finally { http.close() }
 }
 @Test fun forgedIdentityIsRejected()=runBlocking(Dispatchers.IO) {
  forged=true;val http=client();val oauth=ChatGptOAuth(testContext,http,vault)
  try {
   val login=async { runCatching { oauth.signIn() } }
   val url=withTimeout(10000) { browser.await() };nonce=url.getQueryParameter("nonce")!!
   callback(url,mapOf("state" to url.getQueryParameter("state")!!,"code" to "test-code","client_id" to "oaiapp-test"))
   val failure=login.await().exceptionOrNull()
   assertNotNull(failure);assertTrue(failure!!.message!!.contains("Invalid identity signature"));assertNull(vault.get("CHATGPT"))
  } finally { http.close() }
 }
 @Test fun cancellationClosesLoopbackListener()=runBlocking(Dispatchers.IO) {
  val http=client();val oauth=ChatGptOAuth(testContext,http,vault)
  try {
   val login=async { oauth.signIn() }
   val url=withTimeout(10000) { browser.await() };val port=Uri.parse(url.getQueryParameter("redirect_uri")).port
   login.cancelAndJoin()
   assertNull(vault.get("CHATGPT"))
   try { Socket("127.0.0.1",port).close();fail("Listener still open") } catch(_:java.io.IOException) { }
  } finally { http.close() }
 }
}
