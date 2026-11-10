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

data class BuildSettings(val owner: String, val repository: String, val token: String)

object BuildSettingsStore {
    private const val PREFS = "apkforge_build_settings"
    private const val KEY_ALIAS = "apkforge_github_token_v1"

    fun load(context: Context): BuildSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = runCatching {
            val iv = Base64.decode(prefs.getString("token_iv", null), Base64.NO_WRAP)
            val encrypted = Base64.decode(prefs.getString("token_data", null), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrDefault("")
        return BuildSettings(
            prefs.getString("owner", "fzdff114") ?: "fzdff114",
            prefs.getString("repository", "APK-Forge") ?: "APK-Forge",
            token
        )
    }

    fun save(context: Context, owner: String, repository: String, token: String) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("owner", owner.trim()).putString("repository", repository.trim())
        if (token.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(token.trim().toByteArray(Charsets.UTF_8))
            editor.putString("token_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            editor.putString("token_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
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
                .setRandomizedEncryptionRequired(true).build()
        )
        return generator.generateKey()
    }
}
