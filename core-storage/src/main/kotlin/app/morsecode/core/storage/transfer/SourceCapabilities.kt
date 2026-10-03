package app.morsecode.core.storage.transfer

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel

/*
 * Whether a descriptor can be positioned, established by probing rather than
 * assumed.
 *
 * It is tempting to treat every `ParcelFileDescriptor` as seekable: most of the
 * ones an app sees point at a real file, and `FileInputStream(fd).channel` hands
 * back a `FileChannel` whether the descriptor is seekable or not. The channel
 * exists either way. It just fails when you ask it to move.
 *
 * A provider is entitled to hand back any of these, and each one needs a
 * different resume strategy:
 *
 *   * a real file — seekable, size known;
 *   * a pipe or a socket — the channel exists, `position()` throws, and reaching
 *     an offset means reopening and discarding bytes;
 *   * a descriptor whose `size()` throws or lies — seekable, but the size cannot
 *     be used to validate an offset;
 *   * a provider that answers the metadata query and then fails to open at all,
 *     because the row was deleted in between.
 *
 * So the capability is measured on the descriptor that was actually opened, at
 * open time, and the answer is a value the resume path switches on.
 */

/** Whether a source can be positioned directly. */
public enum class Seekability(public val id: String) {
    /** `position()` is accepted. */
    SEEKABLE("seekable"),

    /** `position()` is rejected; reaching an offset requires discarding bytes. */
    NON_SEEKABLE("non_seekable"),

    /** It could not be determined. Treated as non-seekable, which always works. */
    UNKNOWN("unknown"),
    ;

    /** True only when a direct position is known to work. */
    public val canSeek: Boolean get() = this == SEEKABLE

    public companion object {
        public fun fromId(id: String?): Seekability =
            entries.firstOrNull { it.id == id } ?: UNKNOWN
    }
}

/** What the probe actually observed. Carried for diagnostics and for tests. */
public enum class ProbeEvidence(public val id: String) {
    /** `position()` returned; the descriptor is seekable. */
    POSITION_ACCEPTED("position_accepted"),

    /** `position()` threw. The channel exists but the descriptor cannot move. */
    POSITION_REJECTED("position_rejected"),

    /** Seekable, but `size()` threw so the length is not available. */
    SIZE_UNAVAILABLE("size_unavailable"),

    /** No channel could be obtained from the descriptor at all. */
    NO_CHANNEL("no_channel"),

    /** The descriptor itself was not usable. */
    DESCRIPTOR_UNUSABLE("descriptor_unusable"),
    ;

    public companion object {
        public fun fromId(id: String?): ProbeEvidence =
            entries.firstOrNull { it.id == id } ?: DESCRIPTOR_UNUSABLE
    }
}

/** The answer. */
public data class ProbeResult(
    public val seekability: Seekability,
    /** Length in bytes, or null when the descriptor will not say. */
    public val sizeBytes: Long?,
    public val evidence: ProbeEvidence,
) {
    /** True when an offset can be validated against a known length. */
    public val sizeIsKnown: Boolean get() = sizeBytes != null

    public companion object {
        /** Used whenever a descriptor cannot be obtained at all. */
        public val UNAVAILABLE: ProbeResult = ProbeResult(
            seekability = Seekability.UNKNOWN,
            sizeBytes = null,
            evidence = ProbeEvidence.DESCRIPTOR_UNUSABLE,
        )
    }
}

/** The seam, so a test can force any of the five provider shapes. */
public fun interface SeekabilityProbe {
    public fun probe(descriptor: ParcelFileDescriptor): ProbeResult
}

/**
 * Probes a real descriptor.
 *
 * Deliberately asks for position 0 rather than the requested offset: the probe
 * answers "can this descriptor move at all", which is a property of the
 * descriptor, while a particular offset failing is a property of the file and is
 * reported as an error by the opener.
 */
public object ParcelDescriptorSeekabilityProbe : SeekabilityProbe {

    /** The offset the probe seeks to. Always zero: the question is whether it can. */
    public const val PROBE_OFFSET: Long = 0L

    override fun probe(descriptor: ParcelFileDescriptor): ProbeResult {
        val channel: FileChannel = try {
            FileInputStream(descriptor.fileDescriptor).channel
        } catch (e: IOException) {
            return ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.NO_CHANNEL)
        } catch (e: IllegalArgumentException) {
            return ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.NO_CHANNEL)
        } catch (e: SecurityException) {
            return ProbeResult(Seekability.UNKNOWN, null, ProbeEvidence.NO_CHANNEL)
        }

        return try {
            channel.position(PROBE_OFFSET)
        } catch (e: IOException) {
            return ProbeResult(Seekability.NON_SEEKABLE, null, ProbeEvidence.POSITION_REJECTED)
        } catch (e: IllegalArgumentException) {
            return ProbeResult(Seekability.NON_SEEKABLE, null, ProbeEvidence.POSITION_REJECTED)
        } catch (e: UnsupportedOperationException) {
            return ProbeResult(Seekability.NON_SEEKABLE, null, ProbeEvidence.POSITION_REJECTED)
        }

        val size = try {
            channel.size()
        } catch (e: IOException) {
            null
        }

        return if (size == null) {
            ProbeResult(Seekability.SEEKABLE, null, ProbeEvidence.SIZE_UNAVAILABLE)
        } else {
            ProbeResult(Seekability.SEEKABLE, size, ProbeEvidence.POSITION_ACCEPTED)
        }
    }
}
