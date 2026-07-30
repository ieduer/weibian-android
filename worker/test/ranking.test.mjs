import assert from 'node:assert/strict';
import test from 'node:test';
import {
  ANSWER_EVENT_SCHEMA,
  beijingDayKey,
  buildAuthoredTaskIndex,
  parseAnswerEventBatch,
  rankForPoints,
  requireCompleteAuthoredTaskIndex,
  requireRankingPepper,
  validateAuthoredAnswer,
} from '../src/ranking.js';

const authored = {
  bank: [
    {
      id: 'cm-1-1a',
      refs: [1],
      answerId: 'a',
      options: [
        { id: 'a', text: '正确' },
        { id: 'b', text: '错误' },
      ],
    },
  ],
};

function envelope(overrides = {}) {
  return {
    schema: ANSWER_EVENT_SCHEMA,
    events: [
      {
        eventId: 'weibian_answer_00000001',
        contentVersion: 'fc68413c7b70da0e',
        taskId: 'cm-1-1a',
        chapterId: 1,
        chosenOptionId: 'a',
        ...overrides,
      },
    ],
  };
}

test('indexes only canonical authored tasks with stable options and answers', () => {
  const index = buildAuthoredTaskIndex(authored);
  assert.equal(index.size, 1);
  assert.equal(index.get('cm-1-1a').answerId, 'a');
  assert.match(index.get('cm-1-1a').semanticMaterial, /cm-1-1a/);
  assert.throws(
    () => buildAuthoredTaskIndex({ bank: [authored.bank[0], authored.bank[0]] }),
    /invalid-authored-task-bank/,
  );
  assert.throws(
    () => requireCompleteAuthoredTaskIndex(authored),
    /invalid-authored-task-bank-size/,
  );
});

test('client can submit only raw answer identity fields', () => {
  const [event] = parseAnswerEventBatch(envelope());
  assert.deepEqual(event, {
    eventId: 'weibian_answer_00000001',
    contentVersion: 'fc68413c7b70da0e',
    taskId: 'cm-1-1a',
    chapterId: 1,
    chosenOptionId: 'a',
  });

  for (const forbidden of [
    'correct',
    'points',
    'score',
    'answeredAt',
    'userKey',
    'dayKey',
  ]) {
    assert.throws(
      () => parseAnswerEventBatch(envelope({ [forbidden]: forbidden === 'correct' })),
      /client-result-fields-forbidden/,
    );
  }
  assert.throws(
    () => parseAnswerEventBatch(envelope({ unexpected: 'x' })),
    /unknown-answer-event-field/,
  );
});

test('server validates authored answers and derives result without trusting client score', () => {
  const index = buildAuthoredTaskIndex(authored);
  const [correct] = parseAnswerEventBatch(envelope());
  const verifiedCorrect = validateAuthoredAnswer(correct, index, 'fc68413c7b70da0e');
  assert.equal(verifiedCorrect.correct, true);
  assert.equal(verifiedCorrect.points, 1);

  const [wrong] = parseAnswerEventBatch(envelope({ chosenOptionId: 'b' }));
  const verifiedWrong = validateAuthoredAnswer(wrong, index, 'fc68413c7b70da0e');
  assert.equal(verifiedWrong.correct, false);
  assert.equal(verifiedWrong.points, 0);
});

test('unknown generated tasks, chapter mismatches, options and content versions fail closed', () => {
  const index = buildAuthoredTaskIndex(authored);
  const [generated] = parseAnswerEventBatch(envelope({ taskId: 'cloze-1-0' }));
  assert.throws(
    () => validateAuthoredAnswer(generated, index, 'fc68413c7b70da0e'),
    /task-not-ranking-eligible/,
  );

  const [wrongChapter] = parseAnswerEventBatch(envelope({ chapterId: 2 }));
  assert.throws(
    () => validateAuthoredAnswer(wrongChapter, index, 'fc68413c7b70da0e'),
    /task-chapter-mismatch/,
  );

  const [unknownOption] = parseAnswerEventBatch(envelope({ chosenOptionId: 'z' }));
  assert.throws(
    () => validateAuthoredAnswer(unknownOption, index, 'fc68413c7b70da0e'),
    /unknown-task-option/,
  );

  const [oldContent] = parseAnswerEventBatch(
    envelope({ contentVersion: '0000000000000000' }),
  );
  assert.throws(
    () => validateAuthoredAnswer(oldContent, index, 'fc68413c7b70da0e'),
    /content-version-not-accepted/,
  );
});

test('event envelope and batch size are bounded', () => {
  assert.throws(
    () => parseAnswerEventBatch({ schema: ANSWER_EVENT_SCHEMA, events: [] }),
    /invalid-answer-event-count/,
  );
  assert.throws(
    () => parseAnswerEventBatch({
      schema: ANSWER_EVENT_SCHEMA,
      events: Array.from({ length: 26 }, (_, index) => ({
        ...envelope().events[0],
        eventId: `weibian_answer_${String(index).padStart(8, '0')}`,
      })),
    }),
    /invalid-answer-event-count/,
  );
});

test('verified-answer rank thresholds are bounded by the authored bank', () => {
  assert.equal(rankForPoints(2).name, '童蒙');
  assert.equal(rankForPoints(3).name, '志学');
  assert.equal(rankForPoints(200).name, '从心');
  assert.equal(rankForPoints(99_999).name, '从心');
});

test('daily board uses the Beijing date boundary', () => {
  assert.equal(beijingDayKey(new Date('2026-07-28T16:30:00Z')), '2026-07-29');
});

test('ranking identity hashing fails closed without a strong server secret', () => {
  assert.throws(() => requireRankingPepper('short'), /ranking-pepper-missing/);
  assert.equal(requireRankingPepper('x'.repeat(32)).length, 32);
});
