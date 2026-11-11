package com.yuldash.app.data

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidParameterException
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec

/** Real crypto in the JVM platform model; no hardware or new-process persistence claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JvmEncryptedPreferencesTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun qa(label: String) = "qa_jvm_crypto_" + label + "_" + UUID.randomUUID().toString()
    private fun spec(alias: String) = KeyGenParameterSpec.Builder(alias,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
    private inline fun <reified T : Throwable> rejects(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected supported rejection category", failure is T)
    }
    private fun withFixture(block: (JvmAndroidKeyStoreFixture) -> Unit) {
        val fixture = JvmAndroidKeyStoreFixture.install()
        try { block(fixture) } finally {
            fixture.close()
            assertNull("Owned provider leaked", Security.getProvider(JvmAndroidKeyStoreFixture.NAME))
            assertEquals("Owned software keys leaked", 0, fixture.ownedKeyCount)
        }
    }
    private fun withOwnedPreferences(names: List<String>, block: () -> Unit) {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        fun files() = names.flatMap { listOf(File(directory, "$it.xml"), File(directory, "$it.xml.bak")) }
        // Refuse unknown existing data instead of clearing a different test's state.
        assertTrue("Pre-existing preference files", files().none(File::exists))
        names.forEach { assertTrue("Pre-existing preference memory", context.getSharedPreferences(it, 0).all.isEmpty()) }
        try { block() } finally {
            names.forEach { context.deleteSharedPreferences(it) }
            assertTrue("Owned preference files leaked", files().none(File::exists))
        }
    }
    private fun encrypted(name: String, alias: String): EncryptedSharedPreferences {
        val key = MasterKey.Builder(context, alias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(context, name, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        assertEquals(EncryptedSharedPreferences::class.java, prefs.javaClass)
        return prefs as EncryptedSharedPreferences
    }
    @Test fun realEncryptedPreferencesRoundTripAndReopenWhileSoftwareKeyLives() = withFixture { fixture ->
        assertNotEquals(JvmAndroidKeyStoreFixture.NAME, fixture.softwareProviderName)
        val name = qa("prefs")
        val alias = qa("key")
        withOwnedPreferences(listOf(name)) {
            val first = encrypted(name, alias)
            val marker = "synthetic-private-value-" + UUID.randomUUID().toString()
            assertTrue(first.edit().putString("private_field", marker).commit())
            assertEquals(marker, first.getString("private_field", null))
            val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            assertFalse("Plaintext field name reached disk", raw.contains("private_field"))
            assertFalse("Plaintext value reached disk", raw.all.toString().contains(marker))
            val reopened = encrypted(name, alias)
            assertNotSame(first, reopened)
            assertEquals(marker, reopened.getString("private_field", null))
            assertEquals(1, fixture.ownedKeyCount)
        }
    }
    @Test fun defaultSnapshotFactoryUsesRealEncryptionWithoutPlaintextPayload() = withFixture {
        withOwnedPreferences(listOf(SNAPSHOT_META_PREFS, SNAPSHOT_SECURE_PREFS)) {
            val disk = SnapshotPreferencesDisk.open(context)
            val field = SnapshotPreferencesDisk::class.java.getDeclaredField("secure").apply { isAccessible = true }
            assertEquals(EncryptedSharedPreferences::class.java, requireNotNull(field.get(disk)).javaClass)
            val namespace = snapshotHash("synthetic default-factory namespace")
            val marker = "synthetic canonical payload " + UUID.randomUUID().toString()
            assertTrue(disk.write(namespace, marker))
            assertEquals(marker, disk.read(namespace))
            val raw = context.getSharedPreferences(SNAPSHOT_SECURE_PREFS, 0)
            assertFalse(raw.contains(namespace))
            assertFalse(raw.all.toString().contains(marker))
            val reopened = SnapshotPreferencesDisk.open(context)
            assertNotSame(disk, reopened)
            assertEquals(EncryptedSharedPreferences::class.java, requireNotNull(field.get(reopened)).javaClass)
            assertEquals(marker, reopened.read(namespace))
            assertFalse(context.getSharedPreferences(SNAPSHOT_META_PREFS, 0).all.toString().contains(marker))
        }
    }
    @Test fun unsupportedParametersServicesAndPersistentKeysAreRejected() = withFixture { fixture ->
        val generator = KeyGenerator.getInstance("AES", JvmAndroidKeyStoreFixture.NAME)
        rejects<InvalidParameterException> { generator.init(128) }
        rejects<InvalidAlgorithmParameterException> { generator.init(IvParameterSpec(ByteArray(12))) }
        rejects<InvalidAlgorithmParameterException> { generator.init(spec("external_alias")) }
        rejects<InvalidAlgorithmParameterException> { generator.init(spec("qa_other_namespace")) }
        val wrongMode = KeyGenParameterSpec.Builder(qa("cbc"), 3).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        rejects<InvalidAlgorithmParameterException> { generator.init(wrongMode) }
        val hardware = KeyGenParameterSpec.Builder(qa("hardware"), 3).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setIsStrongBoxBacked(true).build()
        rejects<InvalidAlgorithmParameterException> { generator.init(hardware) }
        val auth = KeyGenParameterSpec.Builder(qa("auth"), 3).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true).build()
        rejects<InvalidAlgorithmParameterException> { generator.init(auth) }
        generator.init(spec(qa("discarded")))
        rejects<InvalidParameterException> { generator.init(128) }
        rejects<IllegalStateException> { generator.generateKey() }
        generator.init(spec(qa("null_discarded")))
        val nullInit = runCatching { generator.init(null as AlgorithmParameterSpec?) }.exceptionOrNull()
        val afterNull = runCatching { generator.generateKey() }.exceptionOrNull()
        assertTrue("nullInit=" + (nullInit?.javaClass?.name ?: "NONE") +
            "; generateAfterNull=" + (afterNull?.javaClass?.name ?: "NONE"),
            nullInit is InvalidAlgorithmParameterException && afterNull is IllegalStateException)
        assertEquals(0, fixture.ownedKeyCount)
        rejects<NoSuchAlgorithmException> { KeyGenerator.getInstance("RSA", JvmAndroidKeyStoreFixture.NAME) }
        val store = KeyStore.getInstance(JvmAndroidKeyStoreFixture.NAME).apply { load(null) }
        rejects<UnsupportedOperationException> { store.store(ByteArrayOutputStream(), null) }
    }
    @Test fun closeRemovesOnlyOwnProviderAndSoftwareKeysCannotCrossInstallations() {
        val fixture = JvmAndroidKeyStoreFixture.install()
        val alias = qa("lifecycle")
        lateinit var store: KeyStore
        try {
            store = KeyStore.getInstance(JvmAndroidKeyStoreFixture.NAME).apply { load(null) }
            KeyGenerator.getInstance("AES", JvmAndroidKeyStoreFixture.NAME).apply { init(spec(alias)) }.generateKey()
            assertTrue(store.containsAlias(alias))
            assertEquals(1, fixture.ownedKeyCount)
        } finally { fixture.close() }
        assertNull(Security.getProvider(JvmAndroidKeyStoreFixture.NAME))
        assertEquals(0, fixture.ownedKeyCount)
        rejects<IllegalStateException> { store.containsAlias(alias) }
        withFixture {
            assertFalse(KeyStore.getInstance(JvmAndroidKeyStoreFixture.NAME).apply { load(null) }.containsAlias(alias))
        }
    }
    private fun foreignProvider() = object : Provider(JvmAndroidKeyStoreFixture.NAME, 1.0, "Owned collision fixture") {}
    private fun registerExact(provider: Provider) = synchronized(Security::class.java) {
        assertNull("Refuse pre-existing foreign provider", Security.getProvider(provider.name))
        assertTrue(Security.addProvider(provider) > 0)
        assertSame(provider, Security.getProvider(provider.name))
    }
    private fun removeExact(provider: Provider) = synchronized(Security::class.java) {
        assertSame("Refuse unknown provider cleanup", provider, Security.getProvider(provider.name))
        Security.removeProvider(provider.name)
        assertNull(Security.getProvider(provider.name))
    }
    @Test fun foreignProviderCollisionAndReplacementArePreserved() {
        val foreign = foreignProvider()
        registerExact(foreign)
        try {
            rejects<IllegalStateException> { JvmAndroidKeyStoreFixture.install() }
            assertSame(foreign, Security.getProvider(foreign.name))
        } finally { removeExact(foreign) }
        val fixture = JvmAndroidKeyStoreFixture.install()
        var replacement: Provider? = null
        var closeAttempted = false
        try {
            val alias = qa("replacement")
            KeyGenerator.getInstance("AES", JvmAndroidKeyStoreFixture.NAME).apply { init(spec(alias)) }.generateKey()
            removeExact(fixture.providerReference)
            val candidate = foreignProvider()
            registerExact(candidate)
            replacement = candidate
            closeAttempted = true
            rejects<IllegalStateException> { fixture.close() }
            assertEquals(0, fixture.ownedKeyCount)
            assertSame(candidate, Security.getProvider(candidate.name))
        } finally {
            try { replacement?.let(::removeExact) } finally {
                if (!closeAttempted) fixture.close()
            }
        }
    }
}
