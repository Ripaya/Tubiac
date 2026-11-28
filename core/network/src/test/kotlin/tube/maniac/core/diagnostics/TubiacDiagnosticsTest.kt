package tube.maniac.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TubiacDiagnosticsTest {

    @Test
    fun `redactSensitive strips bearer tokens cookies and github PATs`() {
        val raw = """
            Authorization: Bearer secret_jwt_token_12345
            Cookie: cf_clearance=abc123; session=xyz
            url=https://example.org/cb#access_token=anilist_token_999&token_type=Bearer
            token=ghp_SuperSecretTokenValue123456
        """.trimIndent()

        val cleaned = TubiacLogcat.redactSensitive(raw)
        assertFalse(cleaned.contains("secret_jwt_token_12345"))
        assertFalse(cleaned.contains("cf_clearance=abc123"))
        assertFalse(cleaned.contains("anilist_token_999"))
        assertFalse(cleaned.contains("SuperSecretTokenValue123456"))
        assertTrue(cleaned.contains("[REDACTED]"))
    }

    @Test
    fun `AutoIssueReporter respects opt-out toggle and deduplicates fingerprints`() {
        val tempDir = Files.createTempDirectory("tubiac-diag-test").toFile()
        AutoIssueReporter.initializeForDirectory(
            filesDir = tempDir,
            packageName = "tube.maniac.test",
            versionName = "0.1.5-alpha",
            versionCode = 6L,
        )
        AutoIssueReporter.clearPendingAndHistory()

        // Opt-out prevents queueing crash/bug reports
        AutoIssueReporter.updatePreferences(enabled = false)
        AutoIssueReporter.reportRuntimeBug(
            category = "test",
            title = "Disabled bug",
            detail = "Should not be queued",
            throwable = IllegalStateException("boom"),
        )
        assertEquals(0, AutoIssueReporter.state.value.pendingCount)

        // Opt-in queues once and deduplicates identical stacktraces
        AutoIssueReporter.updatePreferences(enabled = true)
        val err = IllegalStateException("duplicate crash")
        AutoIssueReporter.reportRuntimeBug("test", "Dup bug", "First hit", err)
        AutoIssueReporter.reportRuntimeBug("test", "Dup bug", "Second hit", err)
        assertEquals(1, AutoIssueReporter.state.value.pendingCount)

        tempDir.deleteRecursively()
    }
}
