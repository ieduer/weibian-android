import assert from 'node:assert/strict';
import test from 'node:test';
import {
  contentReleaseForVersion,
  deltaObjectKey,
} from '../src/content-releases.js';

test('maps the accepted content version to an exact immutable R2 object', () => {
  const release = contentReleaseForVersion('fc68413c7b70da0e');
  assert.equal(release?.size, 871_333);
  assert.equal(
    release?.sha256,
    'fc68413c7b70da0e1f14e36bb2229c4d9ae64fb8f26d75251f87d7457f8ffa75',
  );
  assert.match(release?.key || '', /\/fc68413c7b70da0e\/fc68413c.*\.json$/);
  assert.equal(contentReleaseForVersion('unknown'), null);
});

test('allows only bounded content-delta object names', () => {
  assert.equal(
    deltaObjectKey('12345678-abcdef09.json'),
    'apps/weibian-content/deltas/12345678-abcdef09.json',
  );
  assert.equal(deltaObjectKey('../manifest.json'), null);
});
