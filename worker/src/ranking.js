export const RANKING_SCHEMA = 'weibian-rankings-v2';
export const ANSWER_EVENT_SCHEMA = 'weibian-answer-events-v1';
export const MAX_AUTHORED_TASKS = 215;
export const MAX_EVENTS_PER_REQUEST = 25;
export const MAX_EVENT_BODY_BYTES = 32 * 1024;

const EVENT_ID_RE = /^[a-z0-9][a-z0-9_-]{7,99}$/;
const TASK_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$/;
const OPTION_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,39}$/;
const FORBIDDEN_CLIENT_FIELDS = Object.freeze([
  'answeredAt',
  'beijingDay',
  'correct',
  'dayKey',
  'points',
  'receivedAt',
  'score',
  'userKey',
]);

const RANKS = Object.freeze([
  Object.freeze({ name: '童蒙', minimumPoints: 0 }),
  Object.freeze({ name: '志学', minimumPoints: 3 }),
  Object.freeze({ name: '束脩', minimumPoints: 9 }),
  Object.freeze({ name: '升堂', minimumPoints: 20 }),
  Object.freeze({ name: '入室', minimumPoints: 40 }),
  Object.freeze({ name: '博文', minimumPoints: 70 }),
  Object.freeze({ name: '约礼', minimumPoints: 110 }),
  Object.freeze({ name: '不惑', minimumPoints: 160 }),
  Object.freeze({ name: '从心', minimumPoints: 200 }),
]);

function object(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
}

function exactString(value, pattern, label) {
  if (typeof value !== 'string' || !pattern.test(value)) {
    throw new Error(`invalid-${label}`);
  }
  return value;
}

function exactInteger(value, minimum, maximum, label) {
  if (!Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new Error(`invalid-${label}`);
  }
  return value;
}

function normalizedTask(rawTask) {
  const task = object(rawTask);
  if (!task) return null;
  const id = typeof task.id === 'string' ? task.id.trim() : '';
  const answerId = typeof task.answerId === 'string' ? task.answerId.trim() : '';
  const refs = Array.isArray(task.refs) ? task.refs : [];
  const chapterId = refs[0];
  const options = Array.isArray(task.options)
    ? task.options
        .map((option) => object(option))
        .filter(Boolean)
        .map((option) => String(option.id || '').trim())
    : [];
  if (
    !TASK_ID_RE.test(id) ||
    !OPTION_ID_RE.test(answerId) ||
    !Number.isSafeInteger(chapterId) ||
    chapterId < 1 ||
    chapterId > 541 ||
    options.length < 2 ||
    options.length > 8 ||
    options.some((optionId) => !OPTION_ID_RE.test(optionId)) ||
    new Set(options).size !== options.length ||
    !options.includes(answerId)
  ) {
    return null;
  }
  return Object.freeze({
    id,
    chapterId,
    answerId,
    optionIds: Object.freeze(options),
    semanticMaterial: JSON.stringify([id, chapterId, answerId, options]),
  });
}

export function buildAuthoredTaskIndex(content) {
  const root = object(content);
  const bank = Array.isArray(root?.bank) ? root.bank : [];
  if (bank.length < 1 || bank.length > MAX_AUTHORED_TASKS) {
    throw new Error('invalid-authored-task-bank');
  }
  const index = new Map();
  for (const rawTask of bank) {
    const task = normalizedTask(rawTask);
    if (!task || index.has(task.id)) throw new Error('invalid-authored-task-bank');
    index.set(task.id, task);
  }
  return index;
}

export function requireCompleteAuthoredTaskIndex(content) {
  const index = buildAuthoredTaskIndex(content);
  if (index.size !== MAX_AUTHORED_TASKS) {
    throw new Error('invalid-authored-task-bank-size');
  }
  return index;
}

export function parseAnswerEventBatch(payload) {
  const root = object(payload);
  if (!root || root.schema !== ANSWER_EVENT_SCHEMA || !Array.isArray(root.events)) {
    throw new Error('invalid-answer-event-envelope');
  }
  if (root.events.length < 1 || root.events.length > MAX_EVENTS_PER_REQUEST) {
    throw new Error('invalid-answer-event-count');
  }
  return root.events.map((rawEvent) => {
    const event = object(rawEvent);
    if (!event) throw new Error('invalid-answer-event');
    if (FORBIDDEN_CLIENT_FIELDS.some((field) => Object.hasOwn(event, field))) {
      throw new Error('client-result-fields-forbidden');
    }
    const allowed = new Set([
      'eventId',
      'contentVersion',
      'taskId',
      'chapterId',
      'chosenOptionId',
    ]);
    if (Object.keys(event).some((field) => !allowed.has(field))) {
      throw new Error('unknown-answer-event-field');
    }
    return Object.freeze({
      eventId: exactString(event.eventId, EVENT_ID_RE, 'event-id'),
      contentVersion: exactString(
        event.contentVersion,
        /^[a-f0-9]{16}$/,
        'content-version',
      ),
      taskId: exactString(event.taskId, TASK_ID_RE, 'task-id'),
      chapterId: exactInteger(event.chapterId, 1, 541, 'chapter-id'),
      chosenOptionId: exactString(
        event.chosenOptionId,
        OPTION_ID_RE,
        'chosen-option',
      ),
    });
  });
}

export function validateAuthoredAnswer(event, taskIndex, expectedContentVersion) {
  if (event.contentVersion !== expectedContentVersion) {
    throw new Error('content-version-not-accepted');
  }
  const task = taskIndex.get(event.taskId);
  if (!task) throw new Error('task-not-ranking-eligible');
  if (task.chapterId !== event.chapterId) throw new Error('task-chapter-mismatch');
  if (!task.optionIds.includes(event.chosenOptionId)) {
    throw new Error('unknown-task-option');
  }
  return Object.freeze({
    ...event,
    correct: event.chosenOptionId === task.answerId,
    points: event.chosenOptionId === task.answerId ? 1 : 0,
    taskSemanticMaterial: task.semanticMaterial,
  });
}

export function rankForPoints(points) {
  const bounded = Number.isFinite(Number(points))
    ? Math.min(MAX_AUTHORED_TASKS, Math.max(0, Math.trunc(Number(points))))
    : 0;
  return [...RANKS].reverse().find((rank) => bounded >= rank.minimumPoints) || RANKS[0];
}

export function beijingDayKey(now = new Date()) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

export function requireRankingPepper(value) {
  if (typeof value !== 'string' || value.length < 32) {
    throw new Error('ranking-pepper-missing');
  }
  return value;
}
