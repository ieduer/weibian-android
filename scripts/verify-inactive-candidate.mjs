import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { lstatSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// These are reviewed source anchors, never movable refs or production authority.
export const anchors = Object.freeze({
  candidate: '98590b1deb3e5bc0fffd4590cb1d2c41c32828c4',
  runtime: '921d36811c82c97254d07a050c32b9fc1850433a',
  landing: 'cc465135c0a9044a31ab6e087409c6e82a5d419b',
  androidCi: 'f8fd08ba06d44606295978f5789535cd08715ed8',
  notebookExport: '2754fe89efc69e5ef7fb1746711e54f155f070cf',
});
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const contractPath = 'contracts/weibian-first-answer-event-v2-candidate.json';
const frozen = [contractPath, 'candidate/weibian-event-v2/adapter.mjs',
  'candidate/weibian-event-v2/adapter.test.mjs',
  'candidate/weibian-event-v2/verify-source-scope.mjs'];
const runtimePath = (path) => path.startsWith('worker/src/')
  || /^worker\/wrangler[^/]*\.(?:toml|jsonc?)$/.test(path)
  || path.startsWith('worker/migrations/') || path.startsWith('app/src/')
  || path.startsWith('app/schemas/');
const migrationPath = (path) => path.startsWith('worker/migrations/')
  || path.startsWith('app/schemas/');
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fail = (message) => { throw new Error(`inactive-candidate-verification: ${message}`); };

export function loadSnapshot(repo = root) {
  const git = (...args) => execFileSync('git', ['-C', repo, ...args], {
    stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 8 * 1024 * 1024,
  });
  const paths = (...args) => git(...args).toString().split('\0').filter(Boolean);
  const blob = (ref, path) => git('show', `${ref}:${path}`);
  const contract = JSON.parse(blob(anchors.candidate, contractPath));
  for (const ref of [contract.sourceMain, ...Object.values(anchors)]) {
    if (git('merge-base', ref, 'HEAD').toString().trim() !== ref) {
      fail(`HEAD does not descend from reviewed anchor ${ref}`);
    }
  }
  const historical = new Map();
  const expected = new Map();
  for (const path of frozen) expected.set(path, blob(anchors.candidate, path));
  for (const { path } of contract.governance.protectedSurfaceDigests) {
    historical.set(path, blob(anchors.candidate, path));
    expected.set(path, blob(path === '.github/workflows/verify.yml'
      ? anchors.androidCi : path === 'worker/src/index.js'
        ? anchors.landing : path === 'app/src/main/java/net/bdfz/weibian/data/LearningRepository.kt'
          ? anchors.notebookExport : anchors.runtime, path));
  }
  const migrationPaths = paths('ls-tree', '-r', '--name-only', '-z', anchors.runtime)
    .filter(migrationPath);
  for (const path of migrationPaths) expected.set(path, blob(anchors.runtime, path));
  const current = new Map();
  const modes = new Map();
  const discovered = new Set([
    ...paths('ls-files', '-z'), ...paths('ls-files', '--others', '--exclude-standard', '-z'),
    ...expected.keys(),
  ]);
  for (const path of discovered) {
    if (!expected.has(path) && !runtimePath(path)
      && !path.startsWith('candidate/weibian-event-v2/') && !['package.json',
      '.github/workflows/weibian-event-v2-candidate-pr.yml'].includes(path)) continue;
    try {
      const stat = lstatSync(resolve(repo, path));
      modes.set(path, stat.isFile() && !stat.isSymbolicLink() ? 'regular' : 'invalid');
      if (modes.get(path) === 'regular') current.set(path, readFileSync(resolve(repo, path)));
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      modes.set(path, 'missing');
    }
  }
  return { contract, historical, expected, current, modes, migrationPaths,
    historicalChanged: paths('diff', '--name-only', '-z',
      `${contract.sourceMain}...${anchors.candidate}`) };
}

export function verifySnapshot(snapshot) {
  const { contract, historical, expected, current, modes } = snapshot;
  const allowed = [...contract.governance.allowedChangedPaths].sort();
  if (JSON.stringify([...snapshot.historicalChanged].sort()) !== JSON.stringify(allowed)) {
    fail('historical source-only scope differs from its nine-path contract');
  }
  for (const { path, sha256 } of contract.governance.protectedSurfaceDigests) {
    if (!historical.has(path) || hash(historical.get(path)) !== sha256) {
      fail(`historical protected surface changed: ${path}`);
    }
  }
  for (const [path, bytes] of expected) {
    if (modes.get(path) !== 'regular' || !current.has(path)
      || !bytes.equals(current.get(path))) fail(`reviewed source changed: ${path}`);
  }
  const candidatePaths = [...modes.keys()].filter((path) => path.startsWith('candidate/weibian-event-v2/')).sort();
  if (JSON.stringify(candidatePaths) !== JSON.stringify(frozen.filter((path) => path.startsWith('candidate/')).sort())) {
    fail('frozen candidate file inventory changed');
  }
  const requiredFalse = ['runtimeImported', 'routeConnected', 'bindingConfigured',
    'migrationApplied', 'deliveryEnabled', 'scoringActive',
    'productionDeploymentAuthorized', 'activationAllowed'];
  if (contract.status !== 'blocked_inactive_source_only'
    || requiredFalse.some((key) => contract.activation[key] !== false)
    || contract.eventPolicy.mappingDisposition !== 'pending_mapping'
    || ['rawValue', 'maxValue', 'normalizedValue'].some((key) => contract.eventPolicy[key] !== null)) {
    fail('candidate must remain inactive, unmapped and unscored');
  }
  const currentMigrations = [...modes.keys()].filter(migrationPath).sort();
  if (JSON.stringify(currentMigrations) !== JSON.stringify([...snapshot.migrationPaths].sort())) {
    fail('migration or Room schema inventory changed');
  }
  let runtimeFiles = 0;
  for (const [path, mode] of modes) {
    if (!runtimePath(path)) continue;
    if (mode !== 'regular') fail(`runtime source is missing or not a regular file: ${path}`);
    const text = current.get(path).toString('utf8');
    for (const marker of ['weibian-event-v2', 'weibian-first-answer-event-v2',
      'WeibianGrowthEvidence', 'projectVerifiedFirstAnswerEventV2',
      'createWeibianFirstAnswerEventV2', 'WeibianEventV2Candidate',
      'WEIBIAN_EVENT_V2_CANDIDATE']) {
      if (text.includes(marker)) fail(`runtime candidate reference: ${path}`);
    }
    runtimeFiles += 1;
  }
  const pkg = JSON.parse(current.get('package.json'));
  if (pkg.private !== true || pkg.dependencies || pkg.devDependencies || pkg.optionalDependencies
    || pkg.engines?.node !== '22.21.1 || 24.18.0'
    || pkg.scripts?.['verify:candidate'] !== 'node candidate/weibian-event-v2/verify-source-scope.mjs'
    || pkg.scripts?.['verify:inactive'] !== 'node scripts/verify-inactive-candidate.mjs') {
    fail('dependency-free package, Node or verification commands changed');
  }
  const workflow = current.get('.github/workflows/weibian-event-v2-candidate-pr.yml').toString();
  if (!/^\s*pull_request:\s*$/m.test(workflow)
    || ['push:', 'workflow_dispatch:', 'schedule:'].some((trigger) => workflow.includes(trigger))
    || !workflow.includes('run: npm run verify:inactive')
    || contract.node.pullRequestMatrix.some((version) => !workflow.includes(`- ${version}`))) {
    fail('PR-only dual-Node inactive gate changed');
  }
  return { ok: true, anchors, historicalSourceMain: contract.sourceMain,
    historicalChangedPaths: allowed.length, protectedSources: expected.size,
    runtimeFiles, activationAllowed: false, mappingDisposition: 'pending_mapping' };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(JSON.stringify(verifySnapshot(loadSnapshot()), null, 2));
}
