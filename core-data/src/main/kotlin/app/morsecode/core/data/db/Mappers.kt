package app.morsecode.core.data.db

import app.morsecode.core.model.BrowserSession
import app.morsecode.core.model.BrowserSessionState
import app.morsecode.core.model.CrashReport
import app.morsecode.core.model.DeviceKind
import app.morsecode.core.model.HistoryEntry
import app.morsecode.core.model.LogEntry
import app.morsecode.core.model.LogLevel
import app.morsecode.core.model.MediaKind
import app.morsecode.core.model.Peer
import app.morsecode.core.model.RecentDevice
import app.morsecode.core.model.SafGrant
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.SessionPhase
import app.morsecode.core.model.TransferItem
import app.morsecode.core.model.TransferSession
import app.morsecode.core.model.TransferState
import app.morsecode.core.model.TransportKind
import app.morsecode.core.model.WebTransferRecord
import app.morsecode.core.model.WebTransferState

/*
 * Entity <-> domain mapping.
 *
 * Enums are stored by their stable `id` (or `name` where no id exists) so a
 * Kotlin rename can never invalidate a persisted resumable offset. Parsing is
 * always tolerant: an unknown value resolves through the model's `fromId`
 * fallback instead of throwing inside a Room callback.
 */

public fun TransferSessionEntity.toPeer(): Peer = Peer(
    peerId = peerId,
    displayName = peerName,
    endpointId = peerEndpoint,
    transport = TransportKind.fromId(peerTransport) ?: TransportKind.LAN,
    deviceKind = DeviceKind.fromId(peerDeviceKind),
    detail = peerDetail,
    appVersion = peerAppVersion,
    supportsResume = peerSupportsResume,
    supportsEncryption = peerSupportsEncryption,
    lastSeenEpochMillis = startedEpochMillis,
)

public fun TransferSessionEntity.toDomain(): TransferSession = TransferSession(
    sessionId = sessionId,
    peer = toPeer(),
    phase = SessionPhase.fromId(phase),
    broadcastId = broadcastId,
    startedEpochMillis = startedEpochMillis,
    endedEpochMillis = endedEpochMillis,
    pauseAll = pauseAll,
    failureReason = failureReason,
)

public fun TransferSession.toEntity(): TransferSessionEntity = TransferSessionEntity(
    sessionId = sessionId,
    peerId = peer.peerId,
    peerName = peer.displayName,
    peerEndpoint = peer.endpointId,
    peerTransport = peer.transport.id,
    peerDeviceKind = peer.deviceKind.id,
    peerDetail = peer.detail,
    peerAppVersion = peer.appVersion,
    peerSupportsResume = peer.supportsResume,
    peerSupportsEncryption = peer.supportsEncryption,
    broadcastId = broadcastId,
    phase = phase.id,
    pauseAll = pauseAll,
    startedEpochMillis = startedEpochMillis,
    endedEpochMillis = endedEpochMillis,
    failureReason = failureReason,
)

public fun TransferItemEntity.toDomain(): TransferItem = TransferItem(
    transferId = transferId,
    sessionId = sessionId,
    batchId = batchId,
    direction = SessionDirection.fromId(direction),
    displayName = displayName,
    relativePath = relativePath,
    mimeType = mimeType,
    kind = MediaKind.fromId(kind),
    totalBytes = totalBytes,
    lastModifiedEpochMillis = lastModifiedEpochMillis,
    isFolderArchive = isFolderArchive,
    state = TransferState.fromId(state),
    confirmedBytes = confirmedBytes,
    bytesPerSecond = bytesPerSecond,
    sha256Hex = sha256Hex,
    failureReason = failureReason,
    retryCount = retryCount,
    queuedEpochMillis = queuedEpochMillis,
    finishedEpochMillis = finishedEpochMillis,
    resultUriString = resultUriString,
    recipientPeerId = recipientPeerId,
)

/**
 * @param sourceUriString where the bytes come from on this device; null inbound.
 * @param targetDirectory where inbound bytes are committed; null outbound.
 * @param queuePosition ordering inside the session queue.
 */
public fun TransferItem.toEntity(
    sourceUriString: String? = null,
    targetDirectory: String? = null,
    queuePosition: Int = 0,
): TransferItemEntity = TransferItemEntity(
    transferId = transferId,
    sessionId = sessionId,
    batchId = batchId,
    direction = direction.id,
    displayName = displayName,
    relativePath = relativePath,
    mimeType = mimeType,
    kind = kind.id,
    totalBytes = totalBytes,
    lastModifiedEpochMillis = lastModifiedEpochMillis,
    isFolderArchive = isFolderArchive,
    state = state.id,
    confirmedBytes = confirmedBytes,
    bytesPerSecond = bytesPerSecond,
    sha256Hex = sha256Hex,
    failureReason = failureReason,
    retryCount = retryCount,
    queuedEpochMillis = queuedEpochMillis,
    finishedEpochMillis = finishedEpochMillis,
    resultUriString = resultUriString,
    recipientPeerId = recipientPeerId,
    sourceUriString = sourceUriString,
    targetDirectory = targetDirectory,
    queuePosition = queuePosition,
)

public fun HistoryEntryEntity.toDomain(): HistoryEntry = HistoryEntry(
    historyId = historyId,
    transferId = transferId,
    sessionId = sessionId,
    direction = SessionDirection.fromId(direction),
    displayName = displayName,
    mimeType = mimeType,
    kind = MediaKind.fromId(kind),
    totalBytes = totalBytes,
    peerId = peerId,
    peerName = peerName,
    state = TransferState.fromId(state),
    failureReason = failureReason,
    sha256Hex = sha256Hex,
    finishedEpochMillis = finishedEpochMillis,
    resultUriString = resultUriString,
    broadcastId = broadcastId,
)

