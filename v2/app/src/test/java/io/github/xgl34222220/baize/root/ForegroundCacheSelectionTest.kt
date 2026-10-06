package io.github.xgl34222220.baize.root

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ForegroundCacheSelectionTest {
    private fun snapshot(): ForegroundCacheEngine.Snapshot {
        val items = listOf("first", "second").mapIndexed { index, name ->
            ForegroundCacheEngine.Item("example.$name", name, "应用缓存", "/data/user/0/example.$name/cache",
                (index + 1L) * 512, 1, 0, identity = "identity-$name")
        }
        return ForegroundCacheEngine.Snapshot("foreground-token", 123L, items, 1536, 2, 0, 7)
    }
    @Test fun selectedPathsRetainTheirOriginalIdentityAndUnselectedItemsStayOut() {
        val original = snapshot()
        val selected = selectForegroundCacheSnapshot(original, JSONObject().put(original.items[0].path, true).toString())
        assertEquals(original.id, selected.id)
        assertEquals(listOf(original.items[0]), selected.items)
        assertEquals(512L, selected.totalBytes)
        assertEquals(2, original.items.size)
    }
    @Test fun legacyExplicitAllSafeStillUsesOnlyThisSnapshot() {
        assertEquals(snapshot().items, selectForegroundCacheSnapshot(snapshot(), "{\"__all_safe__\":true}").items)
    }
    @Test fun emptyUnknownAndNonBooleanSelectionsFailClosed() {
        for (raw in listOf("{}", "{\"__all_safe__\":false}", "{\"/data/personal\":true}",
            "{\"__all_safe__\":\"true\"}", "{\"__all_safe__\":true,\"/data/personal\":true}")) {
            assertThrows(Exception::class.java) { selectForegroundCacheSnapshot(snapshot(), raw) }
        }
    }
}
