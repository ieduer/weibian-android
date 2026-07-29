export const MAX_POINTS = 51_200;
export const MAX_CHAPTERS = 512;

const RANKS = Object.freeze([
  Object.freeze({ name: '童蒙', minimumPoints: 0 }),
  Object.freeze({ name: '志学', minimumPoints: 300 }),
  Object.freeze({ name: '束脩', minimumPoints: 900 }),
  Object.freeze({ name: '升堂', minimumPoints: 2_000 }),
  Object.freeze({ name: '入室', minimumPoints: 4_000 }),
  Object.freeze({ name: '博文', minimumPoints: 7_000 }),
  Object.freeze({ name: '约礼', minimumPoints: 11_000 }),
  Object.freeze({ name: '不惑', minimumPoints: 16_000 }),
  Object.freeze({ name: '从心', minimumPoints: 24_000 }),
]);

function record(value) {
  return value && typeof value === 'object' && !Array.isArray(value) ? value : {};
}

function boundedInteger(value, minimum, maximum) {
  const parsed = Number(value);
  return Number.isFinite(parsed)
    ? Math.min(maximum, Math.max(minimum, Math.trunc(parsed)))
    : minimum;
}

function safeTime(value) {
  const text = typeof value === 'string' ? value.trim().slice(0, 40) : '';
  return Number.isFinite(Date.parse(text)) ? text : '';
}

export function rankForPoints(points) {
  const bounded = boundedInteger(points, 0, MAX_POINTS);
  return [...RANKS].reverse().find((rank) => bounded >= rank.minimumPoints) || RANKS[0];
}

export function summarizeProgress(payload) {
  const root = record(payload);
  const rawItems = Array.isArray(root.items)
    ? root.items.slice(0, MAX_CHAPTERS * 2)
    : [];
  const bestByChapter = new Map();

  for (const rawItem of rawItems) {
    const item = record(rawItem);
    const itemKey = typeof item.itemKey === 'string'
      ? item.itemKey.trim().slice(0, 24)
      : '';
    const match = /^chapter-(\d{1,3})$/.exec(itemKey);
    if (!match) continue;
    const chapterId = Number(match[1]);
    if (chapterId < 1 || chapterId > 541) continue;

    const meta = record(item.meta);
    const state = typeof item.state === 'string' ? item.state.toLowerCase() : '';
    const progressPercent = boundedInteger(
      meta.progressPercent ?? item.progressPercent ?? item.score,
      0,
      100,
    );
    const completed = progressPercent >= 100 ||
      ['completed', 'complete', 'done', 'passed'].includes(state);
    const points = completed ? 100 : progressPercent;
    const updatedAt = safeTime(
      meta.clientUpdatedAt ?? item.lastActivityAt ?? item.updatedAt,
    );
    const existing = bestByChapter.get(itemKey);
    if (!existing || points > existing.points || updatedAt > existing.updatedAt) {
      bestByChapter.set(itemKey, { points, completed, updatedAt });
    }
  }

  const values = [...bestByChapter.values()]
    .sort((left, right) => right.points - left.points)
    .slice(0, MAX_CHAPTERS);
  return {
    totalPoints: Math.min(
      MAX_POINTS,
      values.reduce((sum, item) => sum + item.points, 0),
    ),
    completedChapters: values.filter((item) => item.completed).length,
    activeChapters: values.filter((item) => item.points > 0).length,
    sourceUpdatedAt: values.reduce(
      (latest, item) => item.updatedAt > latest ? item.updatedAt : latest,
      '',
    ),
  };
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
