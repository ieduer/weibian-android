#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const lockPath = path.join(repository, 'content/public-content-lock.json');
const lock = JSON.parse(await readFile(lockPath, 'utf8'));
const manifest = lock.manifest;

if (
  manifest?.schema !== 'lunyu-content-v1' ||
  manifest?.schemaVersion !== 1 ||
  manifest?.contentId !== 'lunyu-yizhu' ||
  !/^[a-f0-9]{16}$/.test(manifest?.contentVersion || '') ||
  !/^[a-f0-9]{64}$/.test(manifest?.sha256 || '') ||
  !Number.isInteger(manifest?.size) ||
  manifest.size < 1 ||
  manifest.size > 8 * 1024 * 1024
) {
  throw new Error('public content lock is invalid');
}

const source = new URL(lock.sourceUrl);
const expectedPath =
  `/apps/weibian-content/releases/${manifest.contentVersion}/${manifest.sha256}.json`;
if (
  source.protocol !== 'https:' ||
  source.hostname !== 'img.bdfz.net' ||
  source.pathname !== expectedPath ||
  source.search ||
  source.hash
) {
  throw new Error('public content source is not the locked immutable object');
}

const response = await fetch(source, {
  headers: { Accept: 'application/json' },
  signal: AbortSignal.timeout(30_000),
});
if (!response.ok) throw new Error(`content download failed: HTTP ${response.status}`);
const declaredLengthHeader = response.headers.get('content-length');
const declaredLength = Number(declaredLengthHeader);
if (
  declaredLengthHeader !== null &&
  Number.isFinite(declaredLength) &&
  declaredLength !== manifest.size
) {
  throw new Error('content length header does not match lock');
}
if (!response.body) throw new Error('content response has no body');

const chunks = [];
let received = 0;
for await (const chunk of response.body) {
  received += chunk.byteLength;
  if (received > manifest.size || received > 8 * 1024 * 1024) {
    throw new Error('content response exceeded locked size');
  }
  chunks.push(Buffer.from(chunk));
}
const bytes = Buffer.concat(chunks, received);
if (bytes.length !== manifest.size) throw new Error('content size does not match lock');
const digest = createHash('sha256').update(bytes).digest('hex');
if (digest !== manifest.sha256) throw new Error('content digest does not match lock');

const parsed = JSON.parse(bytes.toString('utf8'));
if (
  parsed?.chapters?.length !== manifest.counts.chapters ||
  parsed?.books?.length !== manifest.counts.books ||
  parsed?.concepts?.length !== manifest.counts.concepts ||
  parsed?.figures?.length !== manifest.counts.figures ||
  parsed?.bank?.length !== manifest.counts.bank ||
  parsed?.gaokao?.length !== manifest.counts.gaokao ||
  Object.keys(parsed?.aliases || {}).length !== manifest.counts.aliases
) {
  throw new Error('content payload does not match the locked schema/counts');
}

const manifestBytes = Buffer.from(`${JSON.stringify(manifest, null, 2)}\n`);
for (const [relative, body] of [
  ['app/src/main/assets/content.json', bytes],
  ['app/src/main/assets/content-manifest.json', manifestBytes],
  ['worker/public/content.json', bytes],
  ['worker/public/manifest.json', manifestBytes],
]) {
  const destination = path.join(repository, relative);
  const staged = `${destination}.staged`;
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(staged, body, { mode: 0o600 });
  await rename(staged, destination);
}

process.stdout.write(
  `Bootstrapped ${manifest.contentVersion} (${manifest.size} bytes, ${manifest.sha256})\n`,
);
