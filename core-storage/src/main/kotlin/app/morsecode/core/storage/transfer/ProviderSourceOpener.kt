package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel

/*
 * The shared path from a content or document Uri to a positioned handle.
 *
 * Both MediaStore and SAF sources go through here, because the sequence of
 * decisions between "open the descriptor" and "hand back a handle" is the part
 * where a mistake produces a stream sitting at the wrong offset — which is not a
 * corrupted file, it is a silently *short* one that passes a byte count.
 *
 * The order is fixed:
 *
 *   open descriptor  →  probe capability  →  position  →  validate against length
 *
 * and every failure after the descriptor exists closes it before returning. A
 * caller that gets `Failed` is never left holding a descriptor it has to clean
 * up, and a caller that gets `Opened` owns exactly one handle.
 */

/** What opening a provider-backed source produced. */
public sealed interface ProviderOpenResult {
    public data class Opened(public val handle: SourceHandle, public val offset: Long) : ProviderOpenResult
    public data class Failed(public val error: TransferStorageError) : ProviderOpenResult
}

public object ProviderSourceOpener {

    /**
     * Opens [uri] through [resolver] and returns a handle positioned at [offset].
     *
     * Never returns a handle at an offset it did not reach.
     */
    public fun open(
        resolver: ContentResolver,
        uri: Uri,
        offset: Long,
        probe: SeekabilityProbe = ParcelDescriptorSeekabilityProbe,
        buffer: ByteArray = ByteArray(NonSeekableRepositioner.SKIP_BUFFER_BYTES),
    ): ProviderOpenResult {
        if (offset < 0L) {
            return ProviderOpenResult.Failed(
                TransferStorageError.StateConflict("negative_offset"),
            )
        }

        val descriptor: ParcelFileDescriptor = try {
            resolver.openFileDescriptor(uri, "r")
                ?: return ProviderOpenResult.Failed(
                    TransferStorageError.ProviderFailure(
                        provider = "content_resolver",
                        diagnostic = "openFileDescriptor returned null",
                    ),
                )
        } catch (e: SecurityException) {
            // A grant that was held when the row was read is gone now.
            return ProviderOpenResult.Failed(TransferStorageError.PermissionRevoked("read"))
        } catch (e: FileNotFoundException) {
            // The row was deleted between the metadata query and the open.
            return ProviderOpenResult.Failed(TransferStorageError.NotFound("source"))
        } catch (e: IOException) {
            return ProviderOpenResult.Failed(TransferStorageError.Io("open", e.message))
        } catch (e: IllegalArgumentException) {
            return ProviderOpenResult.Failed(
                TransferStorageError.ProviderFailure("content_resolver", e.message),
            )
        }

        val resource = OwnedSourceResource(OwnedFileDescriptor(descriptor))
        val capability = probe.probe(descriptor)

        return try {
            if (capability.seekability.canSeek) {
                positionSeekable(resource, descriptor, capability, offset)
            } else {
                advanceByDiscarding(resource, descriptor, offset, buffer)
            }
        } catch (e: IOException) {
            resource.close()
            ProviderOpenResult.Failed(TransferStorageError.Io("open", e.message))
        } catch (e: SecurityException) {
            resource.close()
            ProviderOpenResult.Failed(TransferStorageError.PermissionRevoked("read"))
        }
    }

    private fun positionSeekable(
        resource: OwnedSourceResource,
        descriptor: ParcelFileDescriptor,
        capability: ProbeResult,
        offset: Long,
    ): ProviderOpenResult {
        val channel: FileChannel = resource.adopt(FileInputStream(descriptor.fileDescriptor).channel)
        channel.position(offset)

        // The length check only runs when the descriptor was willing to state
        // one. An unknown length means "I cannot validate this offset", not "the
        // offset is wrong", so the open proceeds and a short read surfaces later
        // as end of file rather than as a refusal here.
        val length = capability.sizeBytes
        if (length != null && offset > length) {
            resource.close()
            return ProviderOpenResult.Failed(
                TransferStorageError.StateConflict(
                    reason = "offset_past_end",
                    diagnostic = "offset $offset exceeds length $length",
                ),
            )
        }

        return ProviderOpenResult.Opened(ChannelSourceHandle(resource, channel, offset), offset)
    }

    private fun advanceByDiscarding(
        resource: OwnedSourceResource,
        descriptor: ParcelFileDescriptor,
        offset: Long,
        buffer: ByteArray,
    ): ProviderOpenResult {
        val stream: InputStream = resource.adopt(FileInputStream(descriptor.fileDescriptor))
        return when (val repositioned = NonSeekableRepositioner.reposition(
            stream = SourceStream { target, dataOffset, length -> stream.read(target, dataOffset, length) },
            targetOffset = offset,
            buffer = buffer,
        )) {
            is RepositionResult.Positioned ->
                ProviderOpenResult.Opened(StreamSourceHandle(resource, stream, offset), offset)

            is RepositionResult.EndOfFile -> {
                resource.close()
                ProviderOpenResult.Failed(
                    TransferStorageError.StateConflict(
                        reason = "source_shorter_than_offset",
                        diagnostic = "ended at ${repositioned.discarded} of $offset bytes",
                    ),
                )
            }

            is RepositionResult.Failed -> {
                resource.close()
                ProviderOpenResult.Failed(repositioned.error)
            }
        }
    }
}
