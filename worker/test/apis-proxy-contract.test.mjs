import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const worker = readFileSync(new URL('../src/index.js', import.meta.url), 'utf8');
const config = readFileSync(new URL('../wrangler.toml', import.meta.url), 'utf8');
const client = readFileSync(new URL('../../app/src/main/java/net/bdfz/weibian/network/ApiClient.kt', import.meta.url), 'utf8');
const build = readFileSync(new URL('../../app/build.gradle.kts', import.meta.url), 'utf8');

test('native app calls only the same-product AI proxy', () => {
  assert.match(client, /aiGatewayUrl\.trimEnd\('\/'\) \+ "\/api\/ai"/);
  assert.doesNotMatch(client, /apis\.bdfz\.net/);
  assert.doesNotMatch(client, /X-Internal-Token|APIS_CALLER_TOKEN/);
  assert.match(build, /AI_GATEWAY_URL", "\\"https:\/\/weibian\.bdfz\.net\\""/);
  assert.match(build, /versionCode = 5/);
});

test('content Worker proxy is APIS binding-only and fail-closed', () => {
  assert.match(worker, /env\.APIS\.fetch\('https:\/\/apis\.bdfz\.net\/'/);
  assert.match(worker, /env\.APIS_CALLER_TOKEN/);
  assert.match(worker, /'X-Project-Name': 'weibian'/);
  assert.doesNotMatch(worker, /(?<!\.)(?<![A-Za-z])fetch\(['"]https:\/\/apis\.bdfz\.net/);
  assert.match(config, /binding = "APIS"\s+service = "apis"/s);
});
