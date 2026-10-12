package app.morsecode.core.transfer.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed control-I/O context that `CONTROL_TIMEOUT` alone could not express.
 *
 * Before this existed every control-channel deadline collapsed to `SessionFailure(CONTROL_TIMEOUT)`,
 * so a PING send deadline, a PONG receive-body deadline and a SESSION_CLOSE header deadline were
 * indistinguishable to a caller. The context adds structure without touching the high-level code,
 * and these tests pin the two properties that make it safe to log: the type is honest about what the
 * channel actually learned, and the value can carry no peer-controlled or secret material.
 */
public class ControlIoFailureContextTest {

    @Test
    public fun `the diagnostic vocabulary covers every control operation, phase and record type`() {
        assertEquals(
            listOf(ControlIoOperation.SEND, ControlIoOperation.RECEIVE, ControlIoOperation.CLOSE),
            ControlIoOperation.entries.toList(),
        )
        assertEquals(
            listOf(
                ControlIoPhase.FRAME,
                ControlIoPhase.HEADER,
                ControlIoPhase.BODY,
                ControlIoPhase.AUTHENTICATION,
                ControlIoPhase.FLUSH,
                ControlIoPhase.CLOSE,
            ),
            ControlIoPhase.entries.toList(),
        )
        assertEquals(
            listOf(
                ControlIoRecordType.PING,
                ControlIoRecordType.PONG,
                ControlIoRecordType.SESSION_CLOSE,
                ControlIoRecordType.UNKNOWN,
            ),
            ControlIoRecordType.entries.toList(),
        )
    }

    @Test
    public fun `the wire record type is never widened to carry UNKNOWN`() {
        // UNKNOWN is a diagnostic answer about what the channel did not learn. Putting it on the
        // wire enum would make an unrepresentable-on-the-wire value constructible, which is a worse
        // defect than the small duplication of names.
        assertEquals(4, SecureRecordType.entries.size)
        assertTrue(SecureRecordType.entries.none { it.name == "UNKNOWN" })
        assertEquals(
            listOf(
                SecureRecordType.KEY_CONFIRMATION,
                SecureRecordType.SESSION_CLOSE,
                SecureRecordType.PING,
                SecureRecordType.PONG,
            ),
            SecureRecordType.entries.toList(),
        )
    }

    @Test
    public fun `control record types map to their diagnostic names`() {
        assertEquals(ControlIoRecordType.PING, controlIoRecordTypeOf(SecureRecordType.PING))
        assertEquals(ControlIoRecordType.PONG, controlIoRecordTypeOf(SecureRecordType.PONG))
        assertEquals(
            ControlIoRecordType.SESSION_CLOSE,
            controlIoRecordTypeOf(SecureRecordType.SESSION_CLOSE),
        )
    }

    @Test
    public fun `a type the channel never saw maps to UNKNOWN rather than to a guess`() {
        // A deadline before the header is decoded tells us nothing about the record. Inventing PING
        // would attribute the timeout to a record that may never have been sent.
        assertEquals(ControlIoRecordType.UNKNOWN, controlIoRecordTypeOf(null))
        // KEY_CONFIRMATION is not a control record type, so it has no diagnostic control identity.
        assertEquals(ControlIoRecordType.UNKNOWN, controlIoRecordTypeOf(SecureRecordType.KEY_CONFIRMATION))
    }

    @Test
    public fun `the mapping is total over every wire record type`() {
        for (type in SecureRecordType.entries) {
            assertTrue(
                "$type must map to a diagnostic record type",
                controlIoRecordTypeOf(type) in ControlIoRecordType.entries,
            )
        }
    }

