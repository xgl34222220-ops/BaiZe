package io.github.xgl34222220.baize

import android.app.Application
import io.github.xgl34222220.baize.root.JsonFileTransport
import io.github.xgl34222220.baize.root.RootJsonTransportException
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CleanupAttemptTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun localAuthorizationPreparationFailureLeavesOriginalReviewUsable() {
        val attempt = CleanupAttempt()
        val error = assertThrows(RootJsonTransportException::class.java) {
            attempt.authorize { unpreparedRequest() }
        }
        assertFalse(error.requestSubmitted)
        assertFalse(attempt.snapshotTouched)
        assertFalse(attempt.cleanupSubmitted)
    }

    @Test fun localCleanupPreparationFailureLeavesOriginalReviewUsable() {
        val attempt = CleanupAttempt()
        val error = assertThrows(RootJsonTransportException::class.java) {
            attempt.mutate { unpreparedRequest() }
        }
        assertFalse(error.requestSubmitted)
        assertFalse(attempt.snapshotTouched)
        assertFalse(attempt.cleanupSubmitted)
    }

    @Test fun successfulAuthorizationRemainsTouchedWhenCleanupCannotBePrepared() {
        val attempt = CleanupAttempt()
        attempt.authorize { "{\"success\":true}" }

        assertThrows(RootJsonTransportException::class.java) {
            attempt.mutate { unpreparedRequest() }
        }

        // The failed second phase did not delete anything, but authorization already consumed
        // the server's immutable snapshot. Reusing that review would still be unsafe.
        assertTrue(attempt.snapshotTouched)
        assertFalse(attempt.cleanupSubmitted)
    }

    @Test fun lostCleanupReplyIsSubmittedAndNotRetried() {
        val attempt = CleanupAttempt()
        val directory = folder.newFolder("lost-reply")
        var exchanges = 0
        var mutations = 0

        val error = assertThrows(RootJsonTransportException::class.java) {
            attempt.mutate {
                JsonFileTransport.call(directory, JSONArray().put("snapshot")) { request ->
                    exchanges++
                    JsonFileTransport.serve(directory, request) {
                        mutations++
                        "{\"success\":true}"
                    }.close()
                    null
                }
            }
        }

        assertTrue(error.requestSubmitted)
        assertTrue(attempt.cleanupSubmitted)
        assertEquals(1, exchanges)
        assertEquals(1, mutations)
    }

    @Test fun explicitServiceRejectionDoesNotTouchFreshReview() {
        val attempt = CleanupAttempt()
        val rejected = "{\"success\":false,\"requestRejected\":true}"

        assertEquals(rejected, attempt.authorize { rejected })
        assertFalse(attempt.snapshotTouched)
        assertFalse(attempt.cleanupSubmitted)

        assertEquals(rejected, attempt.mutate { rejected })
        assertFalse(attempt.snapshotTouched)
        assertFalse(attempt.cleanupSubmitted)
    }

    @Test fun cleanupRejectionDoesNotRollBackSuccessfulAuthorization() {
        val attempt = CleanupAttempt()
        attempt.authorize { "{\"success\":true}" }

        val rejected = "{\"success\":false,\"requestRejected\":true}"
        assertEquals(rejected, attempt.mutate { rejected })

        assertTrue(attempt.snapshotTouched)
        assertFalse(attempt.cleanupSubmitted)
    }

    private fun unpreparedRequest(): String = JsonFileTransport.call(
        // A real file cannot be used as the transport directory, so dispatch cannot begin.
        folder.newFile(), JSONArray().put("snapshot")
    ) {
        throw AssertionError("A locally unprepared request must never reach the service")
    }
}
