package app.morsecode.core.storage.transfer

import android.content.ContentResolver

/*
 * How a production commit gets the gateway it acts through.
 *
 * Before this existed the coordinator had a constructor, a gateway parameter and
 * no caller. Nothing constructed it, nothing tested it, and the gateway
 * parameter might as well have been a comment: a correct coordinator that is
 * never wired up proves nothing about whether it runs.
 *
 * The rule this enforces is that the production path is the one that names the
 * real Android gateway. A test can still inject a recording or a failing
 * gateway, because the coordinator takes [SafDocumentGateway] and always will --
 * but a test reaches that by passing one in, not by the production path quietly
 * choosing a stand-in.
 *
 * Nothing here can fall back to a fake. There is no branch that inspects the
 * environment, no "if we are in a test" and no default that substitutes a
 * double when the real thing is unavailable. A commit that cannot reach a real
 * provider fails with a typed error, which is the only honest outcome.
 */

/**
 * Builds a [SafCommitCoordinator] wired to the real platform.
 *
 * The gateway is constructed from the supplied [resolver] and takes its API tier
 * from the device, not from anything passed in here. Provider, root and grant
 * dependencies are supplied by the caller as part of the [SafCommitRecord], so
 * nothing about a particular tree is baked into the coordinator.
 */
public object SafCommitCoordinatorFactory {

    /**
     * The production coordinator over [resolver].
     *
     * [allowVisibleFinalCopy] is product policy, not a capability: setting it
     * true does not make a provider capable of anything, it only permits the
     * coordinator to use the strategy where a rename is unavailable.
     */
    public fun create(
        resolver: ContentResolver,
        staging: SafStaging,
        allowVisibleFinalCopy: Boolean = false,
        copyBufferBytes: Int = SafCopyStreamer.COPY_BUFFER_BYTES,
        isCancelled: () -> Boolean = { false },
    ): SafCommitCoordinator = SafCommitCoordinator(
        gateway = DocumentsContractSafGateway(resolver),
        staging = staging,
        allowVisibleFinalCopy = allowVisibleFinalCopy,
        copyBufferBytes = copyBufferBytes,
        isCancelled = isCancelled,
    )
}
