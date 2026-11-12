package com.example.smartnode.data

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Cifrado AES-128-CBC (PKCS7) compartido con el ESP32.
// Formato: Base64( IV(16) + ciphertext ) — igual que encryptPayload()/decryptPayload() del sketch.
// La clave debe ser idéntica a AES_KEY del sketch (16 bytes).
object CryptoUtil {

    private const val KEY = "SmartNode2026Key"

    fun encrypt(plaintext: String): String = try {
        val key = SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(data, Base64.NO_WRAP)
    } catch (_: Exception) {
        ""
    }

    fun decrypt(b64: String): String? = try {
        val data = Base64.decode(b64, Base64.NO_WRAP)
        if (data.size < 32 || ((data.size - 16) % 16) != 0) {
            null
        } else {
            val key = SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(data.copyOfRange(0, 16)))
            String(cipher.doFinal(data.copyOfRange(16, data.size)), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }
}