    @Test
    public fun `equality and hashing are deterministic and value based`() {
        val a = ControlIoFailureContext(
            operation = ControlIoOperation.RECEIVE,
            phase = ControlIoPhase.BODY,
            recordType = ControlIoRecordType.PONG,
        )
        val b = ControlIoFailureContext(
            operation = ControlIoOperation.RECEIVE,
            phase = ControlIoPhase.BODY,
            recordType = ControlIoRecordType.PONG,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        // Every field participates, so a phase change or a type change is a different diagnosis.
        assertNotEquals(
            a,
            a.copy(phase = ControlIoPhase.HEADER),
        )
        assertNotEquals(
            a,
            a.copy(recordType = ControlIoRecordType.UNKNOWN),
        )
        assertNotEquals(
            a,
            a.copy(operation = ControlIoOperation.SEND),
        )
    }

    @Test
    public fun `the six control classifications are pairwise distinct`() {
        val classifications = listOf(
            ControlIoFailureContext(ControlIoOperation.SEND, ControlIoPhase.FLUSH, ControlIoRecordType.PING),
            ControlIoFailureContext(ControlIoOperation.RECEIVE, ControlIoPhase.BODY, ControlIoRecordType.PING),
            ControlIoFailureContext(ControlIoOperation.SEND, ControlIoPhase.FLUSH, ControlIoRecordType.PONG),
            ControlIoFailureContext(ControlIoOperation.RECEIVE, ControlIoPhase.BODY, ControlIoRecordType.PONG),
            ControlIoFailureContext(ControlIoOperation.SEND, ControlIoPhase.FLUSH, ControlIoRecordType.SESSION_CLOSE),
            ControlIoFailureContext(ControlIoOperation.RECEIVE, ControlIoPhase.BODY, ControlIoRecordType.SESSION_CLOSE),
        )
        assertEquals(
            "each of the six must be distinguishable from the others",
            6,
            classifications.toSet().size,
        )
    }

    @Test
    public fun `a header-phase timeout is distinct from a body-phase timeout of the same type`() {
        val headerTimeout = ControlIoFailureContext(
            operation = ControlIoOperation.RECEIVE,
            phase = ControlIoPhase.HEADER,
            recordType = ControlIoRecordType.UNKNOWN,
        )
        val bodyTimeout = ControlIoFailureContext(
            operation = ControlIoOperation.RECEIVE,
            phase = ControlIoPhase.BODY,
            recordType = ControlIoRecordType.UNKNOWN,
        )
        assertNotEquals(headerTimeout, bodyTimeout)
    }

    @Test
    public fun `string form is deterministic and exposes no frame bytes or identities`() {
        val context = ControlIoFailureContext(
            operation = ControlIoOperation.SEND,
            phase = ControlIoPhase.FLUSH,
            recordType = ControlIoRecordType.SESSION_CLOSE,
        )
        val rendered = context.toString()
        assertEquals(
            "ControlIoFailureContext(operation=SEND, phase=FLUSH, recordType=SESSION_CLOSE)",
            rendered,
        )
        assertEquals("rendering twice must be identical", rendered, context.toString())
        // Three closed enums, so there is nothing peer-controlled left to leak. Assert the absence
        // of the shapes a leak would take rather than only the presence of the enum names.
        assertFalse(rendered.contains("0x"))
        assertFalse(rendered.contains("[B@"))
        assertFalse(rendered.contains("SessionId"))
    }

    @Test
    public fun `the context is optional, so unrelated failures stay source compatible`() {
        val plain = SessionFailure(SessionFailureCode.CONTROL_TIMEOUT)
        assertNull("an unrelated failure carries no control-I/O context", plain.controlIoContext)
        assertEquals(SessionFailureCode.CONTROL_TIMEOUT, plain.code)

        val described = SessionFailure(
            SessionFailureCode.CONTROL_TIMEOUT,
            ControlIoFailureContext(
                operation = ControlIoOperation.RECEIVE,
                phase = ControlIoPhase.BODY,
                recordType = ControlIoRecordType.PING,
            ),
        )
        assertEquals(
            ControlIoFailureContext(
                operation = ControlIoOperation.RECEIVE,
                phase = ControlIoPhase.BODY,
                recordType = ControlIoRecordType.PING,
            ),
            described.controlIoContext,
        )
        assertEquals(
            "the high-level code is unchanged by adding context",
            SessionFailureCode.CONTROL_TIMEOUT,
            described.code,
        )
    }

    @Test
    public fun `the failure string form still hides the context`() {
        // SessionFailure.toString is already redacted to the code id. Adding a field must not turn
        // it into a diagnostic dump, because that string is what reaches logs.
        val described = SessionFailure(
            SessionFailureCode.CONTROL_TIMEOUT,
            ControlIoFailureContext(
                operation = ControlIoOperation.SEND,
                phase = ControlIoPhase.FLUSH,
                recordType = ControlIoRecordType.PONG,
            ),
        )
        assertEquals("SessionFailure(code=${SessionFailureCode.CONTROL_TIMEOUT.id})", described.toString())
    }
}
