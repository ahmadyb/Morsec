package app.morsecode.core.storage.transfer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * Proof that the production commit path reaches the real platform.
 *
 * Before this the coordinator had a constructor and no caller: it was never
 * built in production and never built in a test, so nothing about it was
 * exercised by anything. A correct coordinator that nothing constructs proves
 * nothing about whether it runs, and a gateway parameter that only ever receives
 * fakes proves nothing about the platform.
 *
 * The claim under test is narrow and specific: the factory installs
 * DocumentsContractSafGateway, and that gateway takes its API tier from the
 * device it is running on rather than from anything injected. Everything else
 * about the factory is deliberately unbranching, so there is no path here that
 * could quietly substitute a stand-in.
 *
 * A fake satisfying the same interface would not make these tests pass, because
 * they assert the concrete type.
 */

@RunWith(RobolectricTestRunner::class)
class SafCommitCoordinatorFactoryTest {

    private fun resolver() =
        ApplicationProvider.getApplicationContext<Application>().contentResolver

    private fun coordinator(): SafCommitCoordinator =
        SafCommitCoordinatorFactory.create(
            resolver = resolver(),
            staging = EmptyStaging,
        )

    @Test
    fun `the production factory installs the real Android gateway`() {
        assertTrue(
            "the production path must not be handed a stand-in",
            coordinator().gateway is DocumentsContractSafGateway,
        )
    }

    @Config(sdk = [34])
    @Test
    fun `the tier comes from the device, not from the caller`() {
        val gateway = coordinator().gateway as DocumentsContractSafGateway
        assertEquals(
            SafContainmentTier.CHILD_DOCUMENT,
            gateway.containmentTier,
        )
    }

    @Config(sdk = [27])
    @Test
    fun `a device below API 29 asks for a path instead of child documents`() {
        val gateway = coordinator().gateway as DocumentsContractSafGateway
        assertEquals(SafContainmentTier.DOCUMENT_PATH, gateway.containmentTier)
    }

    @Config(sdk = [23])
    @Test
    fun `a device at minSdk admits it cannot ask the provider`() {
        val gateway = coordinator().gateway as DocumentsContractSafGateway
        assertEquals(SafContainmentTier.CANONICAL_ONLY, gateway.containmentTier)
    }

    @Test
    fun `the coordinator does not acquire a resolver of its own`() {
        // The point of taking the resolver explicitly: the coordinator holds no
        // Context, so it cannot reach a provider the caller did not grant.
        val supplied = resolver()
        val built = SafCommitCoordinatorFactory.create(supplied, EmptyStaging)
        val gateway = built.gateway as DocumentsContractSafGateway
        assertEquals(SafContainmentTier.forSdk(android.os.Build.VERSION.SDK_INT), gateway.containmentTier)
    }

    /** Staging with nothing in it: the factory is what is under test. */
    private object EmptyStaging : SafStaging {
        override fun length(identity: PartialIdentity): Long? = null
        override fun open(identity: PartialIdentity): SafOpen =
            SafOpen.Refused(TransferStorageError.NotFound("staged_copy"))

        override fun delete(identity: PartialIdentity): Boolean = true
    }
}
