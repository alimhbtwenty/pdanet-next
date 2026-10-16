package io.github.alimhbtwenty.pdanext.core.xray

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One stored Xray profile: display name, full Xray JSON config, and stable ID.
 * Secret fields (configJson) are never returned in toString().
 */
data class StoredProfile(
    val id: String,
    val name: String,
    val configJson: String,
) {
    override fun toString(): String = "StoredProfile(id=$id, name=$name)"
}

/** Store of Xray profiles and user bypass domains. */
interface XrayProfileStore {
    fun list(): List<StoredProfile>
    fun get(id: String): StoredProfile?
    fun add(name: String, configJson: String, id: String = generateProfileId()): StoredProfile
    fun delete(id: String): Boolean
    var selectedId: String?
    fun getBypassDomains(): List<String>
    fun setBypassDomains(domains: List<String>)

    companion object {
        fun generateProfileId(): String = "prof_" + UUID.randomUUID().toString().replace("-", "")
    }
}

/** In-memory implementation used for tests and desktop relay. */
class InMemoryXrayProfileStore : XrayProfileStore {
    private val profiles = ConcurrentHashMap<String, StoredProfile>()
    private val order = CopyOnWriteArrayList<String>()
    private val bypassDomains = CopyOnWriteArrayList<String>()

    @Volatile
    override var selectedId: String? = null

    override fun list(): List<StoredProfile> = order.mapNotNull { profiles[it] }

    override fun get(id: String): StoredProfile? = profiles[id]

    override fun add(name: String, configJson: String, id: String): StoredProfile {
        val profile = StoredProfile(id, name, configJson)
        profiles[id] = profile
        if (!order.contains(id)) {
            order.add(id)
        }
        return profile
    }

    override fun delete(id: String): Boolean {
        if (selectedId == id) selectedId = null
        order.remove(id)
        return profiles.remove(id) != null
    }

    override fun getBypassDomains(): List<String> = bypassDomains.toList()

    override fun setBypassDomains(domains: List<String>) {
        bypassDomains.clear()
        bypassDomains.addAll(domains)
    }
}
