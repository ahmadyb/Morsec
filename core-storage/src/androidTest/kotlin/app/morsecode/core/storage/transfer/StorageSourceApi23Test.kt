package app.morsecode.core.storage.transfer

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.morsecode.core.model.SafGrant
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.integrity.Sha256Digest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real resolver, provider, descriptors and pipes on the API 23 emulator, not Robolectric. */
@RunWith(AndroidJUnit4::class)
class StorageSourceApi23Test {
    private lateinit var context: Context
    private val readers = mutableListOf<TypedSourceReader>()
    private var grantHeld = true

    @Before
    fun setUp() {
        assertEquals(23, Build.VERSION.SDK_INT)
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Slice4sProviderControls.cleanup(context)
        grantHeld = true
    }

    @After
    fun tearDown() {
        readers.forEach { it.close() }
        Slice4sProviderControls.cleanup(context)
        val partials = File(context.cacheDir, "slice4s-short-source")
        check(!partials.exists() || partials.deleteRecursively())
    }

    private fun bytes(size: Int): ByteArray = ByteArray(size) { ((it * 37 + 11) and 255).toByte() }
    private fun digest(data: ByteArray): Sha256Digest = Sha256Accumulator().update(data).digest()

    private fun source(data: ByteArray, pipe: Boolean, reportedSize: Long?, failAfter: Int? = null): TransferSource {
        val uri = Slice4sProviderControls.seed(context, data, pipe, reportedSize, failAfter)
        assertEquals("content", uri.scheme)
        val tree = DocumentsContract.buildTreeDocumentUri(Slice4sProviderControls.AUTHORITY, Slice4sProviderControls.ROOT_ID)
        val grant = SafGrant(treeUri = tree.toString(), displayName = "Slice 4S", readWrite = false)
        val result = SafDocumentTransferSources.create(
            resolver = context.contentResolver,
            grants = listOf(grant),
            documentUri = uri,
            relativePath = RelativeTransferPath("fixture.bin"),
            grantChecker = SafGrantChecker { grantHeld },
        )
        assertTrue(result is SafSourceResult.Ready)
        return (result as SafSourceResult.Ready).source
    }

    private fun open(source: TransferSource, offset: Long = 0L, expected: Sha256Digest? = null): TypedSourceOpen {
        val authorization = if (offset > 0L) {
            val stored = (source.fingerprint() as SourceFingerprintResult.Available).fingerprint
            SourceResumePolicy.authorize(stored, source.fingerprint(), expected) as SourceResumeDecision.Ready
        } else null
        return TypedSourceReader.openAt(source, offset, authorization).also {
            if (it is TypedSourceOpen.Opened) readers += it.reader
        }
    }

