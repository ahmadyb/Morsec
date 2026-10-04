package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/*
 * The API tier is a type, not a number.
 *
 * These tests replace the design that came before, where one class held an
 * injected sdkInt integer and an `if` in front of each versioned call. That
 * integer was not the device, so anything able to construct the object could
 * point production code at a symbol the running device does not have -- and the
 * failure for that is a linkage error at the call site, which no typed error
 * mapping downstream can catch.
 *
 * The tests below run under real Robolectric SDK levels, so Build.VERSION is
 * genuinely different in each one. That is what makes them worth having: the
 * tier is selected by the same branch production uses, on the SDK the framework
 * classes actually loaded.
 *
 * What is being proved on the low tiers is not "the answer was indeterminate"
 * but "the question was never asked". A device that cannot answer has not
 * answered no, and the two must stay distinguishable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafPlatformOperationsTest {

    private val authority = "app.morsecode.test.tiers"
    private val treeUri: Uri = Uri.parse("content://$authority/tree/primary%3ADownload")
    private val rootId = "primary:Download"

    private lateinit var provider: FakeSafProvider
    private lateinit var resolver: ContentResolver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        resolver = context.contentResolver
        provider = Robolectric.buildContentProvider(FakeSafProvider::class.java)
            .create(ProviderInfo().apply {
                this.authority = this@SafPlatformOperationsTest.authority
                grantUriPermissions = true
            })
            .get()
        provider.reset(rootId, treeUri)
    }

    private fun uriFor(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    // -----------------------------------------------------------------------
    // Tier selection, per real SDK level
    // -----------------------------------------------------------------------

    @Test
    @Config(sdk = [23])
    fun `the factory selects the grant-scoped tier at API 23`() {
        assertEquals(SafApi23Operations, SafPlatformOperations.create())
        assertEquals(SafContainmentTier.CANONICAL_ONLY, SafPlatformOperations.create().tier)
        assertFalse("below API 26 there is nothing to ask", SafPlatformOperations.create().canAskProvider)
    }

    @Test
    @Config(sdk = [25])
    fun `the factory selects the grant-scoped tier at API 25`() {
        assertEquals(SafApi23Operations, SafPlatformOperations.create())
    }

    @Test
    @Config(sdk = [26])
    fun `the factory selects the document-path tier at API 26`() {
        assertEquals(SafApi26Operations, SafPlatformOperations.create())
        assertTrue(SafPlatformOperations.create().canAskProvider)
    }

    @Test
    @Config(sdk = [28])
    fun `the factory selects the document-path tier at API 28`() {
        assertEquals(SafApi26Operations, SafPlatformOperations.create())
    }

    @Test
    @Config(sdk = [29])
    fun `the factory selects the child-document tier at API 29`() {
        assertEquals(SafApi29Operations, SafPlatformOperations.create())
    }

    @Test
    @Config(sdk = [34])
    fun `the factory selects the child-document tier at the latest configured API`() {
        assertEquals(SafApi29Operations, SafPlatformOperations.create())
    }

    @Test
    fun `the factory branches on the real device sdk rather than an argument`() {
        // Runs at the class-level SDK. If the factory ever took an SDK number,
        // this is the assertion that would stop being meaningful.
        assertEquals(
            SafContainmentTier.forSdk(Build.VERSION.SDK_INT),
            SafPlatformOperations.create().tier,
        )
        assertEquals(
            "this test runs at an SDK that supports the strongest tier",
            SafContainmentTier.CHILD_DOCUMENT,
            SafPlatformOperations.create().tier,
        )
    }

    // -----------------------------------------------------------------------
    // What each tier asks, and what it must never ask
    // -----------------------------------------------------------------------

    @Test
    @Config(sdk = [23])
    fun `a gateway built for API 23 asks nothing the device cannot answer`() {
        val gateway = DocumentsContractSafGateway(resolver)

        assertEquals(
            SafChildAnswer.Indeterminate,
            gateway.isChildDocument(uriFor(rootId), uriFor("$rootId/2026")),
        )
        assertEquals(SafPathAnswer.Indeterminate, gateway.documentPath(uriFor("$rootId/2026")))

        assertEquals(0, provider.callCount("android:isChildDocument"))
        assertEquals(0, provider.callCount("android:findDocumentPath"))
    }

    @Test
    @Config(sdk = [23])
    fun `the API 23 tier never resolves findDocumentPath`() {
        val operations = SafPlatformOperations.create()

        assertEquals(SafPathAnswer.Indeterminate, operations.documentPath(resolver, uriFor(rootId)))
        assertEquals(0, provider.callCount("android:findDocumentPath"))
    }

    @Test
    @Config(sdk = [23])
    fun `the API 23 tier never resolves isChildDocument`() {
        val operations = SafPlatformOperations.create()

        assertEquals(
            SafChildAnswer.Indeterminate,
            operations.isChildDocument(resolver, uriFor(rootId), uriFor("$rootId/2026")),
        )
        assertEquals(0, provider.callCount("android:isChildDocument"))
    }

    @Test
    @Config(sdk = [26])
    fun `the API 26 tier asks findDocumentPath`() {
        provider.addDocument("$rootId/2026", "2026")
        val operations = SafPlatformOperations.create()

        operations.documentPath(resolver, uriFor("$rootId/2026"))

        assertEquals(
            "API 26 can ask, so it must ask",
            1,
            provider.callCount("android:findDocumentPath"),
        )
    }

    @Test
    @Config(sdk = [26])
    fun `the API 26 tier never resolves isChildDocument`() {
        provider.addDocument("$rootId/2026", "2026")
        val operations = SafPlatformOperations.create()

        assertEquals(
            SafChildAnswer.Indeterminate,
            operations.isChildDocument(resolver, uriFor(rootId), uriFor("$rootId/2026")),
        )
        // The path could be used to guess the answer. It is not: an inferred
        // "is a child" would be this app's guess in the provider's voice.
        assertEquals(0, provider.callCount("android:isChildDocument"))
    }

    @Test
    @Config(sdk = [29])
    fun `the API 29 tier asks isChildDocument`() {
        provider.addDocument("$rootId/2026", "2026")
        val operations = SafPlatformOperations.create()

        val answer = operations.isChildDocument(resolver, uriFor(rootId), uriFor("$rootId/2026"))

        assertTrue("got $answer", answer is SafChildAnswer.Answered)
        assertEquals(true, (answer as SafChildAnswer.Answered).isChild)
        assertEquals(1, provider.callCount("android:isChildDocument"))
    }

    // -----------------------------------------------------------------------
    // Fakes replace a tier; they do not choose one by number
    // -----------------------------------------------------------------------

    @Test
    fun `an injected fake answers without the gateway consulting a device sdk`() {
        val fake = FakeSafPlatformOperations(
            tier = SafContainmentTier.CANONICAL_ONLY,
            canAskProvider = false,
            childAnswer = SafChildAnswer.Answered(true),
        )
        val gateway = DocumentsContractSafGateway(resolver, operations = fake)

        assertEquals(
            SafChildAnswer.Answered(true),
            gateway.isChildDocument(uriFor(rootId), uriFor("$rootId/2026")),
        )
        assertEquals(1, fake.isChildDocumentCalls)
        assertEquals(
            "a fake stands in for the tier; it must not redirect production at the provider",
            0,
            provider.callCount("android:isChildDocument"),
        )
    }

    @Test
    fun `typed outcomes survive the tier without being collapsed`() {
        val fake = FakeSafPlatformOperations(
            childAnswer = SafChildAnswer.Revoked,
            pathAnswer = SafPathAnswer.Revoked,
        )
        val gateway = DocumentsContractSafGateway(resolver, operations = fake)

        assertEquals(
            "a revocation is not an indeterminate answer",
            SafChildAnswer.Revoked,
            gateway.isChildDocument(uriFor(rootId), uriFor("$rootId/2026")),
        )
        assertEquals(
            SafPathAnswer.Revoked,
            gateway.documentPath(uriFor("$rootId/2026")),
        )
    }

    @Test
    fun `a gateway reports the tier it was given`() {
        val gateway = DocumentsContractSafGateway(
            resolver,
            operations = FakeSafPlatformOperations(tier = SafContainmentTier.CANONICAL_ONLY),
        )
        assertEquals(SafContainmentTier.CANONICAL_ONLY, gateway.containmentTier)

        val onThisDevice = DocumentsContractSafGateway(resolver)
        assertEquals(SafContainmentTier.forSdk(Build.VERSION.SDK_INT), onThisDevice.containmentTier)
    }
}
