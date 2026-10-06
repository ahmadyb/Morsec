#!/usr/bin/env node
/*
 * Verify the authentic Room schema history and the non-destructive v1→v2 migration.
 *
 * The v1 byte hash is pinned to the accepted schema. CI invokes --baseline-only
 * before KSP runs so the first Room-v2 bootstrap build can create 2.json; after
 * KSP, CI invokes the default strict mode and byte-compares the generated export
 * with the previously committed 2.json. Once v2 has been committed, local and
 * CI runs should use strict mode.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, relative } from 'node:path';

const ROOT = process.cwd();
const SCHEMA_DIR = join(ROOT, 'core-data', 'schemas');
const BASELINE_ONLY = process.argv.includes('--baseline-only');
const ALLOWED_ARGS = new Set(['--baseline-only', '--require-v2', '--quiet']);
const unknownArgs = process.argv.slice(2).filter((arg) => !ALLOWED_ARGS.has(arg));
if (unknownArgs.length) {
  console.error(`unknown argument(s): ${unknownArgs.join(', ')}`);
  process.exit(2);
}

const EXPECTED_V1_TABLES = [
  'browser_sessions',
  'crash_reports',
  'history_entries',
  'log_entries',
  'recent_devices',
  'saf_grants',
  'transfer_items',
  'transfer_sessions',
  'web_transfers',
];
const V2_TABLES = [
  'saf_pending_cleanup',
  'saf_rename_history',
  'transfer_partials',
  'transfer_snapshots',
];
const V1_SHA256 = 'b0bca4243d2f0ba4631e3338e611d3bcaff8ba456de83b79ea0106ae187ac488';
const V1_BYTES = 29767;

const checks = [];
function check(name, fn) {
  checks.push({ name, fn });
}

function walk(dir, extensions = new Set(['.json'])) {
  const output = [];
  if (!existsSync(dir)) return output;
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    const stat = statSync(path);
    if (stat.isDirectory()) output.push(...walk(path, extensions));
    else if ([...extensions].some((extension) => name.endsWith(extension))) output.push(path);
  }
  return output;
}

function hash(raw) {
  return createHash('sha256').update(raw).digest('hex');
}

function compareLists(actual, expected, label) {
  const normalizedActual = [...actual].sort();
  const normalizedExpected = [...expected].sort();
  if (JSON.stringify(normalizedActual) !== JSON.stringify(normalizedExpected)) {
    throw new Error(`${label} differs:\n  found:    ${normalizedActual.join(', ')}\n  expected: ${normalizedExpected.join(', ')}`);
  }
}

const allSchemas = walk(SCHEMA_DIR);
const existingV1Paths = allSchemas.filter((path) => path.endsWith('/1.json'));
const existingV2Paths = allSchemas.filter((path) => path.endsWith('/2.json'));
let v1Path = existingV1Paths.length === 1 ? existingV1Paths[0] : null;
let v2Path = existingV2Paths.length === 1 ? existingV2Paths[0] : null;
let v1Raw = v1Path ? readFileSync(v1Path) : null;
let v2Raw = v2Path ? readFileSync(v2Path) : null;
let v1 = null;
let v2 = null;

check('schema export directory exists', () => {
  if (!existsSync(SCHEMA_DIR)) throw new Error(`${SCHEMA_DIR} is missing`);
  return relative(ROOT, SCHEMA_DIR);
});

check('exactly one authentic v1 export exists', () => {
  const matches = allSchemas.filter((path) => path.endsWith('/1.json'));
  if (matches.length !== 1) throw new Error(`expected exactly one 1.json, found ${matches.length}`);
  const expectedPath = join(SCHEMA_DIR, 'app.morsecode.core.data.db.MorseDatabase', '1.json');
  if (matches[0] !== expectedPath) {
    throw new Error(`the v1 export is not at the immutable Room namespace path: ${relative(ROOT, matches[0])}`);
  }
  v1Path = matches[0];
  v1Raw = readFileSync(v1Path);
  return relative(ROOT, v1Path);
});

check('the v1 export is byte-for-byte unchanged', () => {
  const actualHash = hash(v1Raw);
  if (v1Raw.length !== V1_BYTES || actualHash !== V1_SHA256) {
    throw new Error(`1.json changed: ${v1Raw.length} bytes, sha256 ${actualHash}`);
  }
  return `${v1Raw.length} bytes, sha256 ${actualHash}`;
});

check('v1 is an authentic Room version-1 schema with exactly nine expected tables', () => {
  try {
    v1 = JSON.parse(v1Raw.toString('utf8'));
  } catch (error) {
    throw new Error(`1.json is not parseable: ${error.message}`);
  }
  if (v1.formatVersion !== 1 || v1.database?.version !== 1) {
    throw new Error(`1.json format/database version is ${v1.formatVersion}/${v1.database?.version}`);
  }
  compareLists((v1.database.entities ?? []).map((entity) => entity.tableName), EXPECTED_V1_TABLES, 'v1 tables');
  if (typeof v1.database.identityHash !== 'string' || !/^[0-9a-f]{32}$/.test(v1.database.identityHash)) {
    throw new Error('v1 database identityHash is missing or malformed');
  }
  const insert = (v1.database.setupQueries ?? []).find((query) =>
    query.includes('room_master_table') && query.includes('INSERT'));
  if (!insert || !insert.includes(v1.database.identityHash)) {
    throw new Error('v1 setupQueries do not contain the generated identityHash');
  }
  return `${v1.database.entities.length} tables, identity ${v1.database.identityHash}`;
});

check('no Room v3 or extra versioned schema is present', () => {
  const unsupported = allSchemas.filter((path) => /\/\d+\.json$/.test(path) && !/\/(?:1|2)\.json$/.test(path));
  if (unsupported.length) throw new Error(`unexpected schema file(s): ${unsupported.map((path) => relative(ROOT, path)).join(', ')}`);
  return 'only v1 and v2 schema versions are allowed';
});

check(BASELINE_ONLY ? 'v2 bootstrap state is valid' : 'exactly one authentic v2 export exists', () => {
  const matches = allSchemas.filter((path) => path.endsWith('/2.json'));
  if (matches.length === 0 && BASELINE_ONLY) return 'v2 may be generated by KSP later in this bootstrap job';
  if (matches.length !== 1) throw new Error(`expected exactly one 2.json, found ${matches.length}`);
  v2Path = matches[0];
  v2Raw = readFileSync(v2Path);
  return relative(ROOT, v2Path);
});

check('the explicit migration is additive, registered, and has no destructive fallback', () => {
  const dbDir = join(ROOT, 'core-data', 'src', 'main', 'kotlin');
  const sources = walk(dbDir, new Set(['.kt', '.kts']));
  const migrationPath = join(dbDir, 'app', 'morsecode', 'core', 'data', 'db', 'MorseDatabaseMigrations.kt');
  const databasePath = join(dbDir, 'app', 'morsecode', 'core', 'data', 'db', 'MorseDatabase.kt');
  const modulePath = join(dbDir, 'app', 'morsecode', 'core', 'data', 'di', 'DataModule.kt');
  const migration = readFileSync(migrationPath, 'utf8');
  const database = readFileSync(databasePath, 'utf8');
  const module = readFileSync(modulePath, 'utf8');
  if (!/Migration\s*\(\s*1\s*,\s*2\s*\)/.test(migration) ||
      !/\.addMigrations\s*\(\s*MORSE_MIGRATION_1_2\s*\)/.test(module)) {
    throw new Error('Migration(1, 2) is not explicitly registered in the production builder');
  }
  if (!/version\s*=\s*2/.test(database)) throw new Error('MorseDatabase is not version 2');
  if (/DROP\s+(?:TABLE|INDEX)|ALTER\s+TABLE/i.test(migration)) {
    throw new Error('the v1→v2 migration contains destructive or unreviewed DDL');
  }
  if (!/CREATE TABLE `transfer_snapshots`/.test(migration) ||
      !/CREATE TABLE `transfer_partials`/.test(migration) ||
      !/CREATE TABLE `saf_rename_history`/.test(migration) ||
      !/CREATE TABLE `saf_pending_cleanup`/.test(migration)) {
    throw new Error('the migration does not create all four v2 tables');
  }
  if (sources.some((path) => /fallbackToDestructiveMigration/.test(readFileSync(path, 'utf8')))) {
    throw new Error('a destructive Room migration fallback exists in core-data main sources');
  }
  return 'explicit additive 1→2 migration; production registration present; no destructive fallback';
});

if (v2Raw) {
  check('the v2 export is parseable Room format 1 / database version 2', () => {
    try {
      v2 = JSON.parse(v2Raw.toString('utf8'));
    } catch (error) {
      throw new Error(`2.json is not parseable: ${error.message}`);
    }
    if (v2.formatVersion !== 1 || v2.database?.version !== 2) {
      throw new Error(`2.json format/database version is ${v2.formatVersion}/${v2.database?.version}`);
    }
    const identity = v2.database.identityHash;
    if (typeof identity !== 'string' || !/^[0-9a-f]{32}$/.test(identity) || identity === v1.database.identityHash) {
      throw new Error(`v2 identityHash is missing, malformed, or unchanged: ${JSON.stringify(identity)}`);
    }
    const insert = (v2.database.setupQueries ?? []).find((query) =>
      query.includes('room_master_table') && query.includes('INSERT'));
    if (!insert || !insert.includes(identity)) throw new Error('v2 setupQueries do not contain its identityHash');
    return `identity ${identity}, ${v2Raw.length} bytes`;
  });

  check('v2 contains exactly all nine v1 tables and the four required v2 tables', () => {
    compareLists(
      (v2.database.entities ?? []).map((entity) => entity.tableName),
      [...EXPECTED_V1_TABLES, ...V2_TABLES],
      'v2 tables',
    );
    return '13 tables';
  });

  check('all nine v1 entity definitions are preserved exactly in v2', () => {
    const oldByName = new Map(v1.database.entities.map((entity) => [entity.tableName, entity]));
    const newByName = new Map(v2.database.entities.map((entity) => [entity.tableName, entity]));
    for (const table of EXPECTED_V1_TABLES) {
      if (JSON.stringify(oldByName.get(table)) !== JSON.stringify(newByName.get(table))) {
        throw new Error(`v1 entity ${table} changed in 2.json`);
      }
    }
    return 'all nine v1 entity definitions match 1.json';
  });

  check('v2 columns, ownership foreign keys, and required indices match the persistence design', () => {
    const entities = new Map(v2.database.entities.map((entity) => [entity.tableName, entity]));
    const requiredColumns = {
      transfer_snapshots: [
        'transfer_id', 'session_id', 'batch_id', 'recipient_id', 'direction', 'snapshot_state',
        'snapshot_version', 'confirmed_bytes', 'optimistic_bytes', 'last_acknowledged_sequence',
        'retry_count', 'failure_code', 'failure_detail', 'failure_retryable', 'failure_origin',
        'failure_category', 'remote_paused', 'queue_order', 'file_id', 'display_name',
        'relative_path', 'mime_type', 'total_bytes', 'last_modified_epoch_millis',
        'is_folder_archive', 'expected_sha256_hex', 'chunk_size', 'protocol_version',
        'verification_expected_digest_hex', 'verification_observed_digest_hex',
        'verification_started_snapshot_version', 'row_revision',
      ],
      transfer_partials: [
        'commit_id', 'staging_identity', 'session_id', 'transfer_id', 'checkpoint_version',
        'journal_revision', 'rename_history_count', 'pending_cleanup_count', 'strategy_id',
        'duplicate_policy', 'grant_id', 'tree_uri', 'authority', 'root_document_id',
        'parent_document_id', 'expected_final_name', 'expected_size_bytes', 'expected_digest_hex',
        'checkpoint_phase', 'temporary_uri', 'temporary_document_id', 'existing_uri',
        'existing_document_id', 'backup_uri', 'backup_document_id', 'returned_rename_uri',
        'returned_rename_identity_uri', 'returned_rename_document_id', 'final_uri',
        'final_document_id', 'copied_bytes', 'staging_released', 'last_failure_category',
        'last_failure_code', 'unresolved_rename_phase', 'verified_digest_hex',
      ],
      saf_rename_history: [
        'commit_id', 'sequence', 'phase', 'before_uri', 'before_document_id',
        'returned_uri', 'returned_document_id', 'reconciliation_id',
      ],
      saf_pending_cleanup: [
        'commit_id', 'sequence', 'cleanup_type', 'document_uri', 'document_id', 'staging_identity',
      ],
    };
    for (const [table, columns] of Object.entries(requiredColumns)) {
      const entity = entities.get(table);
      if (!entity) throw new Error(`missing entity ${table}`);
      const fields = new Set((entity.fields ?? []).map((field) => field.columnName));
      for (const column of columns) if (!fields.has(column)) throw new Error(`${table} is missing ${column}`);
      if (table === 'transfer_snapshots') {
        const extra = [...fields].filter((column) => !columns.includes(column));
        if (extra.length) throw new Error(`transfer_snapshots has unexpected column(s): ${extra.join(', ')}`);
        if (fields.has('encoded_snapshot')) {
          throw new Error('transfer_snapshots must not persist a serialized object');
        }
      }
    }
    const expectedPrimaryKeys = {
      transfer_snapshots: ['transfer_id'],
      transfer_partials: ['commit_id'],
      saf_rename_history: ['commit_id', 'sequence'],
      saf_pending_cleanup: ['commit_id', 'sequence'],
    };
    for (const [table, columns] of Object.entries(expectedPrimaryKeys)) {
      if (JSON.stringify(entities.get(table).primaryKey?.columnNames) !== JSON.stringify(columns)) {
        throw new Error(`${table} primary key differs from ${columns.join(', ')}`);
      }
    }

    const expectedIndices = {
      transfer_snapshots: [
        ['index_transfer_snapshots_session_id_snapshot_state', false, ['session_id', 'snapshot_state']],
      ],
      transfer_partials: [
        ['index_transfer_partials_staging_identity', true, ['staging_identity']],
        ['index_transfer_partials_session_id_checkpoint_phase', false, ['session_id', 'checkpoint_phase']],
        ['index_transfer_partials_transfer_id', false, ['transfer_id']],
      ],
      saf_rename_history: [
        ['index_saf_rename_history_commit_id_phase', true, ['commit_id', 'phase']],
      ],
      saf_pending_cleanup: [
        ['index_saf_pending_cleanup_commit_id_cleanup_type', true, ['commit_id', 'cleanup_type']],
        ['index_saf_pending_cleanup_commit_id_document_uri_document_id', true,
          ['commit_id', 'document_uri', 'document_id']],
      ],
    };
    for (const [table, indices] of Object.entries(expectedIndices)) {
      const byName = new Map((entities.get(table).indices ?? []).map((index) => [index.name, index]));
      for (const [name, unique, columns] of indices) {
        const index = byName.get(name);
        if (!index || index.unique !== unique || JSON.stringify(index.columnNames) !== JSON.stringify(columns)) {
          throw new Error(`${table} index ${name} is missing or malformed`);
        }
      }
    }

    for (const table of ['transfer_snapshots', 'transfer_partials']) {
      if ((entities.get(table).foreignKeys ?? []).length !== 0) {
        throw new Error(`${table} must not cascade from UI-owned session rows`);
      }
    }
    for (const table of ['saf_rename_history', 'saf_pending_cleanup']) {
      const keys = entities.get(table).foreignKeys ?? [];
      if (keys.length !== 1) throw new Error(`${table} must have exactly one parent ownership foreign key`);
      const key = keys[0];
      if (key.table !== 'transfer_partials' || key.onDelete !== 'CASCADE' ||
          JSON.stringify(key.columns) !== JSON.stringify(['commit_id']) ||
          JSON.stringify(key.referencedColumns) !== JSON.stringify(['commit_id'])) {
        throw new Error(`${table} ownership foreign key is not a commit-scoped child-only cascade`);
      }
    }
    return 'required columns and indices present; only checkpoint-owned children cascade';
  });
}

let failures = 0;
const lines = [];
for (const { name, fn } of checks) {
  try {
    const detail = fn();
    lines.push(`  ok   ${name}${detail ? ` — ${detail}` : ''}`);
  } catch (error) {
    failures += 1;
    lines.push(`  FAIL ${name} — ${error.message}`);
  }
}

if (!process.argv.includes('--quiet')) {
  console.log('\nRoom schema export verification');
  console.log(lines.join('\n'));
  if (v1Raw) {
    console.log(`\n  v1 path   ${relative(ROOT, v1Path)}`);
    console.log(`  v1 size   ${v1Raw.length} bytes`);
    console.log(`  v1 sha256 ${hash(v1Raw)}`);
  }
  if (v2Raw) {
    console.log(`\n  v2 path   ${relative(ROOT, v2Path)}`);
    console.log(`  v2 size   ${v2Raw.length} bytes`);
    console.log(`  v2 sha256 ${hash(v2Raw)}`);
  }
}

console.log(`\n${checks.length - failures}/${checks.length} checks passed, ${failures} failed`);
if (failures) process.exit(1);
