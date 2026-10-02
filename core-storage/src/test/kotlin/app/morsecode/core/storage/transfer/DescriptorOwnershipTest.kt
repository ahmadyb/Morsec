package app.morsecode.core.storage.transfer

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Descriptor ownership: one owner, one close, on every path.
 *
 * The bug this exists to prevent is not a leak, which is merely slow; it is the
 * double close. Once a descriptor is closed the kernel may hand that number to
 * another component, so a second close is not a no-op at the system level even
 * though it is one at the object level — it closes someone else's file.
 *
 * ADR-0003 §5 names six paths that must close exactly once: success, seek
 * failure, read failure, write failure, verification failure and cancellation.
 * The handle types are exercised on the first three here; the destination and
 * verification paths get the same assertion when those adapters land, using the
 * same `OwnedFileDescriptor.closeCount` counter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DescriptorOwnershipTest {

    private fun tempFile(name: String, bytes: Int = 4_096): File {
        val dir = Files.createTempDirectory("ownership-").toFile()
        val file = File(dir, name)
        file.writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        return file
    }

    private fun open(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    @Test
    fun `closing once closes the descriptor exactly once`() {
        val file = tempFile("once.bin")
        val owned = OwnedFileDescriptor(open(file))
        assertTrue(owned.isOpen)
        assertEquals(0, owned.closeCount)

        owned.close()

        assertEquals(1, owned.closeCount)
        assertFalse(owned.isOpen)
        assertEquals(-1, owned.rawFd)
    }

    @Test
    fun `closing twice is a no-op rather than a double close`() {
        val file = tempFile("twice.bin")
        val owned = OwnedFileDescriptor(open(file))

        owned.close()
        owned.close()
        owned.close()

        assertEquals(1, owned.closeCount)
    }

    @Test
    fun `use blocks close even when the body throws`() {
        val file = tempFile("throwing.bin")
        val owned = OwnedFileDescriptor(open(file))

        val thrown = runCatching {
            owned.use { throw IllegalStateException("seek failed") }
        }

        assertTrue(thrown.isFailure)
        assertEquals(1, owned.closeCount)
        assertFalse(owned.isOpen)
    }

    @Test
    fun `closing a resource closes its adopted stream first, then the descriptor`() {
        val file = tempFile("adopted.bin")
        val resource = OwnedSourceResource(OwnedFileDescriptor(open(file)))
        val stream = resource.adopt(FileInputStream(file))

        // The derived stream is lent, not given: reading through it works.
        assertEquals(0, stream.read())

        resource.close()

        assertEquals(1, resource.derivedCloseCount)
        // Reading through a lent stream after the owner closed it must fail
        // loudly rather than silently succeed on a recycled descriptor.
        assertTrue(
            "a closed derived stream must not keep reading",
            runCatching { stream.read() }.isFailure,
        )
    }

    @Test
    fun `closing a resource twice closes the descriptor once`() {
        val file = tempFile("resource.bin")
        val descriptor = OwnedFileDescriptor(open(file))
        val resource = OwnedSourceResource(descriptor)
        resource.adopt(FileInputStream(file))

        resource.close()
        resource.close()

        assertEquals(1, descriptor.closeCount)
        assertEquals(1, resource.derivedCloseCount)
    }

    @Test
    fun `a source handle closes its descriptor on the success path`() {
        val file = tempFile("handle.bin")
        val descriptor = OwnedFileDescriptor(open(file))
        val resource = OwnedSourceResource(descriptor)
        val channel = resource.adopt(FileInputStream(file).channel)
        val handle = ChannelSourceHandle(resource, channel, openedAtOffset = 0L)

        val buffer = ByteArray(16)
        val read = handle.read(buffer, 0, 16)

        assertEquals(16, read)
        assertEquals(16L, handle.bytesRead)
        assertEquals(16L, handle.position)
        assertEquals(0, descriptor.closeCount)

        handle.close()

        assertEquals(1, descriptor.closeCount)
    }

    @Test
    fun `a read failure still leaves the descriptor owned and closable`() {
        val file = tempFile("read-failure.bin")
        val descriptor = OwnedFileDescriptor(open(file))
        val resource = OwnedSourceResource(descriptor)

        val failing = resource.adopt(object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("provider died")
        })
        val handle = StreamSourceHandle(resource, failing, openedAtOffset = 0L)

        assertTrue(runCatching { handle.read(ByteArray(4), 0, 4) }.isFailure)
        assertEquals(0L, handle.bytesRead)

        handle.close()
        assertEquals(1, descriptor.closeCount)
    }

    @Test
    fun `end of file is not counted as progress`() {
        val file = tempFile("eof.bin", bytes = 8)
        val resource = OwnedSourceResource(OwnedFileDescriptor(open(file)))
        val handle = StreamSourceHandle(resource, resource.adopt(FileInputStream(file)), 0L)
        val buffer = ByteArray(8)

        assertEquals(8, handle.read(buffer, 0, 8))
        assertEquals(-1, handle.read(buffer, 0, 8))
        assertEquals(-1, handle.read(buffer, 0, 8))

        // Two end-of-file reads must not inflate the position.
        assertEquals(8L, handle.bytesRead)
        assertEquals(8L, handle.position)
        handle.close()
    }

    @Test
    fun `a zero-length read is recorded as no progress without failing`() {
        val resource = OwnedSourceResource(OwnedFileDescriptor(open(tempFile("zero.bin"))))
        val stalling = resource.adopt(object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        })
        val handle = StreamSourceHandle(resource, stalling, openedAtOffset = 0L)

        assertEquals(0, handle.read(ByteArray(16), 0, 16))
        assertEquals(0L, handle.bytesRead)
        assertEquals(0L, handle.position)
        handle.close()
    }
}
