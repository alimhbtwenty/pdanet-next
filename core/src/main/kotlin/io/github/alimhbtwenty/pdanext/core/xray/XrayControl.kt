package io.github.alimhbtwenty.pdanext.core.xray

import io.github.alimhbtwenty.pdanext.core.router.BypassPolicy
import io.github.alimhbtwenty.pdanext.core.router.Upstream

/**
 * High-level control combining [XrayProfileStore] and [XrayEngine].
 * On select -> engine.start(config). On off (id = null) -> engine.stop().
 */
class XrayControl(
    val store: XrayProfileStore,
    val engine: XrayEngine,
    private val bypassPolicy: BypassPolicy? = null,
) {
    init {
        bypassPolicy?.setDomains(store.getBypassDomains())
    }

    val isRunning: Boolean get() = engine.isRunning

    val selectedId: String? get() = store.selectedId

    val selectedProfile: StoredProfile? get() = store.selectedId?.let { store.get(it) }

    val selectedName: String? get() = selectedProfile?.name

    val activeTunnel: Upstream? get() = if (store.selectedId != null) engine.tunnel else null

    fun select(id: String?) {
        if (id == null) {
            store.selectedId = null
            engine.stop()
            return
        }
        val profile = store.get(id) ?: throw IllegalArgumentException("unknown profile")
        engine.start(profile.configJson)
        store.selectedId = id
    }

    fun startSelected() {
        val id = store.selectedId ?: return
        val profile = store.get(id)
        if (profile != null) {
            engine.start(profile.configJson)
        } else {
            store.selectedId = null
        }
    }

    fun delete(id: String): Boolean {
        if (store.selectedId == id) {
            select(null)
        }
        return store.delete(id)
    }

    fun importLinks(text: String): List<String> {
        val imported = engine.importLinks(text)
        return imported.map { store.add(it.name, it.configJson).id }
    }

    fun measureDelay(id: String): Long? {
        val profile = store.get(id) ?: return null
        return engine.measureDelay(profile.configJson)
    }

    fun getBypassDomains(): List<String> = store.getBypassDomains()

    fun setBypassDomains(domains: List<String>) {
        store.setBypassDomains(domains)
        bypassPolicy?.setDomains(domains)
    }
}
