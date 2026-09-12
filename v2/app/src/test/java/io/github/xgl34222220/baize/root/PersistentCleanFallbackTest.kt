package io.github.xgl34222220.baize.root

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PersistentCleanFallbackTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun exceptionAfterADeletionNeverReplaysThePersistedPlan() {
        val processed = folder.newFile("already-processed.tmp")
        val remaining = folder.newFile("remaining.tmp")
        var nativeCalls = 0
        var persistedCalls = 0
        val result = JSONObject(PersistentCleanFallback.execute(
            native = {
                nativeCalls++
                assertTrue(processed.delete())
                throw IOException("progress reply failed after deleting the first file")
            },
            persisted = {
                persistedCalls++
                remaining.delete()
                "{\"success\":true}"
            }
        ))
        assertEquals(1, nativeCalls)
        assertEquals(0, persistedCalls)
        assertFalse(processed.exists())
        assertTrue("An unconfirmed first attempt must not start another deletion", remaining.exists())
        assertEquals("persistent_clean_unconfirmed", result.getString("error"))
        assertTrue(result.getBoolean("resultUnconfirmed"))
        assertFalse(result.getBoolean("success"))
    }

    @Test fun onlyAnExplicitExpiredNativeSnapshotMayUseThePersistedPlan() {
        var nativeCalls = 0
        var persistedCalls = 0
        val expected = "{\"success\":true,\"persistedFallback\":true,\"deletedFiles\":3}"
        val actual = PersistentCleanFallback.execute(
            native = { nativeCalls++; JSONObject().put("error", "snapshot_expired") },
            persisted = { persistedCalls++; expected }
        )
        assertEquals(expected, actual)
        assertEquals(1, nativeCalls)
        assertEquals(1, persistedCalls)
    }

    @Test fun successProtectionCancellationAndOtherErrorsNeverFallBack() {
        val outcomes = listOf(
            JSONObject().put("success", true).put("deletedFiles", 2),
            JSONObject().put("success", true).put("cancelled", true),
            JSONObject().put("success", true).put("timedOut", true),
            JSONObject().put("error", "rules_changed"),
            JSONObject().put("error", "empty_selection"),
            JSONObject().put("error", "profile_clean_failed")
        )
        var persistedCalls = 0
        outcomes.forEach { nativeResult ->
            val actual = PersistentCleanFallback.execute(
                native = { nativeResult },
                persisted = { persistedCalls++; "{}" }
            )
            assertEquals(nativeResult.toString(), actual)
        }
        assertEquals(0, persistedCalls)
    }

    @Test fun malformedNativeReplyIsUnconfirmedAndNeverFallsBack() {
        var persistedCalls = 0
        val result = JSONObject(PersistentCleanFallback.execute(
            native = { JSONObject("incomplete reply") },
            persisted = { persistedCalls++; "{}" }
        ))
        assertEquals(0, persistedCalls)
        assertEquals("persistent_clean_unconfirmed", result.getString("error"))
    }
}
