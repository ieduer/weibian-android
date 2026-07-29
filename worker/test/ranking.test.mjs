import assert from 'node:assert/strict';
import test from 'node:test';
import {
  beijingDayKey,
  rankForPoints,
  requireRankingPepper,
  summarizeProgress,
} from '../src/ranking.js';

test('summarizes only bounded Weibian chapter progress', () => {
  const summary = summarizeProgress({
    items: [
      {
        itemKey: 'chapter-1',
        state: 'in_progress',
        meta: { progressPercent: 30, clientUpdatedAt: '2026-07-29T01:00:00Z' },
      },
      {
        itemKey: 'chapter-1',
        state: 'completed',
        meta: { progressPercent: 100, clientUpdatedAt: '2026-07-29T02:00:00Z' },
      },
      { itemKey: 'chapter-2', score: 40 },
      { itemKey: 'other', state: 'completed', score: 100 },
      { itemKey: 'chapter-999', state: 'completed', score: 100 },
    ],
  });

  assert.equal(summary.totalPoints, 140);
  assert.equal(summary.completedChapters, 1);
  assert.equal(summary.activeChapters, 2);
  assert.equal(summary.sourceUpdatedAt, '2026-07-29T02:00:00Z');
});

test('malformed progress grants no points', () => {
  assert.equal(summarizeProgress(null).totalPoints, 0);
  assert.equal(summarizeProgress({ items: 'invalid' }).activeChapters, 0);
});

test('rank thresholds match the native progression ladder', () => {
  assert.equal(rankForPoints(299).name, '童蒙');
  assert.equal(rankForPoints(300).name, '志学');
  assert.equal(rankForPoints(24_000).name, '从心');
});

test('daily board uses the Beijing date boundary', () => {
  assert.equal(beijingDayKey(new Date('2026-07-28T16:30:00Z')), '2026-07-29');
});

test('ranking identity hashing fails closed without a strong server secret', () => {
  assert.throws(() => requireRankingPepper('short'), /ranking-pepper-missing/);
  assert.equal(requireRankingPepper('x'.repeat(32)).length, 32);
});
