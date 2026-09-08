package io.github.belzenn.androidlinuxbridge.features.notifications

/** Pure filtering and bounded deduplication; no notification contents are persisted. */
class NotificationPolicy(private val ownPackage: String, private val capacity: Int = 1000) {
    private val seen = LinkedHashMap<String, Pair<String, String>>()

    fun accept(key: String, pkg: String, enabled: Boolean, ongoing: Boolean,
               groupSummary: Boolean, title: String, text: String): Boolean {
        if (pkg == ownPackage || !enabled || ongoing || groupSummary) return false
        if (title.isBlank() && text.isBlank()) return false
        val content = title to text
        if (seen[key] == content) return false
        seen[key] = content
        if (seen.size > capacity) seen.remove(seen.keys.first())
        return true
    }

    fun remove(key: String) { seen.remove(key) }
    fun clear() { seen.clear() }
}
