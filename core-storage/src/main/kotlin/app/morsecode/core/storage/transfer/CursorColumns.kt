package app.morsecode.core.storage.transfer

import android.database.Cursor

/*
 * Reading a provider's columns defensively.
 *
 * A cursor returns whatever the provider put in it, and a provider is another
 * process. A column can be missing entirely (the index is -1) or present and set
 * to SQL NULL, and those are two different things that both mean "this provider
 * did not tell us" rather than "the answer is zero". Callers get null for both,
 * because a caller that cannot tell those apart will turn a missing length into a
 * zero-byte transfer.
 */

/** The column's value as a String, or null when the column is absent or NULL. */
internal fun Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

/** The column's value as a Long, or null when the column is absent or NULL. */
internal fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

/**
 * The column's value as an Int, or null when the column is absent or NULL.
 *
 * Flags are the reason this must stay nullable: a document that omits
 * COLUMN_FLAGS has not declared itself free of FLAG_SUPPORTS_RENAME, so
 * treating a missing flags column as zero would report a capability the
 * provider never denied.
 */
internal fun Cursor.intOrNull(column: String): Int? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getInt(index)
}
