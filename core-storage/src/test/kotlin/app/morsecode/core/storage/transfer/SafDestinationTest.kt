package app.morsecode.core.storage.transfer

import app.morsecode.core.model.DuplicatePolicy
import app.morsecode.core.model.SafGrant
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Containment, capability detection, commit identity and commit states for the
 * SAF destination.
 *
 * Ordinary JVM tests, deliberately. The containment rules are the part that must
 * not vary between providers, so they are checked without a ContentResolver in
 * the picture at all.
 */

class SafDestinationTest {

    private val treeUri =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    private val rootDocumentId = "primary:Download"
    private val grantedAlways = SafGrantProbe { true }
    private val grantedNever = SafGrantProbe { false }
    private val readWriteGrant = SafGrant(
        id = 1L,
        treeUri = treeUri,
        displayName = "Download",
        readWrite = true,
    )
    private val readOnlyGrant = readWriteGrant.copy(readWrite = false)

    private fun resolved(
        target: String? = null,
        grants: List<SafGrant> = listOf(readWriteGrant),
        probe: SafGrantProbe = grantedAlways,
    ): SafTargetResolution.Resolved {
        val result = SafDestinationTree.resolve(
            grants = grants,
            grantProbe = probe,
            treeUri = treeUri,
            targetDocumentId = target,
        )
        return result as? SafTargetResolution.Resolved
            ?: error("expected resolution, got $result")
    }

    private fun rejected(
        target: String? = null,
        grants: List<SafGrant> = listOf(readWriteGrant),
        probe: SafGrantProbe = grantedAlways,
        tree: String = treeUri,
    ): TransferStorageError {
        val result = SafDestinationTree.resolve(
            grants = grants,
            grantProbe = probe,
            treeUri = tree,
            targetDocumentId = target,
        )
        return (result as? SafTargetResolution.Rejected)?.error
            ?: error("expected rejection, got $result")
    }

    // -----------------------------------------------------------------------
    // Containment — the granted tree is the ceiling
    // -----------------------------------------------------------------------

    @Test
    fun `a blank target resolves to the granted root itself`() {
        val result = resolved(target = null)
        assertEquals(rootDocumentId, result.rootDocumentId)
        assertEquals(rootDocumentId, result.parentDocumentId)
        assertEquals(treeUri, result.parentUri)
    }

    @Test
    fun `a descendant of the granted root resolves inside it`() {
        val result = resolved(target = "$rootDocumentId/2026")
        assertEquals(rootDocumentId, result.rootDocumentId)
        assertEquals("$rootDocumentId/2026", result.parentDocumentId)
        // The parent uri is the document uri, not the bare tree uri.
        assertTrue(result.parentUri.contains("/document/"))
        assertTrue(result.parentUri.startsWith("content://"))
    }

    @Test
    fun `a deeply nested descendant still resolves inside it`() {
        val result = resolved(target = "$rootDocumentId/2026/oct/incoming")
        assertEquals("$rootDocumentId/2026/oct/incoming", result.parentDocumentId)
    }

    @Test
    fun `a tree that is not in the persisted grants is rejected`() {
        val error = rejected(grants = emptyList())
        assertTrue(error is TransferStorageError.Unsupported)
        assertEquals("saf_outside_tree", (error as TransferStorageError.Unsupported).capability)
    }

    @Test
    fun `a different persisted grant does not authorise this tree`() {
        val otherGrant = SafGrant(
            id = 2L,
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMovies",
            displayName = "Movies",
        )
        val error = rejected(grants = listOf(otherGrant))
        assertTrue(error is TransferStorageError.Unsupported)
    }

    @Test
    fun `a grant row that was never read-write is rejected as revoked`() {
        val error = rejected(grants = listOf(readOnlyGrant))
        assertTrue(error is TransferStorageError.PermissionRevoked)
        assertEquals("write", (error as TransferStorageError.PermissionRevoked).grant)
    }

    @Test
    fun `a live grant that has since been revoked is rejected`() {
        // The row says read-write, so only the probe can catch this: a stale row
        // without a live grant is a revocation, not a destination.
        val error = rejected(grants = listOf(readWriteGrant), probe = grantedNever)
        assertTrue(error is TransferStorageError.PermissionRevoked)
    }

    @Test
    fun `a sibling of the granted root is rejected as outside the tree`() {
        val error = rejected(target = "primary:Documents")
        assertTrue(error is TransferStorageError.Unsupported)
        assertEquals("saf_outside_tree", (error as TransferStorageError.Unsupported).capability)
    }