public fun HistoryEntry.toEntity(): HistoryEntryEntity = HistoryEntryEntity(
    historyId = historyId,
    transferId = transferId,
    sessionId = sessionId,
    direction = direction.id,
    displayName = displayName,
    mimeType = mimeType,
    kind = kind.id,
    totalBytes = totalBytes,
    peerId = peerId,
    peerName = peerName,
    state = state.id,
    failureReason = failureReason,
    sha256Hex = sha256Hex,
    finishedEpochMillis = finishedEpochMillis,
    resultUriString = resultUriString,
    broadcastId = broadcastId,
)

public fun RecentDeviceEntity.toDomain(): RecentDevice = RecentDevice(
    peerId = peerId,
    displayName = displayName,
    transport = TransportKind.fromId(transport) ?: TransportKind.LAN,
    deviceKind = DeviceKind.fromId(deviceKind),
    detail = detail,
    lastSeenEpochMillis = lastSeenEpochMillis,
    lastSummary = lastSummary,
    interactionCount = interactionCount,
)

public fun RecentDevice.toEntity(): RecentDeviceEntity = RecentDeviceEntity(
    peerId = peerId,
    displayName = displayName,
    transport = transport.id,
    deviceKind = deviceKind.id,
    detail = detail,
    lastSeenEpochMillis = lastSeenEpochMillis,
    lastSummary = lastSummary,
    interactionCount = interactionCount,
)

public fun LogEntryEntity.toDomain(): LogEntry = LogEntry(
    id = id,
    timestampEpochMillis = timestamp,
    level = LogLevel.fromId(level),
    tag = tag,
    message = message,
    errorId = errorId,
)

public fun LogEntry.toEntity(): LogEntryEntity = LogEntryEntity(
    id = id,
    timestamp = timestampEpochMillis,
    level = level.id,
    tag = tag,
    message = message,
    errorId = errorId,
)

public fun CrashReportEntity.toDomain(): CrashReport = CrashReport(
    id = id,
    occurredEpochMillis = occurredEpochMillis,
    component = component,
    exceptionType = exceptionType,
    message = message,
    stackTrace = stackTrace,
    appVersion = appVersion,
    versionCode = versionCode,
    androidSdkInt = androidSdkInt,
    deviceModel = deviceModel,
    recoveryNote = recoveryNote,
)

public fun CrashReport.toEntity(): CrashReportEntity = CrashReportEntity(
    id = id,
    occurredEpochMillis = occurredEpochMillis,
    component = component,
    exceptionType = exceptionType,
    message = message,
    stackTrace = stackTrace,
    appVersion = appVersion,
    versionCode = versionCode,
    androidSdkInt = androidSdkInt,
    deviceModel = deviceModel,
    recoveryNote = recoveryNote,
)

public fun BrowserSessionEntity.toDomain(): BrowserSession = BrowserSession(
    sessionId = sessionId,
    tokenDigest = tokenDigest,
    userAgent = userAgent,
    remoteAddress = remoteAddress,
    state = BrowserSessionState.fromId(state),
    requestedEpochMillis = requestedEpochMillis,
    acceptedEpochMillis = acceptedEpochMillis,
    revokedEpochMillis = revokedEpochMillis,
    lastActivityEpochMillis = lastActivityEpochMillis,
    bytesDownloaded = bytesDownloaded,
    bytesUploaded = bytesUploaded,
    requestCount = requestCount,
)

public fun BrowserSession.toEntity(): BrowserSessionEntity = BrowserSessionEntity(
    sessionId = sessionId,
    tokenDigest = tokenDigest,
    userAgent = userAgent,
    remoteAddress = remoteAddress,
    state = state.id,
    requestedEpochMillis = requestedEpochMillis,
    acceptedEpochMillis = acceptedEpochMillis,
    revokedEpochMillis = revokedEpochMillis,
    lastActivityEpochMillis = lastActivityEpochMillis,
    bytesDownloaded = bytesDownloaded,
    bytesUploaded = bytesUploaded,
    requestCount = requestCount,
)

public fun SafGrantEntity.toDomain(): SafGrant = SafGrant(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    grantedEpochMillis = grantedEpochMillis,
    readWrite = readWrite,
)

public fun SafGrant.toEntity(): SafGrantEntity = SafGrantEntity(
    id = id,
    treeUri = treeUri,
    displayName = displayName,
    grantedEpochMillis = grantedEpochMillis,
    readWrite = readWrite,
)

public fun WebTransferEntity.toDomain(): WebTransferRecord = WebTransferRecord(
    uploadId = uploadId,
    browserSessionId = browserSessionId,
    fileName = fileName,
    targetDirectory = targetDirectory,
    totalBytes = totalBytes,
    receivedBytes = receivedBytes,
    partialPath = partialPath,
    state = WebTransferState.fromId(state),
    updatedEpochMillis = updatedEpochMillis,
)

public fun WebTransferRecord.toEntity(): WebTransferEntity = WebTransferEntity(
    uploadId = uploadId,
    browserSessionId = browserSessionId,
    fileName = fileName,
    targetDirectory = targetDirectory,
    totalBytes = totalBytes,
    receivedBytes = receivedBytes,
    partialPath = partialPath,
    state = state.id,
    updatedEpochMillis = updatedEpochMillis,
)
