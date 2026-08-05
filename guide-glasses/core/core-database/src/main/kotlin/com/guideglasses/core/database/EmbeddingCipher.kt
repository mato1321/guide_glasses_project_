package com.guideglasses.core.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 人臉特徵向量的加密與解密。
 *
 * 人臉特徵是**生物特徵**，屬《個人資料保護法》的特種個資。即使它只存在
 * 裝置上，也不應該以明文躺在 SQLite 檔案裡 —— 一支 root 過的手機、
 * 一次備份外流、一台送修的裝置，都可能讓它落到別人手上。
 *
 * 金鑰由 Android Keystore 產生並保管，**永遠不會離開 Keystore**，
 * 程式碼只能請它加解密，拿不到金鑰本體。App 被移除時金鑰一併消失。
 *
 * 用 AES/GCM —— 它同時提供機密性與完整性，被竄改的密文解不開而不是
 * 悄悄解成錯誤的向量。
 */
class EmbeddingCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /** 加密後的資料。IV 必須與密文一起存，解密時要用。 */
    data class Encrypted(val cipherText: ByteArray, val iv: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Encrypted) return false
            return cipherText.contentEquals(other.cipherText) && iv.contentEquals(other.iv)
        }

        override fun hashCode(): Int = 31 * cipherText.contentHashCode() + iv.contentHashCode()
    }

    fun encrypt(values: FloatArray): Encrypted {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Encrypted(
            cipherText = cipher.doFinal(values.toBytes()),
            // GCM 的 IV 每次加密都不同，由 Cipher 自動產生。
            iv = cipher.iv,
        )
    }

    /** @return 解密後的向量，失敗（金鑰遺失、密文被竄改）時為 null。 */
    fun decrypt(encrypted: Encrypted): FloatArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, encrypted.iv),
        )
        cipher.doFinal(encrypted.cipherText).toFloatArray()
    }.getOrNull()

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 刻意不要求使用者驗證 —— 導盲系統必須在使用者無法操作螢幕時
                // 也能運作，要求解鎖會讓功能在最需要的時候失效。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "guideglasses.face.embedding"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128

        fun FloatArray.toBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(size * Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
            forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        fun ByteArray.toFloatArray(): FloatArray {
            val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(size / Float.SIZE_BYTES) { buffer.getFloat() }
        }
    }
}
