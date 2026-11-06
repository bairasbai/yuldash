package com.yuldash.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.MasterKey
import java.io.InputStream
import java.io.OutputStream
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidParameterException
import java.security.Key
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/** JVM platform model only: software AES keys live in this fixture's memory.
 * Real MasterKey, EncryptedSharedPreferences and Tink still perform their normal cryptography.
 * This supplies neither hardware protection nor key/process durability. Never use in app sources.
 */
internal class JvmAndroidKeyStoreFixture private constructor(private val software: Provider) : AutoCloseable {
    companion object {
        const val NAME = "AndroidKeyStore"
        fun install(): JvmAndroidKeyStoreFixture = synchronized(Security::class.java) {
            check(Security.getProvider(NAME) == null) { "A foreign AndroidKeyStore provider already exists" }
            // Select the concrete software provider BEFORE registering our AES service.
            val software = KeyGenerator.getInstance("AES").provider
            check(software.name != NAME) { "Recursive software key generator" }
            val owner = JvmAndroidKeyStoreFixture(software)
            val proposed = OwnedProvider(owner)
            check(Security.addProvider(proposed) > 0) { "Provider registration failed" }
            val actual = Security.getProvider(NAME)
            check(actual === proposed) { "Registered provider ownership is unknown" }
            owner.registered = requireNotNull(actual)
            owner
        }
    }

    private data class OwnedKey(val key: SecretKey, val created: Long)
    private val lock = Any()
    private val keys = mutableMapOf<String, OwnedKey>()
    private lateinit var registered: Provider
    private var closed = false
    internal val providerReference: Provider get() = registered
    internal val ownedKeyCount: Int get() = synchronized(lock) { keys.size }
    internal val softwareProviderName: String get() = software.name

