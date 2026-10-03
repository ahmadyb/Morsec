package app.morsecode.core.storage.transfer

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * Containment evidence: how the answer was reached is part of the answer.
 *
 * The three positive levels are never collapsed, so every test here asserts
 * which one it got, not merely that something was "inside".
 *
 * The write path is resolveDestination(grant, segments) -- the destination is
 * constructed under the grant. validateExistingUri exists for reconciliation
 * and is deliberately weaker, refusing to accept an arbitrary URI on a string
 * prefix where the platform cannot ask the provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafContainmentTest {

    private val authority = "com.android.externalstorage.documents"
    private val treeUri: Uri = Uri.parse("content://$authority/tree/primary%3ADownload")
    private val rootId = "primary:Download"

    private val grant = SafTreeGrant(
        grantId = "g-1",
        treeUri = treeUri,
        rootDocumentId = rootId,
        authority = authority,
        writable = true,
    )
    private val readOnlyGrant = grant.copy(writable = false)

    private class FakeProver(
        var child: SafChildAnswer = SafChildAnswer.Answered(true),
        var path: SafPathAnswer = SafPathAnswer.Indeterminate,
    ) : SafContainmentProver {
        val childCalls = mutableListOf<Pair<Uri, Uri>>()
        val pathCalls = mutableListOf<Uri>()
        override fun isChildDocument(parentDocumentUri: Uri, childDocumentUri: Uri): SafChildAnswer {
            childCalls += parentDocumentUri to childDocumentUri
            return child
        }
        override fun documentPath(documentUri: Uri): SafPathAnswer {
            pathCalls += documentUri
            return path
        }
    }

    private class ThrowingProver(private val security: Boolean) : SafContainmentProver {
        override fun isChildDocument(parentDocumentUri: Uri, childDocumentUri: Uri): SafChildAnswer {
            if (security) throw SecurityException("grant revoked")
            throw IllegalStateException("provider crashed")
        }
        override fun documentPath(documentUri: Uri): SafPathAnswer {
            if (security) throw SecurityException("grant revoked")
            throw IllegalStateException("provider crashed")
        }
    }

    private fun documentUri(id: String): Uri =
        SafContainment.documentUriUsingTree(treeUri, id)!!

    private fun confined(evidence: SafContainmentEvidence): SafContainmentEvidence {
        assertTrue("expected a confined result, got $evidence", evidence.isConfined)
        return evidence
    }

    // -----------------------------------------------------------------------
    // Boundary collisions — segment comparison, not unbounded prefixes
    // -----------------------------------------------------------------------

    @Test
    fun `a sibling that extends the root name is not inside it`() {
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "${rootId}s"))
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "primary:Downloads"))
    }

    @Test
    fun `a sibling with a suffix on the root name is not inside it`() {
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "${rootId}Backup"))
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "primary:DownloadBackup"))
    }

    @Test
    fun `a child name is not the same as a longer child name sharing its prefix`() {
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary("$rootId/a", "$rootId/ab"))
        assertTrue(SafDocumentIdRules.isWithinOnSegmentBoundary("$rootId/a", "$rootId/a"))
        assertTrue(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "$rootId/ab"))
    }

    @Test
    fun `a target shorter than the root is not inside it`() {
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary("$rootId/2026", rootId))
    }

    @Test
    fun `the same document id under a different authority is outside`() {
        val foreign = Uri.parse("content://other.authority/tree/primary%3ADownload/document/primary%3ADownload%2F2026")
        val evidence = SafDestinationResolver.validateExistingUri(grant, foreign, FakeProver(), 34)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Outside)
    }

    @Test
    fun `a document under a different tree grant is outside when the provider denies it`() {
        val moviesTree = Uri.parse("content://$authority/tree/primary%3AMovies")
        val moviesDoc = SafContainment.documentUriUsingTree(moviesTree, "primary:Movies/2026")!!
        val evidence = SafDestinationResolver.validateExistingUri(
            grant, moviesDoc, FakeProver(child = SafChildAnswer.Answered(false)), 34,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Outside)
    }

    @Test
    fun `a document under a different tree grant is unknown on API 23, never prefix-accepted`() {
        val moviesTree = Uri.parse("content://$authority/tree/primary%3AMovies")
        val moviesDoc = SafContainment.documentUriUsingTree(moviesTree, "primary:Movies/2026")!!
        val evidence = SafDestinationResolver.validateExistingUri(grant, moviesDoc, FakeProver(), 23)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a prefix-collision sibling is refused when constructed as segments too`() {
        // Construction cannot leave the root, so this asserts the boundary rule
        // the construction relies on.
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("2026"), FakeProver(), 29,
        )
        assertEquals("$rootId/2026", (evidence as SafContainmentEvidence.ProviderConfirmedChild).targetDocumentId)
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "${rootId}s/2026"))
    }

    // -----------------------------------------------------------------------
    // API 23-25 — canonical evidence, and only for constructed paths
    // -----------------------------------------------------------------------

    @Test
    fun `an internally constructed direct child is grant-scoped canonical on API 23`() {
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("2026"), FakeProver(), 23,
        )
        val result = evidence as? SafContainmentEvidence.GrantScopedCanonical
            ?: error("got $evidence")
        assertEquals("$rootId/2026", result.targetDocumentId)
        assertTrue(result.isConfined)
        assertFalse(result.isProviderBacked)
    }

    @Test
    fun `an internally constructed nested child is grant-scoped canonical on API 25`() {
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("2026", "oct", "incoming"), FakeProver(), 25,
        )
        assertEquals(
            "$rootId/2026/oct/incoming",
            (evidence as SafContainmentEvidence.GrantScopedCanonical).targetDocumentId,
        )
    }

    @Test
    fun `the granted root itself is grant-scoped canonical when no segments are given`() {
        val evidence = SafDestinationResolver.resolveDestination(grant, emptyList(), FakeProver(), 23)
        assertEquals(rootId, (evidence as SafContainmentEvidence.GrantScopedCanonical).targetDocumentId)
    }

    @Test
    fun `an arbitrary complete uri is unknown on API 23, never accepted on a prefix`() {
        val evidence = SafDestinationResolver.validateExistingUri(
            grant, documentUri("$rootId/2026"), FakeProver(), 23,
        )
        val unknown = evidence as? SafContainmentEvidence.Unknown ?: error("got $evidence")
        assertFalse("unknown must never be confined", unknown.isConfined)
    }

    @Test
    fun `an arbitrary complete uri is unknown on API 25 too`() {
        val evidence = SafDestinationResolver.validateExistingUri(
            grant, documentUri("$rootId/2026"), FakeProver(), 25,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a grant that is not writable is refused as revoked`() {
        val evidence = SafDestinationResolver.resolveDestination(
            readOnlyGrant, listOf("2026"), FakeProver(), 23,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.PermissionRevoked)
    }

    @Test
    fun `a malformed root document id is refused on any level`() {
        val broken = grant.copy(rootDocumentId = "primary:Download/../x")
        val evidence = SafDestinationResolver.resolveDestination(
            broken, listOf("2026"), FakeProver(), 23,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Malformed)
    }

    @Test
    fun `a relative segment that is not one segment is refused`() {
        for (segment in listOf("..", ".", "a/b", "a%2Fb", "", "a\\b")) {
            val evidence = SafDestinationResolver.resolveDestination(
                grant, listOf(segment), FakeProver(), 23,
            )
            assertTrue("segment '$segment' must be malformed, got $evidence",
                evidence is SafContainmentEvidence.Malformed)
        }
    }

    @Test
    fun `a double-encoded segment is refused`() {
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("%252E%252E"), FakeProver(), 23,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Malformed)
    }

    @Test
    fun `a sibling id is not accepted by the boundary rule on API 23 paths`() {
        // The boundary rule is what construction depends on; prove the
        // collision cases against the real root rather than a toy string.
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "${rootId}s"))
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "${rootId}Backup"))
        assertFalse(SafDocumentIdRules.isWithinOnSegmentBoundary(rootId, "primary:Movies"))
    }

    // -----------------------------------------------------------------------
    // API 26-28 — provider path evidence
    // -----------------------------------------------------------------------

    @Test
    fun `a coherent provider path is provider-confirmed path on API 26`() {
        val prover = FakeProver(
            path = SafPathAnswer.Resolved(
                rootId = rootId,
                segments = listOf(rootId, "$rootId/2026"),
            ),
        )
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 26)
        val result = evidence as? SafContainmentEvidence.ProviderConfirmedPath
            ?: error("got $evidence")
        assertEquals("$rootId/2026", result.targetDocumentId)
        assertTrue(result.isProviderBacked)
    }

    @Test
    fun `a provider path under the wrong root is outside`() {
        val prover = FakeProver(
            path = SafPathAnswer.Resolved(
                rootId = "primary:Movies",
                segments = listOf("primary:Movies", "primary:Movies/2026"),
            ),
        )
        val evidence = SafDestinationResolver.validateExistingUri(
            grant, documentUri("primary:Movies/2026"), prover, 27,
        )
        // The id fails the boundary check against the approved root first.
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Outside)
    }

    @Test
    fun `a provider path that starts somewhere else is outside`() {
        val prover = FakeProver(
            path = SafPathAnswer.Resolved(
                rootId = rootId,
                segments = listOf("primary:Movies", "$rootId/2026"),
            ),
        )
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 28)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Outside)
    }

    @Test
    fun `an empty provider path is unknown`() {
        val prover = FakeProver(path = SafPathAnswer.Resolved(rootId = rootId, segments = emptyList()))
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 27)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a provider path whose root disagrees with the approved root is unknown`() {
        val prover = FakeProver(
            path = SafPathAnswer.Resolved(
                rootId = "some.other.root",
                segments = listOf(rootId, "$rootId/2026"),
            ),
        )
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 27)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a provider path that does not end at the target is unknown`() {
        val prover = FakeProver(
            path = SafPathAnswer.Resolved(
                rootId = rootId,
                segments = listOf(rootId, "$rootId/something-else"),
            ),
        )
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 27)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a provider that cannot produce a path gives unknown`() {
        val prover = FakeProver(path = SafPathAnswer.Indeterminate)
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 27)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a SecurityException during a path query is a revocation on API 27`() {
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("2026"), ThrowingProver(security = true), 27,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.PermissionRevoked)
    }

    @Test
    fun `a provider crash during a path query is unknown, not acceptance`() {
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("2026"), ThrowingProver(security = false), 27,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    // -----------------------------------------------------------------------
    // API 29+ — child-document evidence
    // -----------------------------------------------------------------------

    @Test
    fun `a provider that confirms the child yields provider-confirmed child`() {
        val prover = FakeProver(child = SafChildAnswer.Answered(true))
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 29)
        val result = evidence as? SafContainmentEvidence.ProviderConfirmedChild
            ?: error("got $evidence")
        assertTrue(result.isProviderBacked)
        assertEquals("$rootId/2026", result.targetDocumentId)
    }

    @Test
    fun `a provider that denies the child yields outside`() {
        val prover = FakeProver(child = SafChildAnswer.Answered(false))
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 34)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Outside)
    }

    @Test
    fun `an explicit provider denial is not overridden by a string prefix at any level`() {
        // The id was constructed under the root, so a string comparison would
        // accept it. The provider said no, so nothing may proceed.
        for (sdk in listOf(29, 30, 34)) {
            val evidence = SafDestinationResolver.resolveDestination(
                grant, listOf("2026"), FakeProver(child = SafChildAnswer.Answered(false)), sdk,
            )
            assertTrue("sdk $sdk: got $evidence", evidence is SafContainmentEvidence.Outside)
        }
    }

    @Test
    fun `a provider exception does not fall back to string acceptance`() {
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("2026"), ThrowingProver(security = false), 29,
        )
        val unknown = evidence as? SafContainmentEvidence.Unknown ?: error("got $evidence")
        assertFalse("a crash must never become acceptance", unknown.isConfined)
    }

    @Test
    fun `a revoked permission stays typed and never becomes outside or unknown`() {
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("2026"), ThrowingProver(security = true), 29,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.PermissionRevoked)
    }

    @Test
    fun `a provider that declines to answer stays unknown`() {
        val prover = FakeProver(child = SafChildAnswer.Indeterminate)
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 29)
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `the tier the platform offers depends on the level`() {
        assertEquals(SafContainmentTier.CANONICAL_ONLY, SafContainmentTier.forSdk(23))
        assertEquals(SafContainmentTier.CANONICAL_ONLY, SafContainmentTier.forSdk(25))
        assertEquals(SafContainmentTier.DOCUMENT_PATH, SafContainmentTier.forSdk(26))
        assertEquals(SafContainmentTier.DOCUMENT_PATH, SafContainmentTier.forSdk(28))
        // isChildDocument is API 29, not 21 -- the reason this tiering exists.
        assertEquals(SafContainmentTier.CHILD_DOCUMENT, SafContainmentTier.forSdk(29))
    }

    // -----------------------------------------------------------------------
    // Ordinary valid destinations must not be rejected for valid encoding
    // -----------------------------------------------------------------------

    @Test
    fun `ordinary Downloads DCIM and Documents trees parse`() {
        val roots = mapOf(
            "content://$authority/tree/primary%3ADownload" to "primary:Download",
            "content://$authority/tree/primary%3ADCIM" to "primary:DCIM",
            "content://$authority/tree/primary%3ADocuments" to "primary:Documents",
        )
        roots.forEach { (uri, expected) ->
            assertEquals(expected, SafContainment.treeDocumentIdOf(Uri.parse(uri)))
        }
    }

    @Test
    fun `a unicode document name is accepted`() {
        val prover = FakeProver(child = SafChildAnswer.Answered(true))
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("日本語.mp4"), prover, 29)
        assertEquals("$rootId/日本語.mp4", confined(evidence).targetDocumentId)
    }

    @Test
    fun `a literal percent character in a name is accepted`() {
        val prover = FakeProver(child = SafChildAnswer.Answered(true))
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("100%.txt"), prover, 29)
        assertEquals("$rootId/100%.txt", confined(evidence).targetDocumentId)
    }

    @Test
    fun `an encoded separator in the uri representation is accepted and canonicalised`() {
        // A document id is one percent-encoded URI segment, so a nested id
        // arrives with its separators encoded. The URI keeps the %2F; the
        // document id this layer hands back is the canonical form.
        val prover = FakeProver(child = SafChildAnswer.Answered(true))
        val evidence = SafDestinationResolver.resolveDestination(grant, listOf("2026"), prover, 29)
        val result = confined(evidence)
        assertEquals("$rootId/2026", result.targetDocumentId)
        assertTrue(
            "the uri representation should still be encoded: ${result.targetUri}",
            result.targetUri!!.contains("%2F"),
        )
        assertEquals(
            "$rootId/2026",
            SafContainment.documentIdOf(Uri.parse(result.targetUri!!)),
        )
    }

    @Test
    fun `a name with spaces and parentheses is accepted`() {
        val prover = FakeProver(child = SafChildAnswer.Answered(true))
        val evidence = SafDestinationResolver.resolveDestination(
            grant, listOf("holiday (2).mp4"), prover, 29,
        )
        assertEquals("$rootId/holiday (2).mp4", confined(evidence).targetDocumentId)
    }

    // -----------------------------------------------------------------------
    // Commit policy
    // -----------------------------------------------------------------------

    private fun childEvidence() = SafContainmentEvidence.ProviderConfirmedChild(rootId, "$rootId/x", "u")
    private fun pathEvidence() = SafContainmentEvidence.ProviderConfirmedPath(rootId, "$rootId/x", "u", listOf(rootId))
    private fun canonicalEvidence() = SafContainmentEvidence.GrantScopedCanonical(rootId, "$rootId/x", "u")
    private fun unknownEvidence() = SafContainmentEvidence.Unknown(rootId, "$rootId/x", "u", TransferStorageError.Unsupported("x"))
    private fun revokedEvidence() = SafContainmentEvidence.PermissionRevoked(rootId, "$rootId/x", "u", TransferStorageError.PermissionRevoked("write"))
    private fun outsideEvidence() = SafContainmentEvidence.Outside(rootId, "$rootId/x", "u")
    private fun malformedEvidence() = SafContainmentEvidence.Malformed(rootId, null, null, SafDocumentIdViolation.DOT_SEGMENT)

    @Test
    fun `provider-confirmed child authorises every operation`() {
        SafContainmentOperation.entries.forEach { operation ->
            assertEquals(
                "$operation",
                SafContainmentDecision.ALLOWED,
                SafContainmentPolicy.decide(childEvidence(), operation, storedIdentityMatches = true),
            )
        }
    }

    @Test
    fun `provider-confirmed path authorises every operation`() {
        SafContainmentOperation.entries.forEach { operation ->
            assertEquals(
                "$operation",
                SafContainmentDecision.ALLOWED,
                SafContainmentPolicy.decide(pathEvidence(), operation, storedIdentityMatches = true),
            )
        }
    }

    @Test
    fun `grant-scoped canonical authorises creation only`() {
        assertEquals(
            SafContainmentDecision.ALLOWED,
            SafContainmentPolicy.decide(canonicalEvidence(), SafContainmentOperation.CREATE_DESTINATION),
        )
        listOf(
            SafContainmentOperation.OPEN_WRITE,
            SafContainmentOperation.RENAME,
            SafContainmentOperation.DELETE_TEMPORARY,
            SafContainmentOperation.RECONCILE,
        ).forEach { operation ->
            assertEquals(
                "$operation must not rest on canonical evidence alone",
                SafContainmentDecision.REJECTED,
                SafContainmentPolicy.decide(canonicalEvidence(), operation),
            )
        }
    }

    @Test
    fun `outside and malformed are rejected`() {
        SafContainmentOperation.entries.forEach { operation ->
            assertEquals(SafContainmentDecision.REJECTED, SafContainmentPolicy.decide(outsideEvidence(), operation))
            assertEquals(SafContainmentDecision.REJECTED, SafContainmentPolicy.decide(malformedEvidence(), operation))
        }
    }

    @Test
    fun `unknown forbids writing renaming and deleting`() {
        listOf(
            SafContainmentOperation.CREATE_DESTINATION,
            SafContainmentOperation.OPEN_WRITE,
            SafContainmentOperation.RENAME,
            SafContainmentOperation.DELETE_TEMPORARY,
            SafContainmentOperation.RECONCILE,
        ).forEach { operation ->
            assertEquals(
                "$operation",
                SafContainmentDecision.FORBIDDEN_NO_WRITE,
                SafContainmentPolicy.decide(unknownEvidence(), operation, storedIdentityMatches = true),
            )
        }
    }

    @Test
    fun `a revoked grant authorises nothing`() {
        SafContainmentOperation.entries.forEach { operation ->
            assertEquals(
                "$operation",
                SafContainmentDecision.FORBIDDEN_NO_WRITE,
                SafContainmentPolicy.decide(revokedEvidence(), operation, storedIdentityMatches = true),
            )
        }
    }

    @Test
    fun `deletion additionally requires the stored temporary identity to match`() {
        assertEquals(
            SafContainmentDecision.REJECTED,
            SafContainmentPolicy.decide(
                childEvidence(), SafContainmentOperation.DELETE_TEMPORARY, storedIdentityMatches = false,
            ),
        )
        assertEquals(
            SafContainmentDecision.ALLOWED,
            SafContainmentPolicy.decide(
                childEvidence(), SafContainmentOperation.DELETE_TEMPORARY, storedIdentityMatches = true,
            ),
        )
        // Containment alone is never enough to delete, even at the strongest level.
        assertEquals(
            SafContainmentDecision.REJECTED,
            SafContainmentPolicy.decide(pathEvidence(), SafContainmentOperation.DELETE_TEMPORARY),
        )
    }

    // -----------------------------------------------------------------------
    // API 26-28: provider document ids may be opaque
    // -----------------------------------------------------------------------

    private fun pathProver(rootId: String?, segments: List<String>) = FakeProver(
        path = SafPathAnswer.Resolved(rootId = rootId, segments = segments),
    )

    @Test
    fun `opaque non-prefix ids are accepted when findDocumentPath proves ancestry`() {
        // No entry is a textual prefix or descendant of the one before it.
        // Ancestry comes from the provider having listed the path, not from
        // comparing the strings.
        val evidence = SafDestinationResolver.validateExistingUri(
            grant,
            documentUri("target-opaque-b913"),
            pathProver("root-id", listOf("root-id", "child-opaque-74a2", "target-opaque-b913")),
            27,
        )
        val result = evidence as? SafContainmentEvidence.ProviderConfirmedPath
            ?: error("got $evidence")
        assertEquals("target-opaque-b913", result.targetDocumentId)
        assertEquals(listOf("root-id", "child-opaque-74a2", "target-opaque-b913"), result.segments)
    }

    @Test
    fun `a path whose root is the approved root but whose target differs is unknown`() {
        val evidence = SafDestinationResolver.validateExistingUri(
            grant,
            documentUri("target-opaque-b913"),
            pathProver("root-id", listOf("root-id", "child-opaque-74a2", "some-other-opaque")),
            27,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a path whose root is not the approved root is unknown`() {
        val evidence = SafDestinationResolver.validateExistingUri(
            grant,
            documentUri("target-opaque-b913"),
            pathProver("other-root-id", listOf("root-id", "target-opaque-b913")),
            27,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a path starting somewhere other than the approved root is outside`() {
        val evidence = SafDestinationResolver.validateExistingUri(
            grant,
            documentUri("target-opaque-b913"),
            pathProver("root-id", listOf("a-totally-different-root", "target-opaque-b913")),
            27,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Outside)
    }

    @Test
    fun `an empty opaque provider path is unknown`() {
        val evidence = SafDestinationResolver.validateExistingUri(
            grant, documentUri("target-opaque-b913"), pathProver("root-id", emptyList()), 27,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `duplicate opaque entries make a path incoherent`() {
        val evidence = SafDestinationResolver.validateExistingUri(
            grant,
            documentUri("child-opaque-74a2"),
            pathProver("root-id", listOf("root-id", "child-opaque-74a2", "child-opaque-74a2")),
            27,
        )
        assertTrue("got $evidence", evidence is SafContainmentEvidence.Unknown)
    }

    @Test
    fun `a malformed or null-shaped entry is unknown`() {
        for (bad in listOf("", "..", ".", "a\\b", "/abs")) {
            val evidence = SafDestinationResolver.validateExistingUri(
                grant,
                documentUri("target-opaque-b913"),
                pathProver("root-id", listOf("root-id", bad, "target-opaque-b913")),
                27,
            )
            assertTrue("entry '$bad' must be unknown, got $evidence",
                evidence is SafContainmentEvidence.Unknown)
        }
    }
}
