package com.apkforge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiEndpointSettings(val endpoint: String, val model: String, val apiKey: String)

object AiEndpointStore {
    private const val PREFS = "apkforge_ai_settings"
    private const val KEY_ALIAS = "apkforge_ai_key_v1"

    fun load(context: Context): AiEndpointSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = runCatching {
            val iv = Base64.decode(prefs.getString("key_iv", null), Base64.NO_WRAP)
            val encrypted = Base64.decode(prefs.getString("key_data", null), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
        return AiEndpointSettings(
            prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions")
                ?: "https://api.openai.com/v1/chat/completions",
            prefs.getString("model", "") ?: "",
            key
        )
    }

    fun save(context: Context, endpoint: String, model: String, apiKey: String) {
        require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
            "API 地址必须以 https:// 或 http:// 开头"
        }
        require(model.isNotBlank()) { "请填写模型名称，APK Forge 不会替你固定模型" }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("endpoint", endpoint.trim())
            .putString("model", model.trim())
        if (apiKey.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
            editor.putString("key_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            editor.putString("key_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        editor.apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