    @Test
    fun `a tree uri with no root segment cannot be resolved at all`() {
        // The grant has to match, or the rejection comes from the grant lookup
        // and never reaches the parse this test is about.
        val brokenTree = "content://com.android.externalstorage.documents/tree"
        val error = rejected(
            tree = brokenTree,
            grants = listOf(readWriteGrant.copy(treeUri = brokenTree)),
        )
        assertTrue(error is TransferStorageError.Unsupported)
        assertEquals("saf_document_uri", (error as TransferStorageError.Unsupported).capability)
    }

    // -----------------------------------------------------------------------
    // Traversal — the reason a content uri is never trusted on its own
    // -----------------------------------------------------------------------

    @Test
    fun `a parent traversal in the document id is rejected`() {
        val error = rejected(target = "$rootDocumentId/../primary:Movies")
        assertTrue(error is TransferStorageError.Unsupported)
        assertEquals("saf_document_uri", (error as TransferStorageError.Unsupported).capability)
    }

    @Test
    fun `a bare traversal back to the volume root is rejected`() {
        val error = rejected(target = "..")
        assertTrue(error is TransferStorageError.Unsupported)
    }

    @Test
    fun `an encoded traversal is rejected even though no segment equals dot-dot`() {
        // SafPaths compares segments against the literal "..", so
        // "primary:Download/..%2F..%2Fetc" has no segment that matches and would
        // otherwise be classified as safely inside.
        val error = rejected(target = "$rootDocumentId/..%2F..%2Fetc")
        assertTrue(error is TransferStorageError.Unsupported)
        assertEquals("saf_document_uri", (error as TransferStorageError.Unsupported).capability)
        assertNotNull(error.diagnostic)
    }

    @Test
    fun `an encoded dot-dot without an encoded slash is still refused`() {
        val error = rejected(target = "$rootDocumentId/%2E%2E")
        assertTrue(error is TransferStorageError.Unsupported)
    }

    @Test
    fun `an escaped descendant that still starts with the root prefix is refused`() {
        // "primary:DownloadX" starts with neither "primary:Download" nor
        // "primary:Download/", so prefix matching on its own would be wrong.
        val error = rejected(target = "${rootDocumentId}X/secrets")
        assertTrue(error is TransferStorageError.Unsupported)
        assertEquals("saf_outside_tree", (error as TransferStorageError.Unsupported).capability)
    }

    @Test
    fun `a full document uri is decoded before it is contained`() {
        val uri = "content://com.android.externalstorage.documents/tree/" +
            "primary%3ADownload/document/primary%3ADownload%2F..%2F..%2Fetc"
        val error = rejected(target = uri)
        assertTrue(error is TransferStorageError.Unsupported)
    }

    @Test
    fun `a recorded descendant id is still accepted when re-checked`() {
        assertTrue(SafDestinationTree.isInsideGrantedTree(treeUri, "$rootDocumentId/2026"))
    }

    @Test
    fun `the recorded root id is still accepted when re-checked`() {
        assertTrue(SafDestinationTree.isInsideGrantedTree(treeUri, rootDocumentId))
    }

    @Test
    fun `a blank recorded id means the root and is accepted`() {
        assertTrue(SafDestinationTree.isInsideGrantedTree(treeUri, null))
    }

    @Test
    fun `a recorded id naming another tree is refused when re-checked`() {
        assertFalse(SafDestinationTree.isInsideGrantedTree(treeUri, "primary:Movies"))
    }

    @Test
    fun `a recorded id carrying a parent traversal is refused`() {
        assertFalse(SafDestinationTree.isInsideGrantedTree(treeUri, "$rootDocumentId/../x"))
    }

    @Test
    fun `a recorded id carrying an encoded traversal is refused`() {
        assertFalse(SafDestinationTree.isInsideGrantedTree(treeUri, "$rootDocumentId/%2E%2E"))
    }

    // -----------------------------------------------------------------------
    // Capability detection
    // -----------------------------------------------------------------------

    @Test
    fun `a fully flagged provider reports every capability it declares`() {
        val flags = SafDestinationTestFlags.parent or SafDestinationTestFlags.document
        val caps = SafProviderCapabilities.fromFlags(
            parentFlags = flags,
            documentFlags = flags,
        )
        assertTrue(caps.flagsPresent)
        assertTrue(caps.canCreateDocument)
        assertTrue(caps.canWriteDocument)
        assertTrue(caps.canDeleteDocument)
        assertTrue(caps.canRenameDocument)
        assertTrue(caps.canMoveDocument)
        assertTrue(caps.canQuery)
    }

