package app.morsecode.core.storage.transfer

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.storage.permissions.PermissionMatrix
import app.morsecode.core.transfer.identity.RelativeTransferPath
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * The API 23-28 shared-storage source, and the three refusals that make it safe.
 *
 * The dangerous version of this class is the one that quietly keeps working after
 * scoped storage arrives, because it would then be a path around MediaStore and
 * SAF. So the level check is tested as a refusal, not as a fallback: on API 29+
 * the source is not "found but unusable", it is absent.
 *
 * The other two refusals are containment and the runtime permission. The
 * permission is granted and withheld through the real permission check rather
 * than a stub, because what has to be proven is that a withdrawn grant produces
 * a typed permission failure and not a transfer that reads nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LegacyFileTransferSourcesTest {

    private lateinit var context: Application
    private lateinit var root: File
    private val relative = RelativeTransferPath("Download/clip.mp4")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = Files.createTempDirectory("legacy-root-").toFile()
        Shadows.shadowOf(context).denyPermissions(*PermissionMatrix.mediaRead(LEGACY_SDK).toTypedArray())
    }

    private fun sources(sdk: Int = LEGACY_SDK): LegacyFileTransferSources =
        LegacyFileTransferSources.create(context = context, roots = listOf(root), sdk = sdk)

    private fun grant() {
        Shadows.shadowOf(context).grantPermissions(*PermissionMatrix.mediaRead(LEGACY_SDK).toTypedArray())
    }

    private fun seed(name: String = "clip.mp4", size: Int = 4_096): File {
        val dir = File(root, "Download").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }
    }

    // --- availability -------------------------------------------------------------------

    @Test
    fun `direct shared storage is available up to API 28`() {
        assertTrue(sources(LEGACY_SDK).isAvailable)
    }

    @Test
    fun `it is refused outright from API 29, where the roots are no longer readable`() {
        seed()
        val scoped = sources(FIRST_SCOPED_SDK)
        assertFalse("scoped storage must not fall back to paths", scoped.isAvailable)
        // Not "found but unusable": there is nothing to even attempt.
        assertNull(scoped.create(relative))
        assertFalse(scoped.contains(relative))
    }

    @Test
    fun `with no approved roots there is nothing to read`() {
        val empty = LegacyFileTransferSources.create(context = context, roots = emptyList(), sdk = LEGACY_SDK)
        assertFalse(empty.isAvailable)
        assertNull(empty.create(relative))
    }

    // --- containment --------------------------------------------------------------------------

    @Test
    fun `a file inside an approved root is found and readable`() {
        grant()
        val file = seed()
        val source = sources().create(relative)

        assertNotNull(source)
        val handle = (source!!.openAtZero() as SourceOpenResult.Opened).handle
        val buffer = ByteArray(16)
        assertEquals(16, handle.read(buffer, 0, 16))
        assertTrue(file.readBytes().take(16).toByteArray().contentEquals(buffer))
        handle.close()
    }

    @Test
    fun `a traversal cannot be expressed as a relative path at all`() {
        grant()
        seed()

        // Guarded at the type, so it cannot reach the filesystem layer.
        assertFalse(RelativeTransferPath.isValid("Download/../../outside.mp4"))
        assertNull(RelativeTransferPath.orNull("Download/../../outside.mp4"))
        assertFalse(
            "a traversal must resolve outside the root",
            ApprovedRoots.isInside(root, File(root, "Download/../../outside.mp4")),
        )
    }

    @Test
    fun `an absolute path is refused`() {
        grant()
        seed()

        assertFalse(RelativeTransferPath.isValid("/etc/passwd"))
        assertNull(RelativeTransferPath.orNull("/etc/passwd"))
        assertFalse(ApprovedRoots.isInside(root, File("/etc/passwd")))
    }

    @Test
    fun `a file that is not in the approved root is reported as missing, not as unapproved`() {
        grant()
        // The file lives under some other root. The same relative path still
        // *resolves* inside the approved one, because approval is about roots
        // and not about existence — so the honest outcome is a typed NotFound
        // at use time rather than a refusal to build a source.
        val elsewhere = Files.createTempDirectory("not-approved-").toFile()
        val dir = File(elsewhere, "Download").apply { mkdirs() }
        File(dir, "clip.mp4").writeBytes(ByteArray(64))

        val source = sources().create(relative)
        assertNotNull(source)
        assertTrue(source!!.fingerprint() is SourceFingerprintResult.Unavailable)

        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.NOT_FOUND,
            (opened as SourceOpenResult.Failed).error.category,
        )
    }

    // --- the runtime permission ------------------------------------------------------------------

    @Test
    fun `a missing runtime permission is a revoked permission, not a missing file`() {
        seed()
        val source = sources().create(relative)
        assertNotNull("the source still exists; it is the grant that is gone", source)

        // Distinct from NotFound on purpose: a missing grant is a recoverable
        // "ask the user" state, a missing file is a restart.
        assertTrue(source!!.fingerprint() is SourceFingerprintResult.Unavailable)
        val opened = source.openAtZero()
        assertTrue(opened is SourceOpenResult.Failed)
        assertEquals(
            TransferStorageErrorCategory.PERMISSION_REVOKED,
            (opened as SourceOpenResult.Failed).error.category,
        )
    }

    @Test
    fun `granting the permission makes the same source readable`() {
        seed()
        val source = sources().create(relative)!!
        assertTrue(source.fingerprint() is SourceFingerprintResult.Unavailable)

        // Re-checked on every use, so a grant acquired after the source was
        // built takes effect without rebuilding anything.
        grant()
        assertTrue(source.fingerprint() is SourceFingerprintResult.Available)
        assertTrue(source.openAtZero() is SourceOpenResult.Opened)
    }

    @Test
    fun `the permission the level requires is the one that is checked`() {
        // API 23-28 asks for READ_EXTERNAL_STORAGE; the API 33+ media split does
        // not apply and must not be demanded here.
        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), PermissionMatrix.mediaRead(LEGACY_SDK))
    }

    // --- what the source does not expose ------------------------------------------------

    @Test
    fun `the source exposes no absolute path`() {
        grant()
        seed()
        val source = sources().create(relative)!!

        val rendered = source.uri.toString()
        assertFalse("a legacy source must not expose its path: $rendered", rendered.contains(root.path))
        assertFalse(source.key.value.contains(root.path))
        assertFalse(source.key.value.contains("/storage"))
    }

    private companion object {
        const val LEGACY_SDK = 28
        const val FIRST_SCOPED_SDK = 29
    }
}
