#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import process from 'node:process';

const REQUIRED_FLAGS = new Set([
  '--apk',
  '--metadata',
  '--aapt2',
  '--apksigner',
  '--expected-signer',
  '--expected-app-id',
  '--expected-version',
  '--expected-version-code',
  '--previous-version-code',
]);
const MAX_APK_BYTES = 512 * 1024 * 1024;
const MAX_METADATA_BYTES = 16 * 1024;
const SHA256_RE = /^[0-9a-f]{64}$/;
const SEMVER_RE = /^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$/;
const APPLICATION_ID_RE = /^[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+$/;
const PUBLISHED_AT_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/;

class VerificationError extends Error {}

function reject(message) {
  throw new VerificationError(message);
}

function usage() {
  return [
    'Usage: node scripts/verify_android_release.mjs',
    '  --apk <signed.apk> --metadata <release.json>',
    '  --aapt2 <aapt2> --apksigner <apksigner>',
    '  --expected-signer <64-hex-certificate-sha256>',
    '  --expected-app-id <canonical.application.id>',
    '  --expected-version <semver> --expected-version-code <positive-int>',
    '  --previous-version-code <non-negative-int>',
  ].join('\n');
}

function parseIntegerFlag(value, flag, minimum) {
  if (!/^(?:0|[1-9][0-9]*)$/.test(value)) reject(`${flag} must be an integer`);
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum) {
    reject(`${flag} is outside the accepted range`);
  }
  return parsed;
}

function parseArguments(argv) {
  if (argv.length === 1 && (argv[0] === '--help' || argv[0] === '-h')) {
    return { help: true };
  }

  const parsed = {};
  for (let index = 0; index < argv.length; index += 2) {
    const flag = argv[index];
    const value = argv[index + 1];
    if (!REQUIRED_FLAGS.has(flag) || value === undefined || value.startsWith('--')) {
      reject('invalid command-line arguments');
    }
    if (Object.hasOwn(parsed, flag)) reject(`duplicate ${flag} argument`);
    parsed[flag] = value;
  }
  for (const flag of REQUIRED_FLAGS) {
    if (!Object.hasOwn(parsed, flag)) reject(`missing ${flag} argument`);
  }

  const expectedSigner = parsed['--expected-signer'].toLowerCase();
  if (!SHA256_RE.test(expectedSigner)) {
    reject('expected signer must be a 64-character SHA-256 digest');
  }
  const expectedAppId = parsed['--expected-app-id'];
  if (!APPLICATION_ID_RE.test(expectedAppId)) {
    reject('expected app id is invalid');
  }
  const expectedVersion = parsed['--expected-version'];
  if (!SEMVER_RE.test(expectedVersion)) {
    reject('expected version is invalid');
  }
  const expectedVersionCode = parseIntegerFlag(
    parsed['--expected-version-code'],
    'expected versionCode',
    1,
  );
  const previousVersionCode = parseIntegerFlag(
    parsed['--previous-version-code'],
    'previous versionCode',
    0,
  );
  if (expectedVersionCode <= previousVersionCode) {
    reject('expected versionCode must be greater than previous versionCode');
  }

  return {
    apk: parsed['--apk'],
    metadata: parsed['--metadata'],
    aapt2: parsed['--aapt2'],
    apksigner: parsed['--apksigner'],
    expectedSigner,
    expectedAppId,
    expectedVersion,
    expectedVersionCode,
    previousVersionCode,
  };
}

async function hashApk(apkPath) {
  const before = await stat(apkPath).catch(() => reject('APK is not readable'));
  if (!before.isFile() || before.size < 1 || before.size > MAX_APK_BYTES) {
    reject('APK size is outside the accepted range');
  }

  const digest = createHash('sha256');
  let bytes = 0;
  for await (const chunk of createReadStream(apkPath)) {
    bytes += chunk.byteLength;
    if (bytes > before.size || bytes > MAX_APK_BYTES) {
      reject('APK changed or exceeded the accepted size while hashing');
    }
    digest.update(chunk);
  }

  const after = await stat(apkPath).catch(() => reject('APK disappeared while hashing'));
  if (
    bytes !== before.size ||
    after.size !== before.size ||
    after.mtimeMs !== before.mtimeMs ||
    after.ino !== before.ino
  ) {
    reject('APK changed while it was being verified');
  }

  return { sha256: digest.digest('hex'), size: bytes };
}

