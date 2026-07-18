package info.plateaukao.einkbro.browser

/**
 * Pure decision logic for which tabs to hibernate when the live-WebView count
 * exceeds the configured cap. No Android dependencies, unit-testable.
 */
object HibernationPolicy {

    data class TabState(
        val albumId: Int,
        /** Backed by a real EBWebView (not a placeholder). */
        val isLive: Boolean,
        /** Passed TabManager's eligibility gate (not current/translate/AI/incognito/media/data:). */
        val isEligible: Boolean,
        /** Monotonic access order; lower = least recently used. */
        val lastAccess: Long,
    )

    /** Returns albumIds to hibernate so the live count drops toward [maxLiveTabs] (<= 0 = unlimited). */
    fun selectTabsToHibernate(tabs: List<TabState>, maxLiveTabs: Int): Set<Int> {
        if (maxLiveTabs <= 0) return emptySet()
        val excess = tabs.count { it.isLive } - maxLiveTabs
        if (excess <= 0) return emptySet()
        return tabs.asSequence()
            .filter { it.isLive && it.isEligible }
            .sortedBy { it.lastAccess }
            .take(excess)
            .map { it.albumId }
            .toSet()
    }
}
