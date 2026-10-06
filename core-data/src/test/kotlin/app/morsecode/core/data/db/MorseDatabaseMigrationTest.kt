package app.morsecode.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.morsecode.core.model.SessionDirection
import app.morsecode.core.model.TransferState
import app.morsecode.core.transfer.identity.BatchId
import app.morsecode.core.transfer.identity.ChunkSize
import app.morsecode.core.transfer.identity.FileId
import app.morsecode.core.transfer.identity.ProtocolVersion
import app.morsecode.core.transfer.identity.RelativeTransferPath
import app.morsecode.core.transfer.identity.SessionId
import app.morsecode.core.transfer.identity.TransferId
import app.morsecode.core.transfer.model.TransferFileDescriptor
import app.morsecode.core.transfer.model.TransferSnapshot
import app.morsecode.core.transfer.persistence.StoreReadResult
import app.morsecode.core.transfer.persistence.StoreResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MorseDatabaseMigrationTest {
    private val roomTableNameToken = "\$" + "{TABLE_NAME}"
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = mutableListOf<String>()

    @After
    fun closeDatabases() {
        databases.forEach { name -> context.deleteDatabase(name) }
    }

    @Test
    fun `authentic v1 schema migrates to v2 without losing existing rows`() = runBlocking {
        val name = databaseName()
        val schema = readV1Schema()
        assertEquals(1, schema.version)
        createVersionOneDatabase(name, schema)

        val migrated = Room.databaseBuilder(context, MorseDatabase::class.java, name)
            .addMigrations(MORSE_MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(2, migrated.openHelper.writableDatabase.version)
            val session = migrated.transferSessionDao().find("session-v1")
            assertEquals("session-v1", session?.sessionId)
            assertEquals("Ọ̀rẹ́ 📦", session?.peerName)
            assertEquals(null, session?.peerAppVersion)
            val item = migrated.transferItemDao().find("transfer-v1")
            assertNotNull(item)
            assertEquals("batch-v1", item?.batchId)
            assertEquals("résumé-📦.bin", item?.displayName)
            assertEquals(4_096L, item?.confirmedBytes)
            assertEquals(Long.MAX_VALUE, item?.lastModifiedEpochMillis)
            assertEquals("paused_local", item?.state)
            assertEquals(null, item?.mimeType)
            assertEquals("tree-uri-v1", migrated.safGrantDao().observe().first().single().treeUri)

            val createdTables = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table'",
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertTrue("transfer_snapshots" in createdTables)
            assertTrue("transfer_partials" in createdTables)
            assertTrue("saf_rename_history" in createdTables)
            assertTrue("saf_pending_cleanup" in createdTables)
            assertEquals(0, migrated.transferSnapshotDao().countNonTerminalForSession("session-v1"))
            assertTrue(migrated.transferPartialDao().forSession("session-v1").isEmpty())

            val indices = migrated.openHelper.writableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'index'",
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertTrue("index_transfer_snapshots_session_id_snapshot_state" in indices)
            assertTrue("index_transfer_partials_staging_identity" in indices)
            assertTrue("index_saf_rename_history_commit_id_phase" in indices)
            assertTrue("index_saf_pending_cleanup_commit_id_cleanup_type" in indices)

            fun foreignKeyParent(table: String): String? =
                migrated.openHelper.writableDatabase.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                    if (cursor.moveToNext()) cursor.getString(2) else null
                }
            assertEquals("transfer_sessions", foreignKeyParent("transfer_items"))
            assertEquals("transfer_partials", foreignKeyParent("saf_rename_history"))
            assertEquals("transfer_partials", foreignKeyParent("saf_pending_cleanup"))

            val retained = retentionSnapshot()
            val store = RoomTransferSnapshotStore(migrated, kotlinx.coroutines.Dispatchers.IO)
            assertEquals(StoreResult.Ok, store.saveTransition(null, retained, emptyList()))
            assertEquals(StoreReadResult.Found(retained), store.loadTransfer(retained.transferId))
            migrated.transferSessionDao().deleteClosedBefore(Long.MAX_VALUE)
            assertNotNull(migrated.transferSessionDao().find("session-v1"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `fresh install validates v2 schema constraints DAO operations and reopen`() = runBlocking {
        val name = databaseName()
        var database = Room.databaseBuilder(context, MorseDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        val snapshot = retentionSnapshot().copy(
            transferId = TransferId("fresh-transfer"),
            sessionId = SessionId("fresh-session"),
        )
        try {
            val sql = database.openHelper.writableDatabase
            assertEquals(2, sql.version)
            val tables = sql.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT IN ('room_master_table', 'android_metadata') AND name NOT LIKE 'sqlite_%'",
            ).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertEquals(
                setOf(
                    "transfer_sessions", "transfer_items", "history_entries", "recent_devices",
                    "log_entries", "crash_reports", "browser_sessions", "saf_grants", "web_transfers",
                    "transfer_snapshots", "transfer_partials", "saf_rename_history", "saf_pending_cleanup",
                ),
                tables,
            )
            assertEquals(1, sql.query("PRAGMA foreign_keys").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
            fun foreignKeyDeleteAction(table: String): String? =
                sql.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                    if (cursor.moveToNext()) cursor.getString(6) else null
                }
            assertEquals("CASCADE", foreignKeyDeleteAction("saf_rename_history"))
            assertEquals("CASCADE", foreignKeyDeleteAction("saf_pending_cleanup"))
            val indices = sql.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            assertTrue("index_transfer_snapshots_session_id_snapshot_state" in indices)
            assertTrue("index_transfer_partials_staging_identity" in indices)
            assertTrue("index_saf_rename_history_commit_id_phase" in indices)
            assertTrue("index_saf_pending_cleanup_commit_id_cleanup_type" in indices)

            val snapshotDao = database.transferSnapshotDao()
            assertEquals(0, snapshotDao.countNonTerminalForSession(snapshot.sessionId.value))
            val store = RoomTransferSnapshotStore(database, kotlinx.coroutines.Dispatchers.IO)
            assertEquals(StoreResult.Ok, store.saveTransition(null, snapshot, emptyList()))
            assertEquals(1, snapshotDao.countNonTerminalForSession(snapshot.sessionId.value))

            val invalidChild = SafRenameHistoryEntity(
                commitId = "missing-parent",
                sequence = 0,
                phase = "final_promotion",
                beforeUri = "content://example.provider/tree/root/document/before",
                beforeDocumentId = "before",
                returnedUri = null,
                returnedDocumentId = null,
                reconciliationId = null,
            )
            val childFailure = runCatching {
                database.safRenameHistoryDao().insertAll(listOf(invalidChild))
            }.exceptionOrNull()
            assertTrue(childFailure?.causes()?.any { it is android.database.sqlite.SQLiteConstraintException } == true)

            database.close()
            database = Room.databaseBuilder(context, MorseDatabase::class.java, name)
                .allowMainThreadQueries()
                .build()
            val restored = RoomTransferSnapshotStore(database, kotlinx.coroutines.Dispatchers.IO)
                .loadTransfer(snapshot.transferId)
            assertEquals(StoreReadResult.Found(snapshot), restored)
        } finally {
            database.close()
        }
    }

    private fun retentionSnapshot(): TransferSnapshot {
        val descriptor = TransferFileDescriptor(
            fileId = FileId("retention-file"),
            displayName = "retained.bin",
            relativePath = RelativeTransferPath("retained.bin"),
            mimeType = "application/octet-stream",
            totalBytes = 0L,
            lastModifiedEpochMillis = null,
            isFolderArchive = false,
            expectedSha256 = null,
            chunkSize = ChunkSize(4_096),
            protocolVersion = ProtocolVersion.CURRENT,
        )
        return TransferSnapshot(
            transferId = TransferId("retention-transfer"),
            sessionId = SessionId("session-v1"),
            batchId = BatchId("retention-batch"),
            recipientId = null,
            direction = SessionDirection.OUTBOUND,
            descriptor = descriptor,
            state = TransferState.QUEUED,
            confirmedBytes = 0L,
            optimisticBytes = 0L,
            lastAcknowledgedSequence = null,
            retryCount = 0,
            failure = null,
            verification = null,
            remotePaused = false,
            snapshotVersion = 1L,
            queueOrder = 0L,
        )
    }

    private fun createVersionOneDatabase(name: String, schema: V1Schema) {
        val legacy = context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null)
        legacy.execSQL("PRAGMA foreign_keys = ON")
        for (entity in schema.entities) {
            legacy.execSQL(entity.createSql.replace(roomTableNameToken, entity.tableName))
            for (indexSql in entity.indexSql) {
                legacy.execSQL(indexSql.replace(roomTableNameToken, entity.tableName))
            }
        }
        schema.setupQueries.forEach(legacy::execSQL)
        legacy.execSQL(
            """INSERT INTO transfer_sessions (
                sessionId, peer_id, peer_name, peer_endpoint, peer_transport, peer_device_kind,
                peer_detail, peer_app_version, peer_supports_resume, peer_supports_encryption,
                broadcast_id, phase, pause_all, started_at, ended_at, failure_reason
            ) VALUES ('session-v1', 'peer-v1', 'Ọ̀rẹ́ 📦', 'endpoint', 'lan', 'phone', 'detail',
                      NULL, 1, 1, NULL, 'ended', 0, 100, 200, NULL)""",
        )
        legacy.execSQL(
            """INSERT INTO transfer_items (
                transfer_id, session_id, batch_id, direction, display_name, relative_path,
                mime_type, kind, total_bytes, last_modified, is_folder_archive, state,
                confirmed_bytes, bytes_per_second, sha256_hex, failure_reason, retry_count,
                queued_at, finished_at, result_uri, recipient_peer_id, source_uri,
                target_dir, queue_position
            ) VALUES ('transfer-v1', 'session-v1', 'batch-v1', 'inbound', 'résumé-📦.bin', '',
                      NULL, 'file', 8192, 9223372036854775807, 0, 'paused_local', 4096, 0, NULL, NULL, 0,
                      100, 0, NULL, 'peer-v1', NULL, NULL, 0)""",
        )
        legacy.execSQL(
            "INSERT INTO saf_grants (tree_uri, display_name, granted_at, read_write) " +
                "VALUES ('tree-uri-v1', 'Selected folder', 123, 1)",
        )
        legacy.version = 1
        legacy.close()
    }

    private fun readV1Schema(): V1Schema {
        val path = listOf(
            File("schemas/app.morsecode.core.data.db.MorseDatabase/1.json"),
            File("../core-data/schemas/app.morsecode.core.data.db.MorseDatabase/1.json"),
            File("core-data/schemas/app.morsecode.core.data.db.MorseDatabase/1.json"),
        ).firstOrNull(File::isFile) ?: error("committed authentic Room v1 schema was not found")
        val root = JSONObject(path.readText())
        val database = root.getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val parsedEntities = buildList {
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val indices = entity.optJSONArray("indices")
                add(
                    V1Entity(
                        tableName = entity.getString("tableName"),
                        createSql = entity.getString("createSql"),
                        indexSql = buildList {
                            if (indices != null) {
                                for (indexInEntity in 0 until indices.length()) {
                                    add(indices.getJSONObject(indexInEntity).getString("createSql"))
                                }
                            }
                        },
                    ),
                )
            }
        }
        val setup = database.getJSONArray("setupQueries")
        return V1Schema(
            version = database.getInt("version"),
            entities = parsedEntities,
            setupQueries = buildList {
                for (index in 0 until setup.length()) add(setup.getString(index))
            },
        )
    }

    private fun databaseName(): String = "migration-${UUID.randomUUID()}.db".also(databases::add)

    private data class V1Schema(
        val version: Int,
        val entities: List<V1Entity>,
        val setupQueries: List<String>,
    )

    private data class V1Entity(
        val tableName: String,
        val createSql: String,
        val indexSql: List<String>,
    )

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
}