    private fun live() {
        check(!closed && Security.getProvider(NAME) === registered) { "JVM keystore owner is not current" }
    }
    private fun alias(value: String) {
        require(value == MasterKey.DEFAULT_MASTER_KEY_ALIAS || value.matches(Regex("qa_jvm_crypto_[A-Za-z0-9_.-]{1,160}"))) {
            "Unsupported JVM fixture alias"
        }
    }
    private fun validate(spec: KeyGenParameterSpec) {
        try { alias(spec.keystoreAlias) } catch (_: IllegalArgumentException) {
            throw InvalidAlgorithmParameterException("Unsupported JVM fixture alias")
        }
        val supported = spec.keySize == 256 &&
            spec.purposes == (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) &&
            spec.blockModes.contentEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM)) &&
            spec.encryptionPaddings.contentEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE)) &&
            !spec.isUserAuthenticationRequired && !spec.isStrongBoxBacked && spec.isRandomizedEncryptionRequired &&
            !spec.isUserPresenceRequired && !spec.isUserConfirmationRequired && !spec.isUnlockedDeviceRequired &&
            !spec.isUserAuthenticationValidWhileOnBody && !spec.isDigestsSpecified && spec.signaturePaddings.isEmpty() &&
            spec.attestationChallenge == null && spec.keyValidityStart == null &&
            spec.keyValidityForOriginationEnd == null && spec.keyValidityForConsumptionEnd == null
        if (!supported) throw InvalidAlgorithmParameterException("Unsupported JVM key parameters")
    }
    private fun generate(spec: KeyGenParameterSpec, random: SecureRandom?): SecretKey = synchronized(lock) {
        live(); validate(spec)
        check(!keys.containsKey(spec.keystoreAlias)) { "JVM fixture key already exists" }
        val key = KeyGenerator.getInstance("AES", software).apply {
            if (random == null) init(256) else init(256, random)
        }.generateKey()
        check(key.algorithm == "AES") { "Unexpected software key algorithm" }
        keys[spec.keystoreAlias] = OwnedKey(key, System.currentTimeMillis())
        key
    }
    private fun key(value: String): Key? = synchronized(lock) { live(); alias(value); keys[value]?.key }
    private fun contains(value: String): Boolean = synchronized(lock) { live(); alias(value); keys.containsKey(value) }
    private fun date(value: String): Date? = synchronized(lock) { live(); alias(value); keys[value]?.let { Date(it.created) } }
    private fun delete(value: String) = synchronized(lock) { live(); alias(value); keys.remove(value); Unit }
    private fun aliases(): List<String> = synchronized(lock) { live(); keys.keys.sorted() }
    private fun assertLive() = synchronized(lock) { live() }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        try {
            synchronized(Security::class.java) {
                check(Security.getProvider(NAME) === registered) { "Refuse to remove an unknown provider" }
                Security.removeProvider(NAME)
                check(Security.getProvider(NAME) == null) { "Owned provider removal failed" }
            }
        } finally {
            keys.clear()
            closed = true
        }
    }

    private class OwnedProvider(private val owner: JvmAndroidKeyStoreFixture) :
        Provider(NAME, 1.0, "JVM-only software keystore fixture") {
        init {
            putService(object : Provider.Service(this, "KeyStore", NAME, Store::class.java.name, emptyList(), emptyMap()) {
                override fun newInstance(constructorParameter: Any?): Any {
                    if (constructorParameter != null) throw NoSuchAlgorithmException("Unsupported service parameter")
                    owner.assertLive(); return Store(owner)
                }
            })
            putService(object : Provider.Service(this, "KeyGenerator", "AES", Generator::class.java.name, emptyList(), emptyMap()) {
                override fun newInstance(constructorParameter: Any?): Any {
                    if (constructorParameter != null) throw NoSuchAlgorithmException("Unsupported service parameter")
                    owner.assertLive(); return Generator(owner)
                }
            })
        }
    }
    private class Generator(private val owner: JvmAndroidKeyStoreFixture) : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null
        private var random: SecureRandom? = null
        override fun engineInit(random: SecureRandom?) { spec = null; this.random = null; throw InvalidParameterException("Explicit key parameters required") }
        override fun engineInit(keysize: Int, random: SecureRandom?) { spec = null; this.random = null; throw InvalidParameterException("Explicit key parameters required") }
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = null; this.random = null
            owner.assertLive()
            val candidate = params as? KeyGenParameterSpec
                ?: throw InvalidAlgorithmParameterException("Only KeyGenParameterSpec is supported")
            owner.validate(candidate); spec = candidate; this.random = random
        }
        override fun engineGenerateKey(): SecretKey = owner.generate(
            spec ?: throw IllegalStateException("Explicit key parameters required"), random)
    }
    private class Store(private val owner: JvmAndroidKeyStoreFixture) : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            if (password != null) throw UnrecoverableKeyException("Passwords are unsupported")
            return owner.key(alias)
        }
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? { owner.contains(alias); return null }
        override fun engineGetCertificate(alias: String): Certificate? { owner.contains(alias); return null }
        override fun engineGetCreationDate(alias: String): Date? = owner.date(alias)
        override fun engineDeleteEntry(alias: String) = owner.delete(alias)
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(owner.aliases())
        override fun engineContainsAlias(alias: String): Boolean = owner.contains(alias)
        override fun engineSize(): Int = owner.aliases().size
        override fun engineIsKeyEntry(alias: String): Boolean = owner.contains(alias)
        override fun engineIsCertificateEntry(alias: String): Boolean { owner.contains(alias); return false }
        override fun engineGetCertificateAlias(cert: Certificate): String? { owner.assertLive(); return null }
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            throw KeyStoreException("Only generated fixture keys are supported")
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
            throw KeyStoreException("Imported keys are unsupported")
        }
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) {
            throw KeyStoreException("Certificates are unsupported")
        }
        override fun engineStore(stream: OutputStream, password: CharArray?) {
            throw UnsupportedOperationException("Persisting JVM fixture keys is unsupported")
        }
        override fun engineLoad(stream: InputStream?, password: CharArray?) {
            owner.assertLive()
            if (stream != null || password != null) throw UnsupportedOperationException("Loading external keys is unsupported")
        }
    }
}
