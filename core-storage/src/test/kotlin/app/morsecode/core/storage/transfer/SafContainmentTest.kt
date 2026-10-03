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
 * Layers A and C of containment: parse with the platform, then ask the provider.
 *
 * Robolectric rather than pure JVM, because layer A is defined as "use the
 * Android APIs" -- DocumentsContract.getTreeDocumentId and friends are the
 * subject of the test, not an implementation detail to be stubbed away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafContainmentTest {

    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload")

    private val rootId = "primary:Download"

    private class FakeProver(
        private val answer: SafChildProof =
            SafChildProof.Answered(true, SafContainmentMethod.PROVIDER_CHILD_DOCUMENT),
        private val throwSecurity: Boolean = false,
    ) : SafChildProver {
        val calls = mutableListOf<Pair<Uri, Uri>>()
        override fun prove(parentDocumentUri: Uri, childDocumentUri: Uri): SafChildProof {
            calls += parentDocumentUri to childDocumentUri
            if (throwSecurity) throw SecurityException("grant revoked")
            return answer
        }
    }

    private val answeringProver = FakeProver()
    private val refusingProver = FakeProver(
        SafChildProof.Answered(false, SafContainmentMethod.PROVIDER_CHILD_DOCUMENT),
    )
    private val silentProver = FakeProver(SafChildProof.Indeterminate)
    private val revokedProver = FakeProver(throwSecurity = true)

    private fun inside(proof: SafContainmentProof): SafContainmentProof.Inside =
        proof as? SafContainmentProof.Inside ?: error("expected Inside, got $proof")

    private fun malformed(proof: SafContainmentProof): SafContainmentProof.Malformed =
        proof as? SafContainmentProof.Malformed ?: error("expected Malformed, got $proof")

    // -----------------------------------------------------------------------
    // Layer A — the platform parses, not a regex
    // -----------------------------------------------------------------------

    @Test
    fun `the platform parses the tree document id out of a standard tree uri`() {
        // The %3A is a standard encoded colon, not an escape attempt.
        assertEquals(rootId, SafContainment.treeDocumentIdOf(treeUri))
    }

    @Test
    fun `the platform parses a document id out of a document uri`() {
        val documentUri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/" +
                "primary%3ADownload/document/primary%3ADownload%2F2026",
        )
        assertEquals("$rootId/2026", SafContainment.documentIdOf(documentUri))
    }

    @Test
    fun `a document uri built for a nested id round-trips through the platform`() {
        val built = SafContainment.documentUriUsingTree(treeUri, "$rootId/2026/oct")
        assertEquals("$rootId/2026/oct", SafContainment.documentIdOf(built!!))
    }

    @Test
    fun `a tree uri with no root segment yields no root document id`() {
        val broken = Uri.parse("content://com.android.externalstorage.documents/tree")
        assertEquals(null, SafContainment.treeDocumentIdOf(broken))
    }

    // -----------------------------------------------------------------------
    // Ordinary valid destinations, each of which must keep working
    // -----------------------------------------------------------------------

    @Test
    fun `the granted root itself is inside by identity, with no provider call`() {
        val proof = SafContainment.prove(treeUri, null, answeringProver)
        val result = inside(proof)
        assertEquals(rootId, result.targetDocumentId)
        assertEquals(SafContainmentMethod.IDENTITY, result.method)
        assertTrue(answeringProver.calls.isEmpty())
    }

    @Test
    fun `a direct child is inside`() {
        assertEquals(
            "$rootId/2026",
            inside(SafContainment.prove(treeUri, "$rootId/2026", answeringProver)).targetDocumentId,
        )
    }

    @Test
    fun `a nested descendant is inside`() {
        assertEquals(
            "$rootId/2026/oct/incoming",
            inside(
                SafContainment.prove(treeUri, "$rootId/2026/oct/incoming", answeringProver),
            ).targetDocumentId,
        )
    }

    @Test
    fun `a standard encoded slash in the id is an ordinary nested path`() {
        // primary:Download%2F2026 is how a nested id arrives inside one URI
        // segment. It names the same place as primary:Download/2026.
        val proof = SafContainment.prove(treeUri, "primary:Download%2F2026", answeringProver)
        assertEquals("$rootId/2026", inside(proof).targetDocumentId)
    }

    @Test
    fun `a literal percent character in a document name is accepted`() {
        val proof = SafContainment.prove(treeUri, "$rootId/100%.txt", answeringProver)
        assertEquals("$rootId/100%.txt", inside(proof).targetDocumentId)
    }

    @Test
    fun `a unicode document name is accepted`() {
        val proof = SafContainment.prove(treeUri, "$rootId/日本語.mp4", answeringProver)
        assertEquals("$rootId/日本語.mp4", inside(proof).targetDocumentId)
    }

    @Test
    fun `a missing document id means the granted root`() {
        val proof = SafContainment.prove(treeUri, "", answeringProver)
        assertEquals(SafContainmentMethod.IDENTITY, inside(proof).method)
    }

    // -----------------------------------------------------------------------
    // Attacks — every one must be refused before any provider call
    // -----------------------------------------------------------------------

    @Test
    fun `a literal dot-dot is malformed`() {
        assertEquals(
            SafDocumentIdViolation.DOT_SEGMENT,
            malformed(SafContainment.prove(treeUri, "$rootId/../x", answeringProver)).violation,
        )
    }

    @Test
    fun `a lower-case encoded dot-dot is malformed`() {
        assertEquals(
            SafDocumentIdViolation.ENCODED_TRAVERSAL,
            malformed(SafContainment.prove(treeUri, "$rootId/%2e%2e", answeringProver)).violation,
        )
    }

    @Test
    fun `a mixed-case encoded dot-dot is malformed`() {
        assertEquals(
            SafDocumentIdViolation.ENCODED_TRAVERSAL,
            malformed(SafContainment.prove(treeUri, "$rootId/%2E%2e", answeringProver)).violation,
        )
    }

    @Test
    fun `an encoded slash wrapping a dot-dot is malformed`() {
        assertEquals(
            SafDocumentIdViolation.ENCODED_TRAVERSAL,
            malformed(
                SafContainment.prove(treeUri, "primary:Download%2F..%2F..%2Fetc", answeringProver),
            ).violation,
        )
    }

    @Test
    fun `an encoded backslash is malformed`() {
        assertEquals(
            SafDocumentIdViolation.ENCODED_TRAVERSAL,
            malformed(SafContainment.prove(treeUri, "$rootId/..%5C..", answeringProver)).violation,
        )
    }

    @Test
    fun `a double-encoded traversal is malformed`() {
        assertEquals(
            SafDocumentIdViolation.DOUBLE_ENCODED_TRAVERSAL,
            malformed(SafContainment.prove(treeUri, "$rootId/%252E%252E", answeringProver)).violation,
        )
    }

    @Test
    fun `a target from a different tree is outside`() {
        val proof = SafContainment.prove(treeUri, "primary:Movies/2026", answeringProver)
        assertTrue(proof is SafContainmentProof.Outside)
    }

    @Test
    fun `a sibling sharing the root prefix is outside`() {
        // Prefix agreement is not containment.
        val proof = SafContainment.prove(treeUri, "${rootId}X/2026", answeringProver)
        assertTrue(proof is SafContainmentProof.Outside)
    }

    @Test
    fun `a malformed document uri is refused`() {
        val proof = SafContainment.prove(treeUri, "content://evil.example/tree/primary:Movies", answeringProver)
        assertTrue(proof is SafContainmentProof.Malformed)
    }

    @Test
    fun `a missing tree id is refused`() {
        val broken = Uri.parse("content://com.android.externalstorage.documents/tree")
        val proof = SafContainment.prove(broken, "$rootId/2026", answeringProver)
        assertTrue(proof is SafContainmentProof.Malformed)
    }

    @Test
    fun `no attack ever reaches the provider`() {
        val attacks = listOf(
            "$rootId/../x",
            "$rootId/%2e%2e",
            "$rootId/%2E%2e",
            "primary:Download%2F..%2F..%2Fetc",
            "$rootId/..%5C..",
            "$rootId/%252E%252E",
            "primary:Movies/2026",
            "${rootId}X/2026",
        )
        attacks.forEach { attack ->
            SafContainment.prove(treeUri, attack, answeringProver)
        }
        assertTrue(
            "a refused target must not be queried",
            answeringProver.calls.isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // Layer C — the provider has the last word
    // -----------------------------------------------------------------------

    @Test
    fun `a provider that proves the relationship yields provider-backed containment`() {
        val proof = inside(SafContainment.prove(treeUri, "$rootId/2026", answeringProver))
        assertEquals(SafContainmentMethod.PROVIDER_CHILD_DOCUMENT, proof.method)
        assertTrue(proof.method.isProviderBacked)
    }

    @Test
    fun `a provider that denies the relationship overrides prefix agreement`() {
        // The id names something under the root, but the provider says it is
        // not a descendant. The provider is the final authority.
        val proof = SafContainment.prove(treeUri, "$rootId/2026", refusingProver)
        assertTrue(proof is SafContainmentProof.Outside)
    }

    @Test
    fun `a provider that cannot say yields containment unknown, never acceptance`() {
        val proof = SafContainment.prove(treeUri, "$rootId/2026", silentProver)
        val unknown = proof as? SafContainmentProof.ContainmentUnknown
            ?: error("expected ContainmentUnknown, got $proof")
        assertEquals(rootId, unknown.rootDocumentId)
        // The crucial property: not inside, and not a silent pass.
        assertFalse(SafContainmentProof.isInside(unknown))
    }

    @Test
    fun `a provider that fails to answer yields containment unknown`() {
        val failing = FakeProver(
            SafChildProof.Failed(TransferStorageError.ProviderFailure("document_provider")),
        )
        assertTrue(
            SafContainment.prove(treeUri, "$rootId/2026", failing)
                is SafContainmentProof.ContainmentUnknown,
        )
    }

    @Test
    fun `a SecurityException during the containment query is a revocation, not a failure`() {
        // A revoked grant is not "not a child" and not "the provider broke".
        val proof = SafContainment.prove(treeUri, "$rootId/2026", revokedProver)
        val revoked = proof as? SafContainmentProof.Revoked ?: error("expected Revoked, got $proof")
        assertTrue(revoked.error is TransferStorageError.PermissionRevoked)
    }

    @Test
    fun `with no prover available containment is recorded as prefix-only, not provider-backed`() {
        // This is the API 23-25 path: isChildDocument is API 29 and
        // findDocumentPath is API 26, so below 26 the platform cannot ask.
        val proof = inside(SafContainment.prove(treeUri, "$rootId/2026", prover = null))
        assertEquals(SafContainmentMethod.CANONICAL_PREFIX, proof.method)
        assertFalse(
            "a prefix match must not be dressed up as provider proof",
            proof.method.isProviderBacked,
        )
    }

    @Test
    fun `an unaddressable target is containment unknown rather than a crash`() {
        val oddTree = Uri.parse("content://odd/tree/root%00id")
        val proof = SafContainment.prove(oddTree, "root id/child", answeringProver)
        assertTrue(
            "a NUL-bearing root is not a grant we can reason about: $proof",
            proof is SafContainmentProof.Malformed ||
                proof is SafContainmentProof.ContainmentUnknown,
        )
    }

    // -----------------------------------------------------------------------
    // Which proof the platform can offer at each level
    // -----------------------------------------------------------------------

    @Test
    fun `the strongest available proof depends on the platform level`() {
        assertEquals(
            SafContainmentMethod.CANONICAL_PREFIX,
            SafContainmentMethod.availableOn(23),
        )
        assertEquals(
            SafContainmentMethod.CANONICAL_PREFIX,
            SafContainmentMethod.availableOn(25),
        )
        assertEquals(
            SafContainmentMethod.PROVIDER_DOCUMENT_PATH,
            SafContainmentMethod.availableOn(26),
        )
        assertEquals(
            SafContainmentMethod.PROVIDER_DOCUMENT_PATH,
            SafContainmentMethod.availableOn(28),
        )
        // isChildDocument is API 29, not 21 -- the reason this tiering exists.
        assertEquals(
            SafContainmentMethod.PROVIDER_CHILD_DOCUMENT,
            SafContainmentMethod.availableOn(29),
        )
    }

    @Test
    fun `only provider answers count as provider-backed`() {
        assertTrue(SafContainmentMethod.PROVIDER_CHILD_DOCUMENT.isProviderBacked)
        assertTrue(SafContainmentMethod.PROVIDER_DOCUMENT_PATH.isProviderBacked)
        assertFalse(SafContainmentMethod.CANONICAL_PREFIX.isProviderBacked)
        assertFalse(SafContainmentMethod.IDENTITY.isProviderBacked)
    }
}
