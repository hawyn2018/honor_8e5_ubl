/*
 * ADB RSA public-key representation and TLS identity flow adapted from
 * RikkaApps/Shizuku v13.6.0 (Apache-2.0). See assets/licenses/Shizuku-NOTICE.md.
 */
package dev.honor.bootforge.wireless

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.conscrypt.Conscrypt
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** One persistent identity for this application's own user-approved ADB pairing. */
internal class AdbIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("wireless_adb_identity", Context.MODE_PRIVATE)
    private val privateKey: RSAPrivateKey
    private val publicKey: RSAPublicKey

    init {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secret = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        val saved = preferences.getString("encrypted_private_key", null)
        privateKey = if (saved != null) {
            // Fail explicitly if the Keystore key was lost. Silently rotating the key would
            // invalidate the device-side pairing while making the UI still look paired.
            val encrypted = Base64.decode(saved, Base64.NO_WRAP)
            require(encrypted.size > 28) { "ADB identity is damaged; forget pairing and pair again" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, encrypted, 0, 12))
            cipher.updateAAD(AAD)
            val encoded = cipher.doFinal(encrypted, 12, encrypted.size - 12)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(encoded)) as RSAPrivateKey
        } else {
            val generated = KeyPairGenerator.getInstance("RSA").apply {
                initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            }.generateKeyPair().private as RSAPrivateKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            cipher.updateAAD(AAD)
            val encrypted = cipher.iv + cipher.doFinal(generated.encoded)
            check(preferences.edit().putString("encrypted_private_key", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
                "Failed to persist ADB identity"
            }
            generated
        }
        publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey
    }

    val adbPublicKey: ByteArray by lazy { encodeAdbPublicKey(publicKey, "honor_ubl@android") }

    /** ADB AUTH already supplies a SHA-1 digest; do not hash it a second time. */
    fun signToken(token: ByteArray): ByteArray {
        require(token.size == 20) { "Invalid ADB authentication token" }
        val digestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val block = ByteArray(256) { 0xff.toByte() }
        block[0] = 0
        block[1] = 1
        block[256 - digestInfo.size - token.size - 1] = 0
        digestInfo.copyInto(block, 256 - digestInfo.size - token.size)
        token.copyInto(block, 256 - token.size)
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, privateKey)
            doFinal(block)
        }
    }

    val sslContext: SSLContext by lazy {
        val certificate = createCertificate(privateKey, publicKey)
        val manager = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
                if (types?.any { it == "RSA" || it == "RSASSA-PSS" } == true) "adb" else null
            override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
                chooseClientAlias(types, issuers, null)
            override fun getCertificateChain(alias: String?) = if (alias == "adb") arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "adb") privateKey else null
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) =
                if (type == "RSA") arrayOf("adb") else null
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
        // Wireless ADB uses its pairing code + SPAKE2 to authenticate the peer. adbd's
        // self-signed TLS certificate is deliberately not a Web PKI certificate.
        val trust = object : X509ExtendedTrustManager() {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(arrayOf(manager), arrayOf(trust), SecureRandom())
        }
    }

    companion object {
        private const val KEY_ALIAS = "bootforge.wireless.adb.key.encryption.v1"
        private val AAD = "bootforge-wireless-adb-v1".toByteArray(Charsets.US_ASCII)

        // ADB's 524-byte RSAPublicKey struct, not the usual ASN.1 public key.
        internal fun encodeAdbPublicKey(key: RSAPublicKey, name: String): ByteArray {
            require(key.modulus.bitLength() == 2048)
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = key.modulus.mod(r32).modInverse(r32).negate().toInt()
            val rr = BigInteger.ONE.shiftLeft(4096).mod(key.modulus)
            val data = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            data.putInt(64).putInt(n0inv)
            fun putWords(number: BigInteger) {
                repeat(64) { word -> data.putInt(number.shiftRight(word * 32).toInt()) }
            }
            putWords(key.modulus)
            putWords(rr)
            data.putInt(key.publicExponent.toInt())
            return (Base64.encodeToString(data.array(), Base64.NO_WRAP) + " $name\u0000").toByteArray(Charsets.UTF_8)
        }

        /** Small standard DER certificate builder, avoiding platform-hidden X509 APIs. */
        internal fun createCertificate(privateKey: PrivateKey, publicKey: RSAPublicKey): X509Certificate {
            fun der(tag: Int, bytes: ByteArray): ByteArray {
                val size = bytes.size
                val length = when {
                    size < 128 -> byteArrayOf(size.toByte())
                    size < 256 -> byteArrayOf(0x81.toByte(), size.toByte())
                    else -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
                }
                return byteArrayOf(tag.toByte()) + length + bytes
            }
            fun sequence(vararg bytes: ByteArray) = der(0x30, bytes.fold(byteArrayOf()) { a, b -> a + b })
            val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00)
            val name = sequence(der(0x31, sequence(byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), der(0x0c, "honor_ubl ADB".toByteArray()))))
            val validity = sequence(der(0x18, "20200101000000Z".toByteArray()), der(0x18, "21000101000000Z".toByteArray()))
            val tbs = sequence(der(0xa0, byteArrayOf(0x02, 0x01, 0x02)), byteArrayOf(0x02, 0x01, 0x01), algorithm, name, validity, name, publicKey.encoded)
            val signature = Signature.getInstance("SHA256withRSA").run { initSign(privateKey); update(tbs); sign() }
            val encoded = sequence(tbs, algorithm, der(0x03, byteArrayOf(0) + signature))
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
        }
    }
}
