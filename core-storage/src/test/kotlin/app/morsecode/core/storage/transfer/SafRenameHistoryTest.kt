package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafRenameHistoryTest {

    private val scope = SafRenameScope(
        grantId = "grant-1",
        treeUri = "content://provider/tree/root",
        authority = "provider",
        rootDocumentId = "root",
        parentDocumentId = "root",
        sessionId = SessionId("session-1"),
        transferId = TransferId("transfer-1"),
        commitId = PartialIdentity("commit-1"),
    )

    private fun documentUri(documentId: String): String =
        requireNotNull(SafContainment.documentUriUsingTree(Uri.parse(scope.treeUri), documentId)).toString()

    private fun identity(id: String) = SafStoredDocumentIdentity(
        documentUri = documentUri(id),
        documentId = id,
    )

    private fun evidence(
        before: SafStoredDocumentIdentity = identity("before"),
        returned: SafStoredDocumentIdentity? = identity("returned"),
        phase: SafRenamePhase = SafRenamePhase.FINAL_PROMOTION,
        sequence: Int = 0,
        scope: SafRenameScope = this.scope,
        reconciliation: SafRenameReconciliation? = if (returned == null) {
            SafRenameReconciliation.NULL_RETURN
        } else {
            SafRenameReconciliation.RESOLVED_TO_RETURNED
        },
    ) = SafRenameEvidence(
        before = before,
        returned = returned,
        reconciliation = reconciliation,
        scope = scope,
        phase = phase,
        sequence = sequence,
    )

    @Test
    fun `well formed history carries scope and ordered backup then final evidence`() {
        val history = listOf(
            evidence(
                before = identity("existing"),
                returned = identity("backup"),
                phase = SafRenamePhase.BACKUP_RENAME,
                sequence = 0,
            ),
            evidence(
                before = identity("replacement"),
                returned = identity("final"),
                phase = SafRenamePhase.FINAL_PROMOTION,
                sequence = 1,
            ),
        )

        assertTrue(SafRenameHistoryPolicy.isWellFormed(history, scope))
    }

    @Test
    fun `history refuses mismatched grant tree authority parent session transfer and commit scope`() {
        val mismatches = listOf(
            scope.copy(grantId = "other-grant"),
            scope.copy(treeUri = "content://provider/tree/other", rootDocumentId = "other"),
            scope.copy(authority = "other-provider"),
            scope.copy(rootDocumentId = "other"),
            scope.copy(parentDocumentId = "other-parent"),
            scope.copy(sessionId = SessionId("other-session")),
            scope.copy(transferId = TransferId("other-transfer")),
            scope.copy(commitId = PartialIdentity("other-commit")),
        )

        mismatches.forEach { mismatch ->
            assertFalse(
                "scope mismatch must fail: ${mismatch.toString()}",
                SafRenameHistoryPolicy.isWellFormed(listOf(evidence(scope = mismatch)), scope),
            )
        }
    }

    @Test
    fun `history refuses malformed source and returned uri id pairs`() {
        val malformedSource = SafStoredDocumentIdentity(
            documentUri("source-id"),
            "different-source-id",
        )
        val malformedReturned = SafStoredDocumentIdentity(
            documentUri("returned-id"),
            "different-returned-id",
        )

        assertFalse(SafRenameHistoryPolicy.isWellFormed(listOf(evidence(before = malformedSource)), scope))
        assertFalse(SafRenameHistoryPolicy.isWellFormed(listOf(evidence(returned = malformedReturned)), scope))
        assertFalse(
            SafRenameHistoryPolicy.isWellFormed(
                listOf(evidence(before = identity("bad%2Fid"), returned = identity("safe"))),
                scope,
            ),
        )
        assertFalse(
            SafRenameHistoryPolicy.isWellFormed(
                listOf(
                    evidence(
                        before = identity("before"),
                        returned = SafStoredDocumentIdentity("content://other/document/id", "id"),
                    ),
                ),
                scope,
            ),
        )
        assertFalse(
            SafRenameHistoryPolicy.isWellFormed(
                listOf(
                    evidence(
                        before = SafStoredDocumentIdentity("content://provider/document/%ZZ", "%ZZ"),
                    ),
                ),
                scope,
            ),
        )
    }

    @Test
    fun `history refuses identities under a different tree root`() {
        val otherTreeUri = Uri.parse("content://provider/tree/other-root")
        val outsideTree = SafStoredDocumentIdentity(
            documentUri = requireNotNull(SafContainment.documentUriUsingTree(otherTreeUri, "before")).toString(),
            documentId = "before",
        )

        assertFalse(SafRenameHistoryPolicy.isWellFormed(listOf(evidence(before = outsideTree)), scope))
    }

    @Test
    fun `history refuses duplicate entries invalid sequence phase order and cycles`() {
        val backup = identity("backup")
        val final = identity("final")
        val first = evidence(
            before = identity("old"),
            returned = backup,
            phase = SafRenamePhase.BACKUP_RENAME,
            sequence = 0,
        )
        val second = evidence(
            before = identity("replacement"),
            returned = final,
            phase = SafRenamePhase.FINAL_PROMOTION,
            sequence = 1,
        )

        assertFalse(SafRenameHistoryPolicy.isWellFormed(listOf(first, first.copy(sequence = 1)), scope))
        assertFalse(SafRenameHistoryPolicy.isWellFormed(listOf(first, second.copy(sequence = 3)), scope))
        assertFalse(SafRenameHistoryPolicy.isWellFormed(listOf(second, first.copy(sequence = 1)), scope))
        assertFalse(
            SafRenameHistoryPolicy.isWellFormed(
                listOf(
                    first,
                    evidence(
                        before = backup,
                        returned = identity("old"),
                        phase = SafRenamePhase.FINAL_PROMOTION,
                        sequence = 1,
                    ),
                ),
                scope,
            ),
        )
    }

    @Test
    fun `history length has a fixed small bound`() {
        val tooMany = (0..SafRenameHistoryPolicy.MAX_ENTRIES).map { index ->
            evidence(
                before = identity("before-$index"),
                returned = identity("returned-$index"),
                phase = if (index == 0) SafRenamePhase.BACKUP_RENAME else SafRenamePhase.FINAL_PROMOTION,
                sequence = index,
            )
        }

        assertFalse(SafRenameHistoryPolicy.isWellFormed(tooMany, scope))
        assertTrue(SafRenameHistoryPolicy.MAX_ENTRIES == 2)
    }
}