    @Test
    fun `omitted flags mean did-not-say, not cannot`() {
        val caps = SafProviderCapabilities.fromFlags(parentFlags = null, documentFlags = null)
        assertFalse(caps.flagsPresent)
        assertFalse(caps.canCreateDocument)
        assertFalse(caps.canRenameDocument)
        // The distinction is recorded, which is what stops "did not say" from
        // being read as a definite "no".
        assertEquals(SafProviderCapabilities.UNKNOWN, caps)
    }

    @Test
    fun `partial flags still count as present`() {
        val caps = SafProviderCapabilities.fromFlags(
            parentFlags = SafDestinationTestFlags.parent,
            documentFlags = null,
        )
        assertTrue(caps.flagsPresent)
        assertTrue(caps.canCreateDocument)
        assertFalse(caps.canRenameDocument)
    }

    @Test
    fun `a provider that cannot rename falls back to the visible final copy`() {
        val caps = SafProviderCapabilities.fromFlags(
            parentFlags = SafDestinationTestFlags.parent,
            documentFlags = SafDestinationTestFlags.document and
                SafDestinationTestFlags.rename.inv(),
        )
        assertFalse(caps.canRenameDocument)
        assertFalse(caps.supportsTempThenRename)
        assertTrue(caps.supportsVisibleFinalCopy)
        assertEquals(SafCommitStrategy.VISIBLE_FINAL_COPY, caps.preferredStrategy())
    }

    @Test
    fun `a provider that can do neither offers no strategy at all`() {
        val caps = SafProviderCapabilities.fromFlags(
            parentFlags = 0,
            documentFlags = 0,
        )
        assertNull(caps.preferredStrategy())
    }

    @Test
    fun `a provider that cannot create offers no strategy even if it can rename`() {
        val caps = SafProviderCapabilities.fromFlags(
            parentFlags = 0,
            documentFlags = SafDestinationTestFlags.document,
        )
        assertFalse(caps.canCreateDocument)
        assertTrue(caps.canRenameDocument)
        assertNull(caps.preferredStrategy())
    }

    @Test
    fun `the temporary-then-rename strategy is preferred when it is available`() {
        val caps = SafProviderCapabilities.fromFlags(
            parentFlags = SafDestinationTestFlags.parent,
            documentFlags = SafDestinationTestFlags.document,
        )
        assertEquals(SafCommitStrategy.TEMP_THEN_RENAME, caps.preferredStrategy())
    }

    @Test
    fun `provider durability is never known, whatever the flags say`() {
        // A provider flush is not fsync. Flags cannot change that, so the field
        // is false even for a provider that declares everything.
        val caps = SafProviderCapabilities.fromFlags(
            parentFlags = SafDestinationTestFlags.parent,
            documentFlags = SafDestinationTestFlags.document,
        )
        assertFalse(caps.durabilityKnown)
    }

    @Test
    fun `seekable writes and truncate stay false until they are measured`() {
        // Both are properties of a descriptor the provider hands back, not of a
        // flag it declares, so declaration alone never claims them.
        val caps = SafProviderCapabilities.fromFlags(
            parentFlags = SafDestinationTestFlags.parent,
            documentFlags = SafDestinationTestFlags.document,
        )
        assertFalse(caps.supportsSeekableWrites)
        assertFalse(caps.supportsTruncate)
    }

    // -----------------------------------------------------------------------
    // Temporary-document identity
    // -----------------------------------------------------------------------