async function loadMetadata(metadataPath) {
  const details = await stat(metadataPath).catch(() => reject('metadata is not readable'));
  if (!details.isFile() || details.size < 2 || details.size > MAX_METADATA_BYTES) {
    reject('metadata size is outside the accepted range');
  }

  try {
    const parsed = JSON.parse(await readFile(metadataPath, 'utf8'));
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      reject('metadata root must be an object');
    }
    return parsed;
  } catch (error) {
    if (error instanceof VerificationError) throw error;
    reject('metadata is not valid JSON');
  }
}

function runTool(label, executable, argumentsList) {
  const result = spawnSync(executable, argumentsList, {
    encoding: 'utf8',
    maxBuffer: 4 * 1024 * 1024,
    timeout: 30_000,
    windowsHide: true,
  });
  if (result.error || result.signal || result.status !== 0) {
    reject(`${label} rejected the APK or could not run`);
  }
  return `${result.stdout || ''}\n${result.stderr || ''}`;
}

function inspectPackage(aapt2, apkPath) {
  const output = runTool('aapt2', aapt2, ['dump', 'badging', apkPath]);
  const packageMatch = output.match(
    /^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'/m,
  );
  const minSdkMatch = output.match(/^minSdkVersion:'([0-9]+)'$/m);
  if (!packageMatch || !minSdkMatch) reject('aapt2 output is missing release identity');

  const versionCode = Number(packageMatch[2]);
  const minSdk = Number(minSdkMatch[1]);
  if (
    !Number.isSafeInteger(versionCode) ||
    versionCode < 1 ||
    !Number.isSafeInteger(minSdk) ||
    minSdk < 1
  ) {
    reject('aapt2 returned invalid numeric release identity');
  }

  return {
    appId: packageMatch[1],
    versionCode,
    versionName: packageMatch[3],
    minSdk,
  };
}

function inspectSignature(apksigner, apkPath, expectedSigner) {
  const output = runTool('apksigner', apksigner, [
    'verify',
    '--verbose',
    '--print-certs',
    apkPath,
  ]);
  if (!/^Verified using v1 scheme \(JAR signing\): true$/m.test(output)) {
    reject('APK is missing a verified v1 signature');
  }
  if (!/^Verified using v2 scheme \(APK Signature Scheme v2\): true$/m.test(output)) {
    reject('APK is missing a verified v2 signature');
  }

  const signerCount = output.match(/^Number of signers: ([0-9]+)$/m);
  const certificates = [
    ...output.matchAll(/certificate SHA-256 digest:\s*([0-9a-fA-F]{64})/g),
  ].map((match) => match[1].toLowerCase());
  if (!signerCount || Number(signerCount[1]) !== 1 || certificates.length !== 1) {
    reject('APK must have exactly one signing certificate');
  }
  if (certificates[0] !== expectedSigner) reject('APK signing certificate does not match');

  return certificates[0];
}

function verifyExpectedIdentity(identity, expected) {
  if (identity.appId !== expected.expectedAppId) {
    reject('APK package does not match the expected app id');
  }
  if (identity.versionName !== expected.expectedVersion) {
    reject('APK version does not match the expected version');
  }
  if (identity.versionCode !== expected.expectedVersionCode) {
    reject('APK versionCode does not match the expected versionCode');
  }
  if (identity.versionCode <= expected.previousVersionCode) {
    reject('APK versionCode is not greater than the previous accepted code');
  }
}

