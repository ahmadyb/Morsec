package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.annotation.RequiresApi

/*
 * The platform questions a device can answer, split by the API level that
 * introduced them.
 *
 * `isChildDocument` arrived in API 29 and `findDocumentPath` in API 26. On an
 * API 23 device neither symbol exists, and the difference between "the provider
 * said no" and "this device cannot ask" is the whole point: the first is
 * containment evidence, the second is not evidence at all.
 *
 * The original shape of this was one class with an injected `sdkInt` integer
 * and an `if` in front of each call. That is unsafe in a way that is easy to
 * miss. The integer is not the device, so anyone able to construct the object
 * -- a test, a future caller, a refactor that threads the wrong value through
 * -- could direct production code at a symbol the running device does not have.
 * The result is not a typed failure but a linkage error at the point of the
 * call, which no error mapping downstream can catch.
 *
 * So the tier is now a type. Each implementation only contains calls its own
 * minimum API supports, each is annotated with that minimum, and the tiers are
 * selected once from Build.VERSION.SDK_INT. A caller cannot ask for API 29
 * behaviour on an API 23 device because there is no object to ask it of; the
 * wrong tier is not a value that can be passed, it is a type that is not
 * reachable.
 *
 * Tests inject a fake implementation of this interface. That is safe in a way
 * the injected integer was not: a fake can decide what to answer, but it cannot
 * make production code reference a symbol the device lacks, because the fake
 * replaces the whole tier instead of only the number that selects it.
 */

/** The platform operations available at a given API level. */
public interface SafPlatformOperations {

    /** Which tier this implementation belongs to. */
    public val tier: SafContainmentTier

    /**
     * Whether this tier can ask the provider anything about containment at all.
     *
     * False below API 26, where the only containment available is the kind the
     * app constructs for itself: a document is inside the grant because this
     * app created it under the grant's own root, and the record of that is
     * ours, not the provider's.
     */
    public val canAskProvider: Boolean

    public fun isChildDocument(
        resolver: ContentResolver,
        parentDocumentUri: Uri,
        childDocumentUri: Uri,
    ): SafChildAnswer

    public fun documentPath(
        resolver: ContentResolver,
        documentUri: Uri,
    ): SafPathAnswer

    public companion object {

        /**
         * The operations for the device this is running on.
         *
         * Branches on Build.VERSION.SDK_INT directly rather than accepting an
         * SDK argument, so the guard is the real one and lint can see it. A
         * caller that wants a specific tier for a test should construct that
         * tier, or a fake, and inject it -- not pass a number here.
         */
        @JvmStatic
        public fun create(): SafPlatformOperations = when {
            Build.VERSION.SDK_INT >= 29 -> SafApi29Operations
            Build.VERSION.SDK_INT >= 26 -> SafApi26Operations
            else -> SafApi23Operations
        }
    }
}

/**
 * API 23 to 25: nothing to ask.
 *
 * Containment here is grant-scoped and internally constructed. A document is
 * treated as inside the grant when this app created it under the grant's own
 * root document and holds the record of having done so. That is a weaker claim
 * than a provider saying yes, and it is reported as one: [isChildDocument] and
 * [documentPath] both answer [Indeterminate], never a negative, because a
 * device that cannot ask has not been told no.
 */
public object SafApi23Operations : SafPlatformOperations {
    override val tier: SafContainmentTier get() = SafContainmentTier.CANONICAL_ONLY

    override val canAskProvider: Boolean get() = false

    override fun isChildDocument(
        resolver: ContentResolver,
        parentDocumentUri: Uri,
        childDocumentUri: Uri,
    ): SafChildAnswer = SafChildAnswer.Indeterminate

    override fun documentPath(
        resolver: ContentResolver,
        documentUri: Uri,
    ): SafPathAnswer = SafPathAnswer.Indeterminate
}

/**
 * API 26 to 28: the provider can describe a path.
 *
 * `findDocumentPath` gives the chain of ids from a root down to the document,
 * which is real evidence when the provider fills it in. It still cannot answer
 * the direct parent/child question, so [isChildDocument] stays indeterminate
 * rather than being approximated from the path -- an inferred "is a child"
 * would be this app's guess wearing the provider's authority.
 */
public object SafApi26Operations : SafPlatformOperations {
    override val tier: SafContainmentTier get() = SafContainmentTier.DOCUMENT_PATH

    override val canAskProvider: Boolean get() = true

    override fun isChildDocument(
        resolver: ContentResolver,
        parentDocumentUri: Uri,
        childDocumentUri: Uri,
    ): SafChildAnswer = SafChildAnswer.Indeterminate

    @RequiresApi(26)
    override fun documentPath(
        resolver: ContentResolver,
        documentUri: Uri,
    ): SafPathAnswer = try {
        val path = DocumentsContract.findDocumentPath(resolver, documentUri)
            ?: return SafPathAnswer.Indeterminate
        val segments = path.path
        if (segments.isNullOrEmpty()) {
            SafPathAnswer.Indeterminate
        } else {
            // getRootId is documented to return null here; passed through as-is
            // so the resolver can skip the comparison when it is null.
            SafPathAnswer.Resolved(rootId = path.rootId, segments = segments)
        }
    } catch (e: SecurityException) {
        SafPathAnswer.Revoked
    } catch (e: Exception) {
        SafPathAnswer.Indeterminate
    }
}

/**
 * API 29 and above: the provider can answer directly.
 *
 * `isChildDocument` is the strongest containment evidence the platform offers,
 * and an explicit "no" from it is final -- the containment layer does not fall
 * back to comparing id strings afterwards.
 */
public object SafApi29Operations : SafPlatformOperations {
    override val tier: SafContainmentTier get() = SafContainmentTier.CHILD_DOCUMENT

    override val canAskProvider: Boolean get() = true

    @RequiresApi(29)
    override fun isChildDocument(
        resolver: ContentResolver,
        parentDocumentUri: Uri,
        childDocumentUri: Uri,
    ): SafChildAnswer = try {
        SafChildAnswer.Answered(
            DocumentsContract.isChildDocument(resolver, parentDocumentUri, childDocumentUri),
        )
    } catch (e: SecurityException) {
        SafChildAnswer.Revoked
    } catch (e: Exception) {
        SafChildAnswer.Indeterminate
    }

    /** Delegated: API 29 can do everything API 26 can. */
    @RequiresApi(26)
    override fun documentPath(
        resolver: ContentResolver,
        documentUri: Uri,
    ): SafPathAnswer = SafApi26Operations.documentPath(resolver, documentUri)
}
