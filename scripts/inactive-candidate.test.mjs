import assert from 'node:assert/strict';
import test from 'node:test';
import { loadSnapshot, verifySnapshot } from './verify-inactive-candidate.mjs';

const baseline = loadSnapshot();
const snapshot = () => ({ ...baseline, current: new Map(baseline.current),
  modes: new Map(baseline.modes), historical: new Map(baseline.historical),
  contract: structuredClone(baseline.contract) });
const change = (state, path, text) => {
  state.current.set(path, Buffer.from(text)); state.modes.set(path, 'regular');
};

test('accepted product source passes without enlarging the historical allowlist', () => {
  const result = verifySnapshot(snapshot());
  assert.equal(result.ok, true);
  assert.equal(result.historicalChangedPaths, 9);
  assert.equal(result.activationAllowed, false);
  assert.ok(result.runtimeFiles > 50);
});
test('ordinary UI text outside protected identity/data files is allowed', () => {
  const state = snapshot();
  change(state, 'app/src/main/java/net/bdfz/weibian/ui/Fixture.kt', 'val title = "來源核對"');
  assert.equal(verifySnapshot(state).ok, true);
});
for (const path of ['contracts/weibian-first-answer-event-v2-candidate.json',
  'candidate/weibian-event-v2/adapter.mjs', 'candidate/weibian-event-v2/adapter.test.mjs',
  'candidate/weibian-event-v2/verify-source-scope.mjs', 'worker/src/index.js',
  'worker/wrangler.toml', 'app/src/main/java/net/bdfz/weibian/data/Daos.kt',
  'app/src/main/java/net/bdfz/weibian/data/LearningRepository.kt',
  '.github/workflows/verify.yml']) {
  test(`rejects drift in ${path}`, () => {
    const state = snapshot();
    change(state, path, `${state.current.get(path)}\n// changed`);
    assert.throws(() => verifySnapshot(state), /reviewed source changed/);
  });
}
for (const path of ['worker/src/new-adapter.js',
  'app/src/main/java/net/bdfz/weibian/ui/Connected.kt']) {
  test(`rejects newly connected candidate in ${path}`, () => {
    const state = snapshot();
    change(state, path, 'import { adapter } from "../../candidate/weibian-event-v2/adapter.mjs"');
    assert.throws(() => verifySnapshot(state), /runtime candidate reference/);
  });
}
test('rejects new migrations', () => {
  const state = snapshot(); change(state, 'worker/migrations/0003_candidate.sql', 'CREATE TABLE candidate(id);');
  assert.throws(() => verifySnapshot(state), /migration or Room schema inventory changed/);
});
test('rejects extra candidate files and direct factory wiring from a flavor source', () => {
  const state = snapshot(); change(state, 'candidate/weibian-event-v2/connected.mjs', 'export const active = true;');
  assert.throws(() => verifySnapshot(state), /frozen candidate file inventory/);
  const flavor = snapshot(); change(flavor, 'app/src/direct/java/Connected.kt', 'createWeibianFirstAnswerEventV2Candidate(deps)');
  assert.throws(() => verifySnapshot(flavor), /runtime candidate reference/);
});
test('rejects missing protected files and symlink runtime files', () => {
  const state = snapshot(); state.current.delete('worker/src/ranking.js');
  assert.throws(() => verifySnapshot(state), /reviewed source changed/);
  const symlink = snapshot(); symlink.modes.set('worker/src/link.js', 'invalid');
  assert.throws(() => verifySnapshot(symlink), /not a regular file/);
});
test('rejects falsified historical scope or protected bytes', () => {
  const state = snapshot(); state.historicalChanged = [...state.historicalChanged, 'README.md'];
  assert.throws(() => verifySnapshot(state), /historical source-only scope/);
  const drift = snapshot(); drift.historical.set('worker/src/index.js', Buffer.from('changed'));
  assert.throws(() => verifySnapshot(drift), /historical protected surface changed/);
});
test('rejects activation and numeric grading', () => {
  const active = snapshot(); active.contract.activation.scoringActive = true;
  assert.throws(() => verifySnapshot(active), /must remain inactive/);
  const score = snapshot(); score.contract.eventPolicy.rawValue = 0;
  assert.throws(() => verifySnapshot(score), /must remain inactive/);
});
test('rejects added dependencies and non-PR triggers', () => {
  const state = snapshot(); const pkg = JSON.parse(state.current.get('package.json'));
  pkg.dependencies = { unreviewed: '1' }; change(state, 'package.json', JSON.stringify(pkg));
  assert.throws(() => verifySnapshot(state), /dependency-free package/);
  const scheduled = snapshot(); const path = '.github/workflows/weibian-event-v2-candidate-pr.yml';
  change(scheduled, path, `${scheduled.current.get(path)}\nschedule:\n`);
  assert.throws(() => verifySnapshot(scheduled), /PR-only dual-Node/);
});
