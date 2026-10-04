package app.morsecode.core.storage.transfer

import android.content.ContentResolver
import android.net.Uri

/*
 * A tier a test supplies, standing in for a device.
 *
 * Deliberately not parameterised by an SDK number. The design this replaced
 * let a test set an integer and have production code branch on it, which meant
 * a test could direct production at a symbol the running device does not have.
 * A fake describes the answers a tier would give; it does not describe a device.
 *
 * It lives in test sources because nothing in the shipped app should be able to
 * construct it.
 */
class FakeSafPlatformOperations(
    override val tier: SafContainmentTier = SafContainmentTier.CHILD_DOCUMENT,
    override val canAskProvider: Boolean = true,
    private val childAnswer: SafChildAnswer = SafChildAnswer.Indeterminate,
    private val pathAnswer: SafPathAnswer = SafPathAnswer.Indeterminate,
) : SafPlatformOperations {

    var isChildDocumentCalls: Int = 0
        private set

    var documentPathCalls: Int = 0
        private set

    override fun isChildDocument(
        resolver: ContentResolver,
        parentDocumentUri: Uri,
        childDocumentUri: Uri,
    ): SafChildAnswer {
        isChildDocumentCalls++
        return childAnswer
    }

    override fun documentPath(
        resolver: ContentResolver,
        documentUri: Uri,
    ): SafPathAnswer {
        documentPathCalls++
        return pathAnswer
    }
}
