package app.seniorremote.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.seniorremote.domain.CredentialStore
import app.seniorremote.domain.TvCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pairing credentials encrypted with AES-GCM under a non-exportable key held by the Android
 * Keystore. The encrypted blobs live in private app storage and are excluded from backups.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val dir = File(context.noBackupFilesDir, "credentials")

    override suspend fun save(ref: String, credentials: TvCredentials) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ciphertext = cipher.doFinal(CredentialCodec.encode(credentials))
        val blob = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeInt(cipher.iv.size)
                it.write(cipher.iv)
                it.write(ciphertext)
            }
        }.toByteArray()
        val target = file(ref)
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(blob)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        Unit
    }

    override suspend fun load(ref: String): TvCredentials? = withContext(Dispatchers.IO) {
        val source = file(ref)
        if (!source.exists()) return@withContext null
        runCatching {
            val input = DataInputStream(ByteArrayInputStream(source.readBytes()))
            val iv = ByteArray(input.readInt()).also { input.readFully(it) }
            val ciphertext = input.readBytes()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            CredentialCodec.decode(cipher.doFinal(ciphertext))
        }.getOrNull()
    }

    override suspend fun delete(ref: String) = withContext(Dispatchers.IO) {
        file(ref).delete()
        Unit
    }

    override suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        }
        Unit
    }

    private fun file(ref: String): File {
        require(ref.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "invalid credential reference" }
        return File(dir, "$ref.bin")
    }

    @Synchronized
    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "senior_remote_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal object CredentialCodec {
    private const val VERSION = 1

    fun encode(credentials: TvCredentials): ByteArray = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).use {
            it.writeInt(VERSION)
            it.writeBlock(credentials.clientPrivateKeyPkcs8)
            it.writeBlock(credentials.clientCertificateDer)
            it.writeBlock(credentials.serverPublicKeySha256)
        }
    }.toByteArray()

    fun decode(bytes: ByteArray): TvCredentials {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt() == VERSION) { "unknown credential format" }
        return TvCredentials(input.readBlock(), input.readBlock(), input.readBlock())
    }

    private fun DataOutputStream.writeBlock(block: ByteArray) {
        writeInt(block.size)
        write(block)
    }

    private fun DataInputStream.readBlock(): ByteArray {
        val size = readInt()
        require(size in 0..65_536) { "corrupt credential block" }
        return ByteArray(size).also { readFully(it) }
    }
}

class InMemoryCredentialStore : CredentialStore {
    private val entries = HashMap<String, ByteArray>()
    override suspend fun save(ref: String, credentials: TvCredentials) {
        synchronized(entries) { entries[ref] = CredentialCodec.encode(credentials) }
    }

    override suspend fun load(ref: String): TvCredentials? =
        synchronized(entries) { entries[ref] }?.let(CredentialCodec::decode)

    override suspend fun delete(ref: String) {
        synchronized(entries) { entries.remove(ref) }
    }

    override suspend fun deleteAll() {
        synchronized(entries) { entries.clear() }
    }
}