function verifyMetadata(metadata, identity, apk) {
  if (metadata.schema !== 'bdfz-android-update-v1') reject('metadata schema is invalid');
  if (metadata.appId !== identity.appId) reject('metadata package does not match the APK');
  if (metadata.version !== identity.versionName || !SEMVER_RE.test(metadata.version)) {
    reject('metadata version does not match the APK');
  }
  if (metadata.versionCode !== identity.versionCode) {
    reject('metadata versionCode does not match the APK');
  }
  if (metadata.minAndroidApi !== identity.minSdk) {
    reject('metadata minAndroidApi does not match the APK');
  }
  if (!SHA256_RE.test(metadata.sha256 || '') || metadata.sha256 !== apk.sha256) {
    reject('metadata SHA-256 does not match the APK');
  }
  if (!Number.isSafeInteger(metadata.size) || metadata.size !== apk.size) {
    reject('metadata size does not match the APK');
  }
  if (!PUBLISHED_AT_RE.test(metadata.publishedAt || '')) {
    reject('metadata publishedAt is invalid');
  }
  const parsedPublishedAt = new Date(metadata.publishedAt);
  const normalizedPublishedAt = metadata.publishedAt.includes('.')
    ? metadata.publishedAt
    : metadata.publishedAt.replace(/Z$/, '.000Z');
  if (
    Number.isNaN(parsedPublishedAt.getTime()) ||
    parsedPublishedAt.toISOString() !== normalizedPublishedAt
  ) {
    reject('metadata publishedAt is invalid');
  }
  if (
    !Array.isArray(metadata.releaseNotes) ||
    metadata.releaseNotes.length < 1 ||
    metadata.releaseNotes.length > 10 ||
    metadata.releaseNotes.some(
      (note) =>
        typeof note !== 'string' ||
        note.trim().length < 1 ||
        note.trim().length > 200,
    )
  ) {
    reject('metadata releaseNotes are invalid');
  }
  if (typeof metadata.mandatory !== 'boolean') {
    reject('metadata mandatory flag is invalid');
  }

  let artifact;
  try {
    artifact = new URL(metadata.apkUrl);
  } catch {
    reject('metadata APK URL is invalid');
  }
  const expectedPath =
    `/apps/weibian-android/releases/v${identity.versionName}/` +
    `${apk.sha256.slice(0, 8)}/weibian-${identity.versionName}.apk`;
  if (
    artifact.protocol !== 'https:' ||
    artifact.hostname !== 'img.bdfz.net' ||
    artifact.port ||
    artifact.username ||
    artifact.password ||
    artifact.pathname !== expectedPath ||
    artifact.search ||
    artifact.hash
  ) {
    reject('metadata APK URL is not the exact immutable artifact path');
  }

  return artifact.href;
}

async function main() {
  const options = parseArguments(process.argv.slice(2));
  if (options.help) {
    process.stdout.write(`${usage()}\n`);
    return;
  }

  const [apk, metadata] = await Promise.all([
    hashApk(options.apk),
    loadMetadata(options.metadata),
  ]);
  const identity = inspectPackage(options.aapt2, options.apk);
  verifyExpectedIdentity(identity, options);
  const signerSha256 = inspectSignature(
    options.apksigner,
    options.apk,
    options.expectedSigner,
  );
  const apkUrl = verifyMetadata(metadata, identity, apk);

  process.stdout.write(
    `${JSON.stringify({
      ok: true,
      schema: metadata.schema,
      appId: identity.appId,
      version: identity.versionName,
      versionCode: identity.versionCode,
      previousVersionCode: options.previousVersionCode,
      minAndroidApi: identity.minSdk,
      sha256: apk.sha256,
      size: apk.size,
      signerSha256,
      signatures: ['v1', 'v2'],
      apkUrl,
    })}\n`,
  );
}

try {
  await main();
} catch (error) {
  const message =
    error instanceof VerificationError ? error.message : 'unexpected verification error';
  process.stderr.write(`release verification failed: ${message}\n`);
  process.exitCode = 1;
}
