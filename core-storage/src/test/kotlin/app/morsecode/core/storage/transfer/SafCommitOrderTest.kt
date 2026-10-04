package app.morsecode.core.storage.transfer

import android.net.Uri
import app.morsecode.core.model.DuplicatePolicy
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * The commit sequence, in order.
 *
 * The order is the safety property, not an implementation detail. Each of these
 * tests names one inversion and asserts it does not happen, because each
 * inversion has a specific way of producing a file that is reported as delivered
 * and is not:
 *
 *  verifying through the write descriptor reads bytes the provider may still be
 *  buffering, so the digest is of the write, not of the document;
 *
 *  renaming before verifying publishes a name that has not earned it;
 *
 *  releasing staging before final verification destroys the only copy of the
 *  bytes in order to save space during a commit that has not succeeded;
 *
 *  advancing after a failed flush or close records a state the bytes are not in.
 *
 * The gateway here is a recorder, not a provider. A faithful fake of one
 * provider would tell us about that provider; what has to hold for all of them
 * is the sequence.
 */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafCommitOrderTest {

    private val treeUri =
        "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    private val parentDocumentId = "primary:Download"

    private val grant = SafTreeGrant(
        grantId = "g-1",
        treeUri = Uri.parse(treeUri),
        rootDocumentId = parentDocumentId,
        authority = requireNotNull(Uri.parse(treeUri).authority),
        writable = true,
    )

    private val bytes = "morsec".toByteArray()

    private fun record(
        policy: DuplicatePolicy = DuplicatePolicy.RENAME,
        strategy: SafCommitStrategy = SafCommitStrategy.TEMP_THEN_RENAME,
        gateway: RecordingSafGateway = RecordingSafGateway(),
        allowVisibleFinalCopy: Boolean = false,
    ): Pair<SafCommitOutcome, Order> {
        val log = Order(gateway.calls)
        val staging = RecordingStaging(bytes, gateway.calls)
        val coordinator = SafCommitCoordinator(
            gateway = gateway,
            staging = staging,
            allowVisibleFinalCopy = allowVisibleFinalCopy,
        )
        val outcome = coordinator.commit(
            SafCommitRecord(
                transferId = app.morsecode.core.transfer.identity.TransferId("t-1"),
                partialId = PartialIdentity("p-1"),
                treeUri = treeUri,
                rootDocumentId = parentDocumentId,
                parentDocumentId = parentDocumentId,
                expectedFinalName = "movie.mp4",
                expectedSizeBytes = bytes.size.toLong(),
                strategy = strategy,
                duplicatePolicy = policy,
            ),
            grant,
        )
        return outcome to log
    }

    /** Positional queries over one recorded order. */
    private class Order(private val calls: List<String>) {
        fun after(first: String, second: String): Boolean {
            val a = calls.indexOfFirst { it.startsWith(first) }
            val b = calls.indexOfFirst { it.startsWith(second) }
            assertTrue("'$first' must appear", a >= 0)
            assertTrue("'$second' must appear", b >= 0)
            return a < b
        }

        fun contains(prefix: String): Boolean = calls.any { it.startsWith(prefix) }

        override fun toString(): String = calls.joinToString("\n")
    }

    private class RecordingStaging(
        private val bytes: ByteArray,
        private val log: MutableList<String>,
        private val deletes: () -> Boolean = { true },
    ) : SafStaging {
        override fun length(identity: PartialIdentity): Long? = bytes.size.toLong()

        override fun open(identity: PartialIdentity): SafOpen {
            log += "staging:open"
            return SafOpen.Opened(SafReadHandle(ByteArrayInputStream(bytes)))
        }

        override fun delete(identity: PartialIdentity): Boolean {
            log += "staging:delete"
            return deletes()
        }
    }

    @Test
    fun `a successful commit runs the sequence in the documented order`() {
        val gateway = RecordingSafGateway()
        val (outcome, order) = record(gateway = gateway)
        assertTrue(outcome is SafCommitOutcome.Committed)

        // Staging is hashed in a bounded fresh pass before any provider child lookup.
        assertTrue(order.after("staging:open", "findChild:"))
        assertEquals(2, gateway.calls.count { it == "staging:open" })

        // A live grant is checked immediately before each provider phase.
        assertTrue(order.after("authorize:reconcile", "findChild:"))
        assertTrue(order.after("authorize:create_destination", "create:"))
        assertTrue(order.after("authorize:open_write", "openWrite:"))
        assertTrue(order.after("authorize:verify", "openRead:"))
        assertTrue(order.after("authorize:rename", "rename:"))

        // Copy and flush precede the close that releases the bytes.
        assertTrue(order.after("openWrite:", "flush:"))
        assertTrue(order.after("flush:", "closeWrite:"))
        // Destination is queried before it is reopened for verification.
        assertTrue(order.after("closeWrite:", "query:"))
        assertTrue(order.after("query:", "openRead:"))
        // Verification is the last thing before publication.
        assertTrue(order.after("openRead:", "closeRead:"))
        assertTrue(order.after("closeRead:", "rename:"))
        // The renamed identity is reopened and verified again before staging
        // can be released; the first read proved the temp, not the final name.
        val finalRename = gateway.calls.indexOfLast { it.startsWith("rename:") }
        val finalRead = gateway.calls.indexOfLast { it.startsWith("openRead:") }
        val finalReadClose = gateway.calls.indexOfLast { it.startsWith("closeRead:") }
        val stagingDelete = gateway.calls.indexOfFirst { it == "staging:delete" }
        assertTrue("final verification follows rename", finalRename < finalRead)
        assertTrue("final read owner closes before staging release", finalReadClose < stagingDelete)
        // Staging is released only after the rename is settled.
        assertTrue(order.after("query:", "staging:delete"))
    }

    @Test
    fun `verification never reads through the descriptor that wrote the bytes`() {
        val (_, order) = record()
        // The write owner is closed before the read owner exists: one owner per
        // descriptor, and verification starts at zero on a fresh one.
        assertTrue(
            "the write descriptor must be released before verification opens",
            order.after("closeWrite:", "openRead:"),
        )
    }

    @Test
    fun `nothing is renamed before the copy has been verified`() {
        val (_, order) = record()
        assertTrue(
            "verification must finish before the rename starts",
            order.after("closeRead:", "rename:"),
        )
    }

    @Test
    fun `staging is not released before the final verification has passed`() {
        val (_, order) = record()
        assertTrue(
            "staging must outlive verification",
            order.after("openRead:", "staging:delete"),
        )
    }

    @Test
    fun `a flush the provider refuses stops the commit before verification`() {
        // A gateway that will not open for write yields FlushUnsupported, which
        // must not be treated as flushed.
        val gateway = RecordingSafGateway(openFailure = TransferStorageError.Io("write"))
        val (outcome, order) = record(gateway = gateway)
        assertTrue(outcome is SafCommitOutcome.Failed)
        assertTrue("no verification may be attempted", !order.contains("openRead:"))
        assertTrue("nothing may be renamed", !order.contains("rename:"))
        assertTrue("staging must be retained", !order.contains("staging:delete"))
    }

    @Test
    fun `a rename the provider refuses stops the commit before staging is released`() {
        val gateway = RecordingSafGateway(renameFailure = TransferStorageError.Io("rename"))
        val (outcome, order) = record(gateway = gateway)
        assertTrue(outcome is SafCommitOutcome.Failed)
        assertTrue("verification still happens, before the rename", order.contains("openRead:"))
        assertTrue("staging must survive a failed rename", !order.contains("staging:delete"))
    }

    @Test
    fun `a failed rename leaves the state on failure, never on published`() {
        val gateway = RecordingSafGateway(renameFailure = TransferStorageError.Io("rename"))
        val (outcome, _) = record(gateway = gateway)
        val failed = outcome as SafCommitOutcome.Failed
        assertTrue(
            "a failed rename must not advance toward committed",
            failed.record.state == SafCommitState.COMMIT_FAILED ||
                failed.record.state == SafCommitState.RECONCILIATION_REQUIRED,
        )
    }

    @Test
    fun `a copy that reports fewer bytes than expected does not verify`() {
        // The staged length is the authority; a short copy must not be renamed.
        val gateway = RecordingSafGateway()
        val staging = ShortStaging(bytes)
        val coordinator = SafCommitCoordinator(gateway = gateway, staging = staging)
        val outcome = coordinator.commit(
            SafCommitRecord(
                transferId = app.morsecode.core.transfer.identity.TransferId("t-1"),
                partialId = PartialIdentity("p-1"),
                treeUri = treeUri,
                rootDocumentId = parentDocumentId,
                parentDocumentId = parentDocumentId,
                expectedFinalName = "movie.mp4",
                expectedSizeBytes = bytes.size.toLong() + 1,
            ),
            grant,
        )
        assertTrue(outcome is SafCommitOutcome.Failed)
        assertTrue(!gateway.calls.any { it.startsWith("rename:") })
    }

    @Test
    fun `a failed commit never claims delivery`() {
        val gateway = RecordingSafGateway(renameFailure = TransferStorageError.Io("rename"))
        val (outcome, _) = record(gateway = gateway)
        assertEquals(false, outcome.isDelivered)
    }

    @Test
    fun `handles are all closed by the end of a successful commit`() {
        val gateway = RecordingSafGateway()
        record(gateway = gateway)
        assertEquals(
            "every descriptor opened must be released",
            gateway.openedHandles,
            gateway.closedHandles,
        )
        assertEquals(0, gateway.liveHandles)
    }

    @Test
    fun `handles are all closed by the end of a failed commit too`() {
        val gateway = RecordingSafGateway(renameFailure = TransferStorageError.Io("rename"))
        record(gateway = gateway)
        assertEquals(
            "a failed commit must not leak the write descriptor",
            gateway.openedHandles,
            gateway.closedHandles,
        )
    }

    @Test
    fun `a write descriptor close failure is surfaced and prevents verification`() {
        val gateway = RecordingSafGateway()
        gateway.closeFailureOn = { operation ->
            if (operation == RecordingSafGateway.OP_CLOSE_WRITE) IOException("close failed") else null
        }

        val (outcome, order) = record(gateway = gateway)

        val failed = outcome as SafCommitOutcome.Failed
        assertEquals(TransferStorageError.Io("close"), failed.error)
        assertTrue("the fresh read must not open after a write close failure", !order.contains("openRead:"))
        assertTrue("the document must not be renamed", !order.contains("rename:"))
        assertTrue("staging must be retained", !order.contains("staging:delete"))
        assertEquals(gateway.openedHandles, gateway.closedHandles)
    }

    @Test
    fun `a verification descriptor close failure is surfaced and prevents rename`() {
        val gateway = RecordingSafGateway()
        gateway.closeFailureOn = { operation ->
            if (operation == RecordingSafGateway.OP_CLOSE_READ) IOException("close failed") else null
        }

        val (outcome, order) = record(gateway = gateway)

        val failed = outcome as SafCommitOutcome.Failed
        assertEquals(TransferStorageError.Io("close"), failed.error)
        assertTrue("verification did open a fresh read owner", order.contains("openRead:"))
        assertTrue("the document must not be renamed", !order.contains("rename:"))
        assertTrue("staging must be retained", !order.contains("staging:delete"))
        assertEquals(gateway.openedHandles, gateway.closedHandles)
    }

    @Test
    fun `an overwrite that cannot replace safely does not touch the existing document`() {
        val gateway = RecordingSafGateway()
        gateway.addNamed("$treeUri/document/seed", "movie.mp4")
        val (outcome, _) = record(
            policy = DuplicatePolicy.OVERWRITE,
            strategy = SafCommitStrategy.VISIBLE_FINAL_COPY,
            gateway = gateway,
            allowVisibleFinalCopy = true,
        )
        assertTrue(
            "an unsupported overwrite must be reported as unsupported, not attempted",
            outcome is SafCommitOutcome.SafeOverwriteUnsupported,
        )
    }

    /** Reports a length that disagrees with the bytes it hands out. */
    private class ShortStaging(private val bytes: ByteArray) : SafStaging {
        override fun length(identity: PartialIdentity): Long? = bytes.size.toLong() + 1

        override fun open(identity: PartialIdentity): SafOpen =
            SafOpen.Opened(SafReadHandle(ByteArrayInputStream(bytes)))

        override fun delete(identity: PartialIdentity): Boolean = true
    }
}