    private fun readAll(reader: TypedSourceReader, request: Int = 733): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(request)
        while (true) {
            when (val read = reader.read(buffer, 0, buffer.size)) {
                is SourceReadResult.Data -> result.write(buffer, 0, read.count)
                SourceReadResult.EndOfSource -> return result.toByteArray()
                is SourceReadResult.Failure -> throw AssertionError("typed read failed: ${read.error.category}")
            }
        }
    }

    @Test(timeout = 15000)
    fun mergedTestProviderIsPrivateAndAccessibleToTheInstrumentationUid() {
        val provider = context.packageManager.resolveContentProvider(Slice4sProviderControls.AUTHORITY, 0)
        assertTrue("test provider must be present in the installed manifest", provider != null)
        requireNotNull(provider)
        assertEquals(Slice4sTestProvider::class.java.name, provider.name)
        assertEquals(Slice4sProviderControls.AUTHORITY, provider.authority)
        assertFalse(provider.exported)
        assertFalse(provider.grantUriPermissions)
        assertEquals(Process.myUid(), provider.applicationInfo.uid)
        val source = source(bytes(12), pipe = false, reportedSize = 12L)
        assertArrayEquals(bytes(12), readAll((open(source) as TypedSourceOpen.Opened).reader))
        Log.i("Morsec4S", "test_provider private same_uid resolver_open=ok")
    }

    @Test(timeout = 15000)
    fun seekableContentSourceReadsMultipleChunksAndOpensAtNonzeroOffset() {
        val data = bytes(130_111)
        val source = source(data, pipe = false, reportedSize = data.size.toLong())
        assertTrue(source.isSeekable)
        assertEquals(data.size.toLong(), source.sizeBytes)
        val fresh = open(source) as TypedSourceOpen.Opened
        assertArrayEquals(data, readAll(fresh.reader, 1021))
        val resumed = open(source, 2_053L, digest(data)) as TypedSourceOpen.Opened
        assertEquals(2_053L, resumed.offset)
        assertArrayEquals(data.copyOfRange(2_053, data.size), readAll(resumed.reader, 509))
    }

    @Test(timeout = 15000)
    fun nonseekablePipeDiscardsToOffsetWithABoundedBufferAndTypedResume() {
        val data = bytes(8_321)
        val source = source(data, pipe = true, reportedSize = null)
        assertFalse(source.isSeekable)
        assertEquals(-1L, source.sizeBytes)
        val raw = ProviderSourceOpener.open(context.contentResolver, source.uri, 113L, buffer = ByteArray(17))
        assertTrue(raw is ProviderOpenResult.Opened)
        val handle = (raw as ProviderOpenResult.Opened).handle
        assertFalse(handle.isSeekable)
        assertEquals(113L, handle.position)
        val reader = TypedSourceReader(handle).also(readers::add)
        assertArrayEquals(data.copyOfRange(113, data.size), readAll(reader, 211))
        val resumed = open(source, 59L, digest(data)) as TypedSourceOpen.Opened
        assertArrayEquals(data.copyOfRange(59, data.size), readAll(resumed.reader, 173))
    }

    @Test(timeout = 15000)
    fun unknownLengthAndEmptySourcesHaveDistinctEofAndMetadata() {
        val data = bytes(2_303)
        val unknown = source(data, pipe = true, reportedSize = null)
        assertEquals(-1L, unknown.sizeBytes)
        assertArrayEquals(data, readAll((open(unknown) as TypedSourceOpen.Opened).reader))
        Slice4sProviderControls.cleanup(context)
        val empty = source(ByteArray(0), pipe = false, reportedSize = 0L)
        assertEquals(0L, empty.sizeBytes)
        val reader = (open(empty) as TypedSourceOpen.Opened).reader
        assertEquals(SourceReadResult.EndOfSource, reader.read(ByteArray(8), 0, 8))
        assertEquals(SourceReadResult.EndOfSource, reader.read(ByteArray(8), 0, 8))
    }

    @Test(timeout = 15000)
    fun pipeEofBeforeResumeOffsetIsRefusedRatherThanMispositioned() {
        val data = bytes(41)
        val source = source(data, pipe = true, reportedSize = null)
        val raw = ProviderSourceOpener.open(context.contentResolver, source.uri, 60L, buffer = ByteArray(7))
        assertTrue(raw is ProviderOpenResult.Failed)
        assertEquals("source_shorter_than_offset", ((raw as ProviderOpenResult.Failed).error as TransferStorageError.StateConflict).reason)
        val typed = open(source, 60L, digest(data))
        assertTrue(typed is TypedSourceOpen.Failed)
        val error = (typed as TypedSourceOpen.Failed).error
        assertEquals(TransferStorageErrorCategory.STATE, error.category)
        assertNull(error.diagnostic)
    }

    @Test(timeout = 15000)
    fun changedKnownSizeForcesRestartBeforeAnyResumeOpen() {
        val data = bytes(1_100)
        val source = source(data, pipe = false, reportedSize = data.size.toLong())
        val stored = (source.fingerprint() as SourceFingerprintResult.Available).fingerprint
        val ready = SourceResumePolicy.authorize(stored, source.fingerprint(), digest(data)) as SourceResumeDecision.Ready
        Slice4sProviderControls.row!!.reportedSize = data.size.toLong() + 1L
        val decision = SourceResumePolicy.authorize(stored, source.fingerprint(), digest(data))
        assertTrue(decision is SourceResumeDecision.Refused)
        assertTrue((decision as SourceResumeDecision.Refused).fingerprintOutcome.requiresRestart)
        val attempts = Slice4sProviderControls.readAttempts.get()
        val opened = TypedSourceReader.openAt(source, 20L, ready)
        assertTrue(opened is TypedSourceOpen.Failed)
        assertEquals(TransferStorageErrorCategory.STATE, (opened as TypedSourceOpen.Failed).error.category)
        assertEquals(attempts, Slice4sProviderControls.readAttempts.get())
    }

    @Test(timeout = 15000)
    fun grantRefusalAndProviderSecurityExceptionAreTypedBeforeReading() {
        val source = source(bytes(32), pipe = false, reportedSize = 32L)
        val attempts = Slice4sProviderControls.readAttempts.get()
        grantHeld = false
        val denied = open(source) as TypedSourceOpen.Failed
        assertEquals(TransferStorageErrorCategory.PERMISSION_REVOKED, denied.error.category)
        assertEquals(attempts, Slice4sProviderControls.readAttempts.get())
        grantHeld = true
        Slice4sProviderControls.denyReads = true
        val rejected = open(source) as TypedSourceOpen.Failed
        assertEquals(TransferStorageErrorCategory.PERMISSION_REVOKED, rejected.error.category)
        assertNull(rejected.error.diagnostic)
        assertFalse(rejected.error.safeMessage().contains("private provider detail"))
    }

    @Test(timeout = 15000)
    fun realOpenerClosesItsDescriptorAndDerivedPipeStreamOnceOnEof() {
        val source = source(bytes(33), pipe = true, reportedSize = 33L)
        var descriptor: ParcelFileDescriptor? = null
        val probe = SeekabilityProbe { opened ->
            descriptor = opened
            ParcelDescriptorSeekabilityProbe.probe(opened)
        }
        val result = ProviderSourceOpener.open(context.contentResolver, source.uri, 0L, probe)
        assertTrue(result is ProviderOpenResult.Opened)
        val handle = (result as ProviderOpenResult.Opened).handle
        assertFalse(handle.isSeekable)
        // Inspect the real opener's owner, rather than constructing a substitute
        // handle. No instrumentation hook or production API is added for this.
        val field = handle.javaClass.getDeclaredField("resource").apply { isAccessible = true }
        val resource = field.get(handle) as OwnedSourceResource
        val owner = resource.descriptor()
        val reader = TypedSourceReader(handle).also(readers::add)
        assertArrayEquals(bytes(33), readAll(reader))
        reader.close()
        reader.close()
        assertEquals(1, owner.closeCount)
        assertEquals(1, resource.derivedCloseCount)
        assertFalse(requireNotNull(descriptor).fileDescriptor.valid())

        // The early-EOF failure path also releases its real opened descriptor.
        var failedDescriptor: ParcelFileDescriptor? = null
        val failingProbe = SeekabilityProbe { opened ->
            failedDescriptor = opened
            ParcelDescriptorSeekabilityProbe.probe(opened)
        }
        val failed = ProviderSourceOpener.open(context.contentResolver, source.uri, 40L, failingProbe, ByteArray(7))
        assertTrue(failed is ProviderOpenResult.Failed)
        assertFalse(requireNotNull(failedDescriptor).fileDescriptor.valid())
    }

    @Test(timeout = 15000)
    fun reliablePipeFailureIsVisibleToCheckErrorButRawTypedReadSeesPrematureEof() {
        val expected = bytes(128)
        val source = source(expected, pipe = true, reportedSize = expected.size.toLong(), failAfter = 17)
        val reader = (open(source) as TypedSourceOpen.Opened).reader
        val received = readAll(reader, 19)
        assertArrayEquals(expected.copyOf(17), received)
        // A raw FileInputStream does not inspect the reliable-pipe status channel.
        // The platform exposes the failure only when checkError is explicitly called.
        val descriptor = context.contentResolver.openFileDescriptor(source.uri, "r")!!
        val owner = OwnedFileDescriptor(descriptor)
        val resource = OwnedSourceResource(owner)
        val stream = resource.adopt(FileInputStream(descriptor.fileDescriptor))
        try {
            assertTrue(descriptor.canDetectErrors())
            val buffer = ByteArray(32)
            while (stream.read(buffer) != -1) { /* consume the bounded pipe */ }
            var errorObserved = false
            try { descriptor.checkError() } catch (_: IOException) { errorObserved = true }
            assertTrue("API 23 reliable-pipe error must be observable through checkError", errorObserved)
        } finally {
            resource.close()
        }
        assertEquals(1, owner.closeCount)
        assertEquals(1, resource.derivedCloseCount)

        // No EOF is mistaken for the declared full file: length and SHA-256
        // checks both refuse completion and the test publisher is never called.
        val store = AppPrivatePartialStore(File(context.cacheDir, "slice4s-short-source"))
        val identity = PartialIdentity("app_private:slice4s-short-source")
        val partial = store.open(identity)
        var published = false
        val session = (DestinationSession.open(
            transferId = TransferId("slice4s-short-source"), identity = identity, sink = partial,
            declaredSizeBytes = expected.size.toLong(), verificationSource = partial.verificationSource(),
            publisher = DestinationPublisher { published = true; PublicationOutcome.Published },
            resources = listOf(partial),
        ) as DestinationOpen.Opened).session
        try {
            assertTrue(session.writeAt(0L, received) is DestinationWrite.Written)
            val lengthCheck = DestinationVerifier.verify(partial.verificationSource(), expected.size.toLong(), digest(expected))
            assertTrue(lengthCheck is VerifyResult.Failed)
            assertEquals("verification_source_ended_early", ((lengthCheck as VerifyResult.Failed).error as TransferStorageError.StateConflict).reason)
            assertTrue(session.verify(digest(expected)) is SessionVerification.Mismatched)
            assertTrue(session.commit() is CommitResult.Refused)
            assertFalse(published)
            Log.i("Morsec4S", "reliable_pipe typed=short_eof explicit_checkError=ioexception length_digest=refused")
        } finally {
            session.close()
            assertTrue(store.delete(identity))
        }
    }
}
