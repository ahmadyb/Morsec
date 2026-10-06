package app.morsecode.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive Room v1 → v2 migration. Existing v1 tables and rows are untouched. */
public val MORSE_MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE `transfer_snapshots` (
                `transfer_id` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `batch_id` TEXT NOT NULL,
                `recipient_id` TEXT,
                `direction` TEXT NOT NULL,
                `snapshot_state` TEXT NOT NULL,
                `snapshot_version` INTEGER NOT NULL,
                `confirmed_bytes` INTEGER NOT NULL,
                `optimistic_bytes` INTEGER NOT NULL,
                `last_acknowledged_sequence` INTEGER,
                `retry_count` INTEGER NOT NULL,
                `failure_code` TEXT,
                `failure_detail` TEXT,
                `failure_retryable` INTEGER,
                `failure_origin` TEXT,
                `failure_category` TEXT,
                `remote_paused` INTEGER NOT NULL,
                `queue_order` INTEGER NOT NULL,
                `file_id` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `relative_path` TEXT NOT NULL,
                `mime_type` TEXT NOT NULL,
                `total_bytes` INTEGER NOT NULL,
                `last_modified_epoch_millis` INTEGER,
                `is_folder_archive` INTEGER NOT NULL,
                `expected_sha256_hex` TEXT,
                `chunk_size` INTEGER NOT NULL,
                `protocol_version` INTEGER NOT NULL,
                `verification_expected_digest_hex` TEXT,
                `verification_observed_digest_hex` TEXT,
                `verification_started_snapshot_version` INTEGER,
                `row_revision` INTEGER NOT NULL,
                PRIMARY KEY(`transfer_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX `index_transfer_snapshots_session_id_snapshot_state` " +
                "ON `transfer_snapshots` (`session_id`, `snapshot_state`)",
        )

        db.execSQL(
            """
            CREATE TABLE `transfer_partials` (
                `commit_id` TEXT NOT NULL,
                `staging_identity` TEXT NOT NULL,
                `session_id` TEXT NOT NULL,
                `transfer_id` TEXT NOT NULL,
                `checkpoint_version` INTEGER NOT NULL,
                `journal_revision` INTEGER NOT NULL,
                `rename_history_count` INTEGER NOT NULL,
                `pending_cleanup_count` INTEGER NOT NULL,
                `strategy_id` TEXT NOT NULL,
                `duplicate_policy` TEXT NOT NULL,
                `grant_id` TEXT NOT NULL,
                `tree_uri` TEXT NOT NULL,
                `authority` TEXT NOT NULL,
                `root_document_id` TEXT NOT NULL,
                `parent_document_id` TEXT NOT NULL,
                `expected_final_name` TEXT NOT NULL,
                `expected_size_bytes` INTEGER NOT NULL,
                `expected_digest_hex` TEXT,
                `checkpoint_phase` TEXT NOT NULL,
                `temporary_uri` TEXT,
                `temporary_document_id` TEXT,
                `existing_uri` TEXT,
                `existing_document_id` TEXT,
                `backup_uri` TEXT,
                `backup_document_id` TEXT,
                `returned_rename_uri` TEXT,
                `returned_rename_identity_uri` TEXT,
                `returned_rename_document_id` TEXT,
                `final_uri` TEXT,
                `final_document_id` TEXT,
                `copied_bytes` INTEGER NOT NULL,
                `staging_released` INTEGER NOT NULL,
                `last_failure_category` TEXT,
                `last_failure_code` TEXT,
                `unresolved_rename_phase` TEXT,
                `verified_digest_hex` TEXT,
                PRIMARY KEY(`commit_id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_transfer_partials_staging_identity` " +
                "ON `transfer_partials` (`staging_identity`)",
        )
        db.execSQL(
            "CREATE INDEX `index_transfer_partials_session_id_checkpoint_phase` " +
                "ON `transfer_partials` (`session_id`, `checkpoint_phase`)",
        )
        db.execSQL(
            "CREATE INDEX `index_transfer_partials_transfer_id` " +
                "ON `transfer_partials` (`transfer_id`)",
        )

        db.execSQL(
            """
            CREATE TABLE `saf_rename_history` (
                `commit_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `phase` TEXT NOT NULL,
                `before_uri` TEXT NOT NULL,
                `before_document_id` TEXT NOT NULL,
                `returned_uri` TEXT,
                `returned_document_id` TEXT,
                `reconciliation_id` TEXT,
                PRIMARY KEY(`commit_id`, `sequence`),
                FOREIGN KEY(`commit_id`) REFERENCES `transfer_partials`(`commit_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_saf_rename_history_commit_id_phase` " +
                "ON `saf_rename_history` (`commit_id`, `phase`)",
        )

        db.execSQL(
            """
            CREATE TABLE `saf_pending_cleanup` (
                `commit_id` TEXT NOT NULL,
                `sequence` INTEGER NOT NULL,
                `cleanup_type` TEXT NOT NULL,
                `document_uri` TEXT,
                `document_id` TEXT,
                `staging_identity` TEXT,
                PRIMARY KEY(`commit_id`, `sequence`),
                FOREIGN KEY(`commit_id`) REFERENCES `transfer_partials`(`commit_id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_saf_pending_cleanup_commit_id_cleanup_type` " +
                "ON `saf_pending_cleanup` (`commit_id`, `cleanup_type`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_saf_pending_cleanup_commit_id_document_uri_document_id` " +
                "ON `saf_pending_cleanup` (`commit_id`, `document_uri`, `document_id`)",
        )
    }
}
