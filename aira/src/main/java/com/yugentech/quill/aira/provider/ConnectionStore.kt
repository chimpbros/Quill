package com.yugentech.quill.aira.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.yugentech.quill.domain.ai.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_credentials", Context.MODE_PRIVATE)
    private val alias = "quill_personal_credentials"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString(name, encoded).commit()) { "Unable to save credentials" }
    }
    @Synchronized fun get(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { throw AiException(AiErrorKind.AUTHENTICATION, "Saved credentials cannot be decrypted. Reconnect this provider.") }
    }
    fun remove(name: String) { check(prefs.edit().remove(name).commit()) }
}

class ConnectionStore(context: Context) {
    val preferences = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
    val revision = MutableStateFlow(0L)
    fun changed() { revision.value += 1 }
    fun selected(slot: String = "text"): AiConnection? {
        val provider = preferences.getString("${slot}_provider", null)?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() } ?: return null
        val model = preferences.getString("${slot}_model", "").orEmpty()
        if (model.isBlank()) return null
        if (slot == "image" && provider == AiProvider.CHATGPT) return null
        return AiConnection(provider, model, contextTokens = preferences.getInt("${slot}_context", 32768))
    }
    fun select(slot: String, connection: AiConnection) {
        require(slot in listOf("text", "image"))
        require(connection.model.isNotBlank() && connection.contextTokens >= 8192)
        require(slot != "image" || connection.provider != AiProvider.CHATGPT)
        check(preferences.edit().putString("${slot}_provider", connection.provider.name)
            .putString("${slot}_model", connection.model.trim()).putInt("${slot}_context", connection.contextTokens).commit())
        changed()
    }
    fun disconnect(provider: AiProvider) {
        val edit = preferences.edit()
        for (slot in listOf("text", "image")) if (selected(slot)?.provider == provider) {
            edit.remove("${slot}_provider").remove("${slot}_model")
        }
        check(edit.commit()); changed()
    }
    var cloudSpeech: Boolean
        get() = preferences.getBoolean("cloud_speech", false)
        set(value) { preferences.edit().putBoolean("cloud_speech", value).apply(); changed() }
    var voice: String
        get() = preferences.getString("voice", "coral") ?: "coral"
        set(value) { preferences.edit().putString("voice", value).apply(); changed() }
}
