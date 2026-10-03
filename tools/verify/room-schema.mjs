#!/usr/bin/env node
/*
 * Verifies the committed Room schema export.
 *
 * The version-1 schema is the baseline every later migration is written and
 * tested against, so it has to be impossible to tamper with by accident. Two
 * accidents are worth guarding against specifically:
 *
 *   1. Someone bumps `version = 2` and adds the new entities, then regenerates
 *      the schema. Room overwrites 1.json *only* if the old file is missing, but
 *      a developer who deletes the directory and rebuilds gets a file called
 *      1.json that actually describes version 2. Every migration test written
 *      against it would then be testing nothing.
 *   2. A generated file is committed over the exported one after being edited by
 *      hand, or a truncated download is committed, producing a schema that
 *      parses but does not match what KSP produced.
 *
 * So this checks the content, not just that the file exists: the version, the
 * exact table list, the absence of the version-2 tables, and the structural
 * shape Room emits. It also prints the SHA-256 so a reviewer can compare it
 * against the value recorded in doc/AI_HANDOFF.md without downloading anything.
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';

const ROOT = process.cwd();
const SCHEMA_DIR = join(ROOT, 'core-data', 'schemas');

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

const FORBIDDEN_TABLES = ['transfer_snapshots', 'transfer_partials'];

const checks = [];
function check(name, fn) {
  checks.push({ name, fn });
}

function walk(dir) {
  const out = [];
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...walk(path));
    else if (entry.endsWith('.json')) out.push(path);
  }
  return out;
}

let parsed = null;
let raw = null;
let schemaPath = null;

check('the version-1 schema file exists', () => {
  const found = walk(SCHEMA_DIR).filter((p) => p.endsWith('/1.json'));
  if (found.length !== 1) {
    throw new Error(`expected exactly one 1.json under core-data/schemas, found ${found.length}`);
  }
  schemaPath = found[0];
  raw = readFileSync(schemaPath);
  return `${schemaPath.replace(ROOT + '/', '')}`;
});

check('the file is parseable JSON', () => {
  try {
    parsed = JSON.parse(raw.toString('utf8'));
  } catch (error) {
    throw new Error(`1.json is not parseable: ${error.message}`);
  }
  return `${raw.length} bytes`;
});

check('formatVersion is 1', () => {
  if (parsed.formatVersion !== 1) {
    throw new Error(`formatVersion is ${parsed.formatVersion}, expected 1`);
  }
  return '1';
});

check('database.version is 1', () => {
  if (parsed.database?.version !== 1) {
    throw new Error(
      `database.version is ${parsed.database?.version}, expected 1 — a v2 schema must never be committed as 1.json`,
    );
  }
  return '1';
});

check('it contains exactly the nine accepted version-1 tables', () => {
  const tables = (parsed.database?.entities ?? []).map((e) => e.tableName).sort();
  const expected = [...EXPECTED_V1_TABLES].sort();
  if (JSON.stringify(tables) !== JSON.stringify(expected)) {
    throw new Error(`table list differs:\n  found:    ${tables.join(', ')}\n  expected: ${expected.join(', ')}`);
  }
  return `${tables.length} tables`;
});

check('it does not contain the version-2 tables', () => {
  const tables = (parsed.database?.entities ?? []).map((e) => e.tableName);
  const present = FORBIDDEN_TABLES.filter((t) => tables.includes(t));
  if (present.length > 0) {
    throw new Error(`version-2 tables present in the version-1 schema: ${present.join(', ')}`);
  }
  return `none of ${FORBIDDEN_TABLES.join(', ')}`;
});

check('identityHash is a 32-character hex string', () => {
  const hash = parsed.database?.identityHash;
  if (typeof hash !== 'string' || !/^[0-9a-f]{32}$/.test(hash)) {
    throw new Error(`identityHash is ${JSON.stringify(hash)}, expected 32 lowercase hex characters`);
  }
  return hash;
});

check('it matches the structure Room emits', () => {
  const database = parsed.database;
  for (const key of ['entities', 'identityHash', 'setupQueries', 'version']) {
    if (!(key in database)) throw new Error(`database.${key} is missing`);
  }
  if (!Array.isArray(database.setupQueries) || database.setupQueries.length === 0) {
    throw new Error('database.setupQueries is empty');
  }
  if (!database.setupQueries.some((q) => q.includes('room_master_table'))) {
    throw new Error('no room_master_table setup query');
  }
  for (const entity of database.entities) {
    for (const key of ['tableName', 'createSql', 'fields', 'primaryKey']) {
      if (!(key in entity)) throw new Error(`entity ${entity.tableName ?? '?'} is missing ${key}`);
    }
    if (!Array.isArray(entity.fields) || entity.fields.length === 0) {
      throw new Error(`entity ${entity.tableName} has no fields`);
    }
  }
  return `${database.entities.length} entities, ${database.setupQueries.length} setup quer${database.setupQueries.length === 1 ? 'y' : 'ies'}`;
});

check('the identity hash is consistent with the setup queries', () => {
  const master = parsed.database.setupQueries.find((q) => q.includes('room_master_table') && q.includes('INSERT'));
  if (!master) throw new Error('no room_master_table insert statement found');
  if (!master.includes(parsed.database.identityHash)) {
    throw new Error('setupQueries does not carry the identityHash from database.identityHash');
  }
  return 'consistent';
});

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

const sha256 = raw ? createHash('sha256').update(raw).digest('hex') : null;

if (!process.argv.includes('--quiet')) {
  console.log('\nRoom schema export');
  console.log(lines.join('\n'));
  if (sha256) {
    console.log(`\n  path   ${schemaPath.replace(ROOT + '/', '')}`);
    console.log(`  size   ${raw.length} bytes`);
    console.log(`  sha256 ${sha256}`);
  }
}

console.log(`\n${checks.length - failures}/${checks.length} checks passed, ${failures} failed`);
if (failures > 0) {
  console.error(`\n${failures} Room schema check(s) failed.`);
  process.exit(1);
}
