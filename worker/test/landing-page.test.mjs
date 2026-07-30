import assert from 'node:assert/strict';
import test from 'node:test';
import worker from '../src/index.js';

test('production landing download points to the immutable v1.1.2 APK', async () => {
  const response = await worker.fetch(
    new Request('https://weibian.bdfz.net/'),
    {
      ASSETS: {
        async fetch() {
          return new Response('not found', { status: 404 });
        },
      },
    },
  );
  const html = await response.text();
  const downloadHref = html.match(
    /<a class="dl" href="([^"]+)">下载 Android 安装包<\/a>/,
  )?.[1];

  assert.equal(
    downloadHref,
    'https://img.bdfz.net/apps/weibian-android/releases/v1.1.2/956810c9/weibian-1.1.2.apk',
  );
});
