package io.github.alimhbtwenty.pdanext.xray

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import io.github.alimhbtwenty.pdanext.core.xray.StoredProfile
import io.github.alimhbtwenty.pdanext.core.xray.XrayProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * XrayProfileStore implementation for Android encrypted with an Android Keystore
 * AES-GCM key in app-private storage.
 */
class AndroidXrayProfileStore(private val context: Context) : XrayProfileStore {

    private val profiles = linkedMapOf<String, StoredProfile>()
    private val bypassDomains = mutableListOf<String>()

    @Volatile
    private var _selectedId: String? = null

    override var selectedId: String?
        get() = synchronized(this) { _selectedId }
        set(value) = synchronized(this) {
            _selectedId = value
            saveToDisk()
        }

    init {
        loadFromDisk()
    }

    @Synchronized
    override fun list(): List<StoredProfile> = profiles.values.toList()

    @Synchronized
    override fun get(id: String): StoredProfile? = profiles[id]

    @Synchronized
    override fun add(name: String, configJson: String, id: String): StoredProfile {
        val profile = StoredProfile(id, name, configJson)
        profiles[id] = profile
        saveToDisk()
        return profile
    }

    @Synchronized
    override fun delete(id: String): Boolean {
        if (_selectedId == id) {
            _selectedId = null
        }
        val removed = profiles.remove(id) != null
        if (removed) {
            saveToDisk()
        }
        return removed
    }

    @Synchronized
    override fun getBypassDomains(): List<String> = bypassDomains.toList()

    @Synchronized
    override fun setBypassDomains(domains: List<String>) {
        bypassDomains.clear()
        bypassDomains.addAll(domains)
        saveToDisk()
    }

    private fun getStorageFile(): File = File(context.filesDir, FILE_NAME)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainBytes: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plainBytes)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return combined
    }

    private fun decrypt(encryptedBytes: ByteArray): ByteArray {
        if (encryptedBytes.size < GCM_IV_LENGTH) throw IllegalArgumentException("encrypted data too short")
        val iv = ByteArray(GCM_IV_LENGTH)
        val ciphertext = ByteArray(encryptedBytes.size - GCM_IV_LENGTH)
        System.arraycopy(encryptedBytes, 0, iv, 0, GCM_IV_LENGTH)
        System.arraycopy(encryptedBytes, GCM_IV_LENGTH, ciphertext, 0, ciphertext.size)
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }

    @Synchronized
    private fun loadFromDisk() {
        val file = getStorageFile()
        if (!file.exists()) return
        try {
            val encryptedBytes = file.readBytes()
            if (encryptedBytes.isEmpty()) return
            val plainBytes = decrypt(encryptedBytes)
            val jsonStr = String(plainBytes, Charsets.UTF_8)
            val root = JSONObject(jsonStr)

            _selectedId = if (root.isNull("selectedId")) null else root.optString("selectedId", null)
            profiles.clear()
            val arr = root.optJSONArray("profiles") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val cfg = obj.getString("configJson")
                profiles[id] = StoredProfile(id, name, cfg)
            }

            bypassDomains.clear()
            val bypassArr = root.optJSONArray("bypassDomains") ?: JSONArray()
            for (i in 0 until bypassArr.length()) {
                bypassDomains.add(bypassArr.getString(i))
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to load encrypted profiles: ${e.javaClass.simpleName}") // never the message: it can quote the config
        }
    }

    @Synchronized
    private fun saveToDisk() {
        try {
            val root = JSONObject()
            if (_selectedId != null) {
                root.put("selectedId", _selectedId)
            } else {
                root.put("selectedId", JSONObject.NULL)
            }
            val arr = JSONArray()
            for (p in profiles.values) {
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                obj.put("configJson", p.configJson)
                arr.put(obj)
            }
            root.put("profiles", arr)

            val bypassArr = JSONArray()
            for (d in bypassDomains) {
                bypassArr.put(d)
            }
            root.put("bypassDomains", bypassArr)

            val plainBytes = root.toString().toByteArray(Charsets.UTF_8)
            val encryptedBytes = encrypt(plainBytes)

            val file = getStorageFile()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(encryptedBytes)
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "failed to save encrypted profiles: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "XrayProfileStore"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "pdanext_xray_store_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val FILE_NAME = "xray_profiles.enc"
    }
}
