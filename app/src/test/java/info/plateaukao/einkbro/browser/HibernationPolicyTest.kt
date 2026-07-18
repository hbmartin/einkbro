package info.plateaukao.einkbro.browser

import info.plateaukao.einkbro.browser.HibernationPolicy.TabState
import org.junit.Assert.assertEquals
import org.junit.Test

class HibernationPolicyTest {

    private fun tab(
        id: Int,
        lastAccess: Long,
        isLive: Boolean = true,
        isEligible: Boolean = true,
    ) = TabState(albumId = id, isLive = isLive, isEligible = isEligible, lastAccess = lastAccess)

    @Test
    fun `cap of zero or below disables hibernation`() {
        val tabs = listOf(tab(1, 1), tab(2, 2), tab(3, 3))
        assertEquals(emptySet<Int>(), HibernationPolicy.selectTabsToHibernate(tabs, 0))
        assertEquals(emptySet<Int>(), HibernationPolicy.selectTabsToHibernate(tabs, -1))
    }

    @Test
    fun `live count at or under cap selects nothing`() {
        val tabs = listOf(tab(1, 1), tab(2, 2), tab(3, 3))
        assertEquals(emptySet<Int>(), HibernationPolicy.selectTabsToHibernate(tabs, 3))
        assertEquals(emptySet<Int>(), HibernationPolicy.selectTabsToHibernate(tabs, 4))
    }

    @Test
    fun `selects exactly the excess, least recently used first`() {
        val tabs = listOf(tab(1, 5), tab(2, 1), tab(3, 3), tab(4, 4), tab(5, 2))
        assertEquals(setOf(2, 5), HibernationPolicy.selectTabsToHibernate(tabs, 3))
    }

    @Test
    fun `ineligible tabs are never selected even when over cap`() {
        val tabs = listOf(
            tab(1, 1, isEligible = false),   // e.g. the current tab
            tab(2, 2),
            tab(3, 3),
        )
        assertEquals(setOf(2), HibernationPolicy.selectTabsToHibernate(tabs, 2))
    }

    @Test
    fun `excess beyond eligible count selects only eligible tabs`() {
        val tabs = listOf(
            tab(1, 1, isEligible = false),
            tab(2, 2, isEligible = false),
            tab(3, 3),
        )
        assertEquals(setOf(3), HibernationPolicy.selectTabsToHibernate(tabs, 1))
    }

    @Test
    fun `placeholders count neither as live nor as candidates`() {
        val tabs = listOf(
            tab(1, 1, isLive = false, isEligible = false),
            tab(2, 2, isLive = false, isEligible = false),
            tab(3, 3),
            tab(4, 4),
        )
        // 2 live tabs, cap 2: nothing to do despite 4 open tabs
        assertEquals(emptySet<Int>(), HibernationPolicy.selectTabsToHibernate(tabs, 2))
        // cap 1: only the older live tab goes
        assertEquals(setOf(3), HibernationPolicy.selectTabsToHibernate(tabs, 1))
    }

    @Test
    fun `untouched tabs with default access order are evicted first`() {
        val tabs = listOf(tab(1, 0), tab(2, 0), tab(3, 7))
        assertEquals(setOf(1, 2), HibernationPolicy.selectTabsToHibernate(tabs, 1))
    }
}
