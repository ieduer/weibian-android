import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstat, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const contractPath = resolve(
  repoRoot,
  'contracts/weibian-first-answer-event-v2-candidate.json',
);
const contract = JSON.parse(await readFile(contractPath, 'utf8'));

function fail(message) {
  throw new Error(`candidate-source-verification: ${message}`);
}

function git(...args) {
  return execFileSync('git', ['-C', repoRoot, ...args], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

function lines(value) {
  return String(value || '').split('\n').map((line) => line.trim()).filter(Boolean);
}

async function digest(path) {
  return createHash('sha256').update(await readFile(resolve(repoRoot, path))).digest('hex');
}

if (contract.status !== 'blocked_inactive_source_only') fail('status must remain blocked');
if (contract.eventPolicy?.mappingDisposition !== 'pending_mapping') {
  fail('mapping must remain pending_mapping');
}
if (
  contract.eventPolicy?.rawValue !== null
  || contract.eventPolicy?.maxValue !== null
  || contract.eventPolicy?.normalizedValue !== null
) {
  fail('candidate score values must remain null');
}
const requiredFalse = [
  'runtimeImported',
  'routeConnected',
  'bindingConfigured',
  'migrationApplied',
  'deliveryEnabled',
  'scoringActive',
  'productionDeploymentAuthorized',
  'activationAllowed',
];
for (const key of requiredFalse) {
  if (contract.activation?.[key] !== false) fail(`activation.${key} must be false`);
}
if (
  contract.identityAuthority?.publicApiSessionAccepted !== false
  || contract.identityAuthority?.currentIdentityRpcConnected !== false
  || contract.identityAuthority?.currentSourceIdentityResolverConnected !== false
  || contract.identityAuthority?.currentWeibianNamedEntrypointExistsAtAuditedMain !== false
  || contract.identityAuthority?.sameBoundedCookieRequired !== true
  || contract.identityAuthority?.sourceOwnerResolvedBeforeLedger !== true
  || contract.identityAuthority?.projectAcceptsOwnerUserKey !== false
  || contract.identityAuthority?.callerSuppliedOwnerUserKeyAccepted !== false
) {
  fail('identity blockers must remain explicit');
}
if (
  contract.identityAuthority?.requiredDependencies?.identityRpc?.method !== 'resolveSession'
  || contract.identityAuthority?.requiredDependencies?.sourceIdentity?.method !== 'resolveOwner'
) {
  fail('dual identity dependency contract drifted');
}

const sourceMain = String(contract.sourceMain || '');
if (!/^[a-f0-9]{40}$/.test(sourceMain)) fail('sourceMain must be an exact commit');
git('cat-file', '-e', `${sourceMain}^{commit}`);
if (git('merge-base', sourceMain, 'HEAD') !== sourceMain) {
  fail('candidate HEAD is not descended from sourceMain');
}

const changed = new Set([
  ...lines(git('diff', '--name-only', `${sourceMain}...HEAD`)),
  ...lines(git('diff', '--name-only')),
  ...lines(git('diff', '--cached', '--name-only')),
  ...lines(git('ls-files', '--others', '--exclude-standard')),
]);
const allowed = new Set(contract.governance?.allowedChangedPaths || []);
if (!allowed.size) fail('allowed changed path list is empty');
for (const path of changed) {
  if (!allowed.has(path)) fail(`protected or unexpected path changed: ${path}`);
}
for (const path of allowed) {
  if (!changed.has(path)) fail(`declared candidate path is not part of the change: ${path}`);
  const stats = await lstat(resolve(repoRoot, path));
  if (!stats.isFile() || stats.isSymbolicLink()) fail(`candidate path is not a regular file: ${path}`);
}

const protectedSurfaceDigests = contract.governance?.protectedSurfaceDigests;
if (!Array.isArray(protectedSurfaceDigests) || protectedSurfaceDigests.length < 1) {
  fail('protected surface digest list is empty');
}
for (const { path, sha256: expected } of protectedSurfaceDigests) {
  if (typeof path !== 'string' || !/^[a-f0-9]{64}$/.test(expected || '')) {
    fail('protected surface digest entry invalid');
  }
  const actual = await digest(path);
  if (actual !== expected) fail(`protected surface digest changed: ${path}`);
}

const runtimeFiles = [
  'worker/src/index.js',
  'worker/src/ranking.js',
  'worker/wrangler.toml',
];
for (const path of runtimeFiles) {
  const source = await readFile(resolve(repoRoot, path), 'utf8');
  for (const marker of [
    'candidate/weibian-event-v2',
    'weibian-first-answer-event-v2-candidate-v1',
    'WeibianGrowthEvidence',
  ]) {
    if (source.includes(marker)) fail(`runtime surface imports or configures candidate marker: ${path}`);
  }
}

const packageJson = JSON.parse(await readFile(resolve(repoRoot, 'package.json'), 'utf8'));
if (packageJson.private !== true) fail('package must remain private');
if (packageJson.dependencies || packageJson.devDependencies || packageJson.optionalDependencies) {
  fail('candidate must remain dependency-free');
}

const adapterSource = await readFile(
  resolve(repoRoot, 'candidate/weibian-event-v2/adapter.mjs'),
  'utf8',
);
if (/async\s+project\s*\(\s*\{[^}]*ownerUserKey/s.test(adapterSource)) {
  fail('project must not accept caller-supplied ownerUserKey');
}
if (/export\s+(?:async\s+)?function\s+projectVerifiedFirstAnswerEventV2/.test(adapterSource)) {
  fail('owner-key projection primitive must not be exported');
}
for (const requiredMethod of ['identityRpc.resolveSession(cookie)', 'sourceIdentity.resolveOwner(cookie)']) {
  if (!adapterSource.includes(requiredMethod)) fail(`adapter dependency missing: ${requiredMethod}`);
}
if (packageJson.engines?.node !== '22.21.1 || 24.18.0') {
  fail('Node authority drifted');
}

const workflow = await readFile(
  resolve(repoRoot, '.github/workflows/weibian-event-v2-candidate-pr.yml'),
  'utf8',
);
if (!/^\s*pull_request:\s*$/m.test(workflow)) fail('candidate workflow must run on pull_request');
for (const forbiddenTrigger of ['push:', 'workflow_dispatch:', 'schedule:']) {
  if (workflow.includes(forbiddenTrigger)) fail(`candidate workflow trigger forbidden: ${forbiddenTrigger}`);
}
for (const version of contract.node.pullRequestMatrix) {
  if (!workflow.includes(`- ${version}`)) fail(`candidate workflow is missing Node ${version}`);
}

const candidateText = (
  await Promise.all([...allowed].map((path) => readFile(resolve(repoRoot, path), 'utf8')))
).join('\n');
for (const secretPattern of [
  /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/,
  /\bBearer\s+[A-Za-z0-9._~+/-]{20,}/,
  /\bCLOUDFLARE_API_TOKEN\s*=/,
  /\bRANKING_PEPPER\s*=/,
]) {
  if (secretPattern.test(candidateText)) fail('candidate contains a secret-shaped value');
}

console.log(JSON.stringify({
  ok: true,
  sourceMain,
  changedPaths: [...changed].sort(),
  protectedSurfaceCount: protectedSurfaceDigests.length,
  activationAllowed: false,
  mappingDisposition: 'pending_mapping',
}));
