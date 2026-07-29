import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repository = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
);
const validator = path.join(repository, 'scripts', 'verify_android_release.mjs');
const signer = 'a40f3956296d09ca2c6d8c3ec23f4f1d5470cb8ca6a5d4a69a9f19eb39941282';

async function fixture(t) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'weibian-release-test-'));
  t.after(() => rm(directory, { recursive: true, force: true }));

  const apk = path.join(directory, 'candidate.apk');
  const bytes = Buffer.from('signed-weibian-apk-fixture');
  await writeFile(apk, bytes);
  const sha256 = createHash('sha256').update(bytes).digest('hex');

  const aapt2 = path.join(directory, 'aapt2');
  await writeFile(
    aapt2,
    `#!/usr/bin/env node
process.stdout.write("package: name='net.bdfz.weibian.direct' versionCode='3' versionName='1.1.1'\\nminSdkVersion:'23'\\n");
`,
  );
  await chmod(aapt2, 0o755);

  const apksigner = path.join(directory, 'apksigner');
  await writeFile(
    apksigner,
    `#!/usr/bin/env node
process.stdout.write([
  'Verifies',
  'Verified using v1 scheme (JAR signing): true',
  'Verified using v2 scheme (APK Signature Scheme v2): true',
  'Number of signers: 1',
  'V2 Signer: certificate SHA-256 digest: ${signer}',
  '',
].join('\\n'));
`,
  );
  await chmod(apksigner, 0o755);

  const metadata = {
    schema: 'bdfz-android-update-v1',
    appId: 'net.bdfz.weibian.direct',
    version: '1.1.1',
    versionCode: 3,
    minAndroidApi: 23,
    apkUrl:
      `https://img.bdfz.net/apps/weibian-android/releases/v1.1.1/` +
      `${sha256.slice(0, 8)}/weibian-1.1.1.apk`,
    sha256,
    size: bytes.length,
    publishedAt: '2026-07-29T15:21:44Z',
    releaseNotes: ['修复个人中心崩溃'],
    mandatory: false,
  };

  return { directory, apk, aapt2, apksigner, metadata };
}

async function runValidator(t, mutate = (metadata) => metadata) {
  const files = await fixture(t);
  const metadataPath = path.join(files.directory, 'release.json');
  await writeFile(metadataPath, `${JSON.stringify(mutate({ ...files.metadata }))}\n`);
  return spawnSync(
    process.execPath,
    [
      validator,
      '--apk',
      files.apk,
      '--metadata',
      metadataPath,
      '--aapt2',
      files.aapt2,
      '--apksigner',
      files.apksigner,
      '--expected-signer',
      signer,
    ],
    { encoding: 'utf8' },
  );
}

test('accepts an exact signed immutable release', async (t) => {
  const result = await runValidator(t);
  assert.equal(result.status, 0, result.stderr);
  const receipt = JSON.parse(result.stdout);
  assert.equal(receipt.ok, true);
  assert.equal(receipt.appId, 'net.bdfz.weibian.direct');
  assert.deepEqual(receipt.signatures, ['v1', 'v2']);
});

test('rejects a base-package metadata mismatch', async (t) => {
  const result = await runValidator(t, (metadata) => ({
    ...metadata,
    appId: 'net.bdfz.weibian',
  }));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /metadata package does not match/);
});

test('rejects a query on the immutable APK URL', async (t) => {
  const result = await runValidator(t, (metadata) => ({
    ...metadata,
    apkUrl: `${metadata.apkUrl}?candidate=1`,
  }));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /exact immutable artifact path/);
});

test('rejects an incomplete release contract', async (t) => {
  const result = await runValidator(t, (metadata) => ({
    ...metadata,
    releaseNotes: [],
  }));
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /releaseNotes are invalid/);
});
