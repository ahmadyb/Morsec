package app.morsecode.core.transfer.model

import app.morsecode.core.transfer.ProtocolLimits
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.integrity.Sha256Accumulator
import app.morsecode.core.transfer.integrity.Sha256Digest

/*
 * What one transfer is moving.
 *
 * A descriptor is the thing both peers must agree on before a single byte is
 * sent, and the thing resume re-checks before it trusts a stored offset. It is
 * therefore validated in full at construction and reduced to one comparable
 * [descriptorFingerprint]: if the fingerprints differ, the stored partial file
 * belongs to a different file and must not be resumed.
 *
 * There is intentionally no absolute path here. A descriptor names content
 * ([fileId]) and a safe location inside the batch ([relativePath]); turning that
 * into a real source or destination is a storage-adapter concern.
 */

private val DISPLAY_NAME_CONTROL = Regex("""\p{Cntrl}""")
private val MIME_TYPE = Regex("""[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+""")

/**
 * Everything the receiver needs before it can decide whether to accept, and
 * everything resume needs before it can trust a stored offset.
 */
public data class TransferFileDescriptor(
    /** Stable identity of the content; one content, many deliveries. */
    public val fileId: FileId,

    /** Name shown in the UI. Never a path: no separator may appear in it. */
    public val displayName: String,

    /** Where the file sits inside the batch; the only location the core knows. */
    public val relativePath: RelativeTransferPath,

    /** MIME type, or empty when the source could not determine one. */
    public val mimeType: String,

    /** Exact size in bytes; 0 is legal and follows the zero-byte path. */
    public val totalBytes: Long,

    /** Last-modified time in epoch milliseconds, or null when unavailable. */
    public val lastModifiedEpochMillis: Long?,

    /** True for a folder streamed as an archive. */
    public val isFolderArchive: Boolean,

    /** Full-file SHA-256 when the sender already knows it; null otherwise. */
    public val expectedSha256: Sha256Digest?,

    /** Chunk size this transfer will use. */
    public val chunkSize: ChunkSize,

    /** Protocol version this descriptor was produced by. */
    public val protocolVersion: ProtocolVersion,
) {
    init {
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        require(nameBytes.isNotEmpty()) { "displayName must not be empty" }
        require(nameBytes.size <= ProtocolLimits.MAX_TEXT_LENGTH_BYTES) {
            "displayName must not exceed ${ProtocolLimits.MAX_TEXT_LENGTH_BYTES} UTF-8 bytes"
        }
        require(!DISPLAY_NAME_CONTROL.containsMatchIn(displayName)) {
            "displayName must not contain control characters"
        }
        require(!displayName.contains('/') && !displayName.contains('\\')) {
            "displayName must not contain a path separator"
        }
        require(displayName != "." && displayName != "..") {
            "displayName must not be a traversal marker"
        }
        val mimeBytes = mimeType.toByteArray(Charsets.UTF_8)
        require(mimeBytes.size <= ProtocolLimits.MAX_TEXT_LENGTH_BYTES) {
            "mimeType must not exceed ${ProtocolLimits.MAX_TEXT_LENGTH_BYTES} UTF-8 bytes"
        }
        require(mimeType.isEmpty() || MIME_TYPE.matches(mimeType)) {
            "mimeType must be empty or of the form type/subtype"
        }
        require(totalBytes >= 0L) { "totalBytes must not be negative, was $totalBytes" }
        require(totalBytes <= ProtocolLimits.MAX_FILE_SIZE_BYTES) {
            "totalBytes $totalBytes exceeds the maximum ${ProtocolLimits.MAX_FILE_SIZE_BYTES}"
        }
        require(lastModifiedEpochMillis == null || lastModifiedEpochMillis >= 0L) {
            "lastModifiedEpochMillis must not be negative, was $lastModifiedEpochMillis"
        }
        // `protocolVersion` is range-checked by its own constructor; whether a
        // peer's version is acceptable is a negotiation decision, not a
        // construction-time rejection.
    }

    /** Number of DATA_CHUNK frames a complete transfer of this file needs. */
    public val chunkCount: Long get() = chunkSize.chunkCountFor(totalBytes)

    /** True when the file is empty, which takes the zero-byte verification path. */
    public val isEmpty: Boolean get() = totalBytes == 0L

    /**
     * Canonical bytes the fingerprint is taken over.
     *
     * Built from fields in a fixed order with explicit separators, so the same
     * file described by two builds of the app produces the same fingerprint.
     */
    public fun canonicalBytes(): ByteArray = buildString {
        append(fileId.value).append('\n')
        append(relativePath.value).append('\n')
        append(displayName).append('\n')
        append(mimeType).append('\n')
        append(totalBytes).append('\n')
        append(lastModifiedEpochMillis ?: -1L).append('\n')
        append(if (isFolderArchive) 1 else 0).append('\n')
        append(chunkSize.value).append('\n')
        append(expectedSha256?.hex ?: "-").append('\n')
    }.toByteArray(Charsets.UTF_8)

    /**
     * 64-character hex digest of [canonicalBytes].
     *
     * Resume compares this against the fingerprint stored with the partial file:
     * a mismatch means "this partial file is not that file" and forces a restart
     * from zero rather than a silently corrupted append.
     */
    public val descriptorFingerprint: String by lazy {
        Sha256Accumulator().update(canonicalBytes()).digest().hex
    }

    /** A copy that adopts a different chunk size after negotiation. */
    public fun withChunkSize(chunkSize: ChunkSize): TransferFileDescriptor = copy(chunkSize = chunkSize)

    public companion object {
        /**
         * Non-throwing construction for untrusted (peer-supplied) input.
         *
         * Returns null instead of throwing so the codec can turn a bad
         * descriptor into a typed `TransferError` rather than an exception.
         */
        public fun tryCreate(
            fileId: FileId,
            displayName: String,
            relativePath: RelativeTransferPath,
            mimeType: String,
            totalBytes: Long,
            lastModifiedEpochMillis: Long?,
            isFolderArchive: Boolean,
            expectedSha256: Sha256Digest?,
            chunkSize: ChunkSize,
            protocolVersion: ProtocolVersion,
        ): TransferFileDescriptor? = try {
            TransferFileDescriptor(
                fileId = fileId,
                displayName = displayName,
                relativePath = relativePath,
                mimeType = mimeType,
                totalBytes = totalBytes,
                lastModifiedEpochMillis = lastModifiedEpochMillis,
                isFolderArchive = isFolderArchive,
                expectedSha256 = expectedSha256,
                chunkSize = chunkSize,
                protocolVersion = protocolVersion,
            )
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