    @Test
    fun `a temporary name carries the opaque partial id, not just the final name`() {
        val identity = PartialIdentity.of(TransferId("t-1"), DestinationStrategy.SAF_STAGED)
        val name = temporaryDocumentName("holiday.mp4", identity)
        assertTrue(name.startsWith("holiday.mp4."))
        assertTrue(name.endsWith(".morsec-part"))
        // The id is deliberately sanitised: ':' and '/' are not safe in a
        // filename, so the name carries a filename-safe form of it rather than
        // the raw value.
        val sanitised = identity.value.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '-'
        }.joinToString("")
        assertTrue(name.contains(sanitised))
    }

    @Test
    fun `two transfers of the same final name never share a temporary name`() {
        val first = temporaryDocumentName(
            "holiday.mp4",
            PartialIdentity.of(TransferId("t-1"), DestinationStrategy.SAF_STAGED),
        )
        val second = temporaryDocumentName(
            "holiday.mp4",
            PartialIdentity.of(TransferId("t-2"), DestinationStrategy.SAF_STAGED),
        )
        assertTrue(first != second)
    }

    @Test
    fun `a temporary name never contains a path separator`() {
        val identity = PartialIdentity("saf_staged:primary:Download/t-1/x")
        val name = temporaryDocumentName("a.mp4", identity)
        assertFalse(name.contains('/'))
    }

    @Test
    fun `cleanup uses the recorded temporary uri, never a filename`() {
        val record = record(temporaryUri = "content://x/tree/1/document/9")
        assertEquals("content://x/tree/1/document/9", record.cleanupUri)
        // The final uri is only the fallback, for the visible-copy strategy.
        val finalOnly = record(temporaryUri = null, finalUri = "content://x/tree/1/document/10")
        assertEquals("content://x/tree/1/document/10", finalOnly.cleanupUri)
        assertNull(record(temporaryUri = null, finalUri = null).cleanupUri)
    }

    @Test
    fun `a commit record refuses a negative expected size`() {
        val thrown = runCatching { record(size = -1L) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `a commit record refuses a blank final name`() {
        val thrown = runCatching { record(name = "  ") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `a commit record accepts a zero-byte file`() {
        assertEquals(0L, record(size = 0L).expectedSizeBytes)
    }

    @Test
    fun `a commit record enforces the SAF filename byte budget`() {
        val limit = SafFilenamePolicy.MAX_FILENAME_BYTES
        assertEquals(limit, SafFilenamePolicy.utf8Length(record(name = "x".repeat(limit)).expectedFinalName))
        assertTrue(runCatching { record(name = "x".repeat(limit + 1)) }.isFailure)
    }

    // -----------------------------------------------------------------------
    // Commit states
    // -----------------------------------------------------------------------

    @Test
    fun `no state jumps straight from copy started to committed`() {
        assertFalse(
            SafCommitState.allows(SafCommitState.COPY_STARTED, SafCommitState.COMMITTED),
        )
        val path = listOf(
            SafCommitState.COPY_COMPLETED,
            SafCommitState.PROVIDER_FLUSH_COMPLETED,
            SafCommitState.PROVIDER_VERIFICATION_STARTED,
            SafCommitState.PROVIDER_VERIFIED,
            SafCommitState.RENAME_STARTED,
            SafCommitState.RENAMED,
            SafCommitState.PUBLISHED_OR_VISIBLE,
            SafCommitState.STAGING_CLEANUP_PENDING,
            SafCommitState.COMMITTED,
        )
        path.zipWithNext().forEach { (from, to) ->
            assertTrue(
                "$from -> $to must be allowed",
                SafCommitState.allows(from, to),
            )
        }
    }

    @Test
    fun `committed is terminal and allows nothing further`() {
        assertTrue(SafCommitState.COMMITTED.isTerminal)
        assertTrue(SafCommitState.next(SafCommitState.COMMITTED).isEmpty())
        assertFalse(SafCommitState.COMMITTED.requiresRecovery)
        assertFalse(SafCommitState.COMMITTED.ownsStaging)
    }

    @Test
    fun `every state still owns staging except the one that released it`() {
        SafCommitState.entries
            .filter { it != SafCommitState.COMMITTED }
            .forEach { state -> assertTrue(state.ownsStaging) }
    }

    @Test
    fun `every state documents what the next pass should do`() {
        SafCommitState.entries.forEach { state ->
            assertTrue(
                "${state.id} must document a restoration action",
                state.restoration.isNotBlank(),
            )
        }
    }

    @Test
    fun `an unreadable provider state is its own state, never a commit`() {
        assertTrue(SafCommitState.RECONCILIATION_REQUIRED.requiresRecovery)
        assertFalse(SafCommitState.RECONCILIATION_REQUIRED.isTerminal)
        // Reconciliation is the one state from which any state may follow,
        // because only a fresh query can say what actually happened.
        assertEquals(
            SafCommitState.entries.toSet(),
            SafCommitState.next(SafCommitState.RECONCILIATION_REQUIRED),
        )
    }

    @Test
    fun `a rename is bracketed by states so a death either side is answerable`() {
        assertTrue(
            SafCommitState.allows(
                SafCommitState.PROVIDER_VERIFIED,
                SafCommitState.RENAME_STARTED,
                SafCommitStrategy.TEMP_THEN_RENAME,
            ),
        )
        assertTrue(
            SafCommitState.allows(
                SafCommitState.RENAME_STARTED,
                SafCommitState.RENAMED,
                SafCommitStrategy.TEMP_THEN_RENAME,
            ),
        )
        // Landing straight on published skips the question "did the rename
        // happen", which is the whole point of having RENAME_STARTED.
        assertFalse(
            SafCommitState.allows(
                SafCommitState.PROVIDER_VERIFIED,
                SafCommitState.PUBLISHED_OR_VISIBLE,
                SafCommitStrategy.TEMP_THEN_RENAME,
            ),
        )
        assertFalse(
            SafCommitState.allows(
                SafCommitState.DESTINATION_RESOLVED,
                SafCommitState.FINAL_CREATED,
                SafCommitStrategy.TEMP_THEN_RENAME,
            ),
        )
    }

    @Test
    fun `the visible-copy strategy takes its own path and never claims a rename`() {
        assertTrue(
            SafCommitState.allows(
                SafCommitState.DESTINATION_RESOLVED,
                SafCommitState.FINAL_CREATED,
                SafCommitStrategy.VISIBLE_FINAL_COPY,
            ),
        )
        assertTrue(
            SafCommitState.allows(
                SafCommitState.PROVIDER_VERIFIED,
                SafCommitState.PUBLISHED_OR_VISIBLE,
                SafCommitStrategy.VISIBLE_FINAL_COPY,
            ),
        )
        assertFalse(
            SafCommitState.allows(
                SafCommitState.DESTINATION_RESOLVED,
                SafCommitState.TEMPORARY_CREATED,
                SafCommitStrategy.VISIBLE_FINAL_COPY,
            ),
        )
        assertFalse(
            SafCommitState.allows(
                SafCommitState.PROVIDER_VERIFIED,
                SafCommitState.RENAME_STARTED,
                SafCommitStrategy.VISIBLE_FINAL_COPY,
            ),
        )
    }

    @Test
    fun `a failed commit can be retried but not marked committed`() {
        assertTrue(SafCommitState.allows(SafCommitState.COMMIT_FAILED, SafCommitState.DESTINATION_RESOLVED))
        assertFalse(SafCommitState.allows(SafCommitState.COMMIT_FAILED, SafCommitState.COMMITTED))
    }

    @Test
    fun `staging is only released from the cleanup state`() {
        assertTrue(
            SafCommitState.allows(
                SafCommitState.PUBLISHED_OR_VISIBLE,
                SafCommitState.STAGING_CLEANUP_PENDING,
            ),
        )
        assertTrue(SafCommitState.allows(SafCommitState.STAGING_CLEANUP_PENDING, SafCommitState.COMMITTED))
        // No earlier state is allowed to declare the staging gone.
        assertFalse(
            SafCommitState.allows(SafCommitState.PROVIDER_VERIFIED, SafCommitState.STAGING_CLEANUP_PENDING),
        )
    }

    @Test
    fun `ids round-trip and an unknown id degrades safely`() {
        SafCommitState.entries.forEach { state -> assertEquals(state, SafCommitState.fromId(state.id)) }
        SafCommitStrategy.entries.forEach { strategy ->
            assertEquals(strategy, SafCommitStrategy.fromId(strategy.id))
        }
        assertEquals(SafCommitState.STAGING_VERIFIED, SafCommitState.fromId("nonsense"))
        assertEquals(SafCommitStrategy.TEMP_THEN_RENAME, SafCommitStrategy.fromId("nonsense"))
    }

    @Test
    fun `the temporary strategy avoids a partial under the final name, the other does not`() {
        assertTrue(SafCommitStrategy.TEMP_THEN_RENAME.avoidsVisiblePartialUnderFinalName)
        assertFalse(SafCommitStrategy.VISIBLE_FINAL_COPY.avoidsVisiblePartialUnderFinalName)
    }

    // -----------------------------------------------------------------------

    private fun record(
        name: String = "holiday.mp4",
        size: Long = 1_048_576L,
        temporaryUri: String? = null,
        finalUri: String? = null,
    ): SafCommitRecord = SafCommitRecord(
        sessionId = SessionId("session-1"),
        transferId = TransferId("t-1"),
        partialId = PartialIdentity.of(TransferId("t-1"), DestinationStrategy.SAF_STAGED),
        treeUri = treeUri,
        rootDocumentId = rootDocumentId,
        parentDocumentId = rootDocumentId,
        temporaryUri = temporaryUri,
        finalUri = finalUri,
        expectedFinalName = name,
        expectedSizeBytes = size,
        duplicatePolicy = DuplicatePolicy.RENAME,
    )

    private object SafDestinationTestFlags {
        const val dirSupportsCreate = 0x00000008
        const val supportsWrite = 0x00000002
        const val supportsDelete = 0x00000004
        const val rename = 0x00000040
        const val supportsMove = 0x00000100
        const val parent = dirSupportsCreate
        const val document = supportsWrite or supportsDelete or rename or supportsMove
    }
}
