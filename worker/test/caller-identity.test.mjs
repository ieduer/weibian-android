import assert from 'node:assert/strict';
import test from 'node:test';

import worker from '../src/index.js';

test('caller check uses the existing APIS binding identity without an invented Origin', async () => {
  let upstreamRequest = null;
  const response = await worker.fetch(new Request('https://weibian.bdfz.net/__caller-check'), {
    APIS_CALLER_TOKEN: 'test-token',
    APIS: {
      async fetch(input, init) {
        upstreamRequest = new Request(input, init);
        return Response.json({
          ok: true,
          callerId: 'weibian',
          identityStatus: 'verified',
          requestId: 'request-1',
        });
      },
    },
  });

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    ok: true,
    callerId: 'weibian',
    identityStatus: 'verified',
    requestId: 'request-1',
  });
  assert.equal(upstreamRequest.url, 'https://apis.bdfz.net/caller-identity');
  assert.equal(upstreamRequest.method, 'POST');
  assert.equal(upstreamRequest.headers.get('X-Project-Name'), 'weibian');
  assert.equal(upstreamRequest.headers.get('X-Internal-Token'), 'test-token');
  assert.equal(upstreamRequest.headers.has('Origin'), false);
});

test('caller check fails closed when its binding identity is unavailable', async () => {
  const response = await worker.fetch(new Request('https://weibian.bdfz.net/__caller-check'), {});

  assert.equal(response.status, 503);
  assert.deepEqual(await response.json(), {
    ok: false,
    callerId: 'weibian',
    identityStatus: 'configuration_unavailable',
    requestId: null,
  });
});

test('caller check rejects non-GET methods before calling APIS', async () => {
  let called = false;
  const response = await worker.fetch(new Request('https://weibian.bdfz.net/__caller-check', {
    method: 'HEAD',
  }), {
    APIS_CALLER_TOKEN: 'test-token',
    APIS: {
      async fetch() {
        called = true;
        return Response.json({});
      },
    },
  });

  assert.equal(response.status, 405);
  assert.equal(response.headers.get('Allow'), 'GET');
  assert.equal(called, false);
});
