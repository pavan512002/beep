// Picks random tagged posts for a hand check and writes eval/sample-50.csv + eval/sample-meta.json.
// Usage (repo root): node eval/sample.mjs [count] [seed] [--population all|claude|api] [--force]
//
//   --population all     every tagged post on the live site (default: what students actually see)
//                claude  only posts tagged by the Claude Code routine
//                api     only posts tagged by the free API models (Groq / Gemini)
//
// The "correct_*" and "skills_ok" columns start EMPTY on purpose: score.mjs refuses to run until
// every row has a decision, so an unread sample can never be published as a hand check.
// For each post read the original text, then in each correct_* column type either
//   ok               – the AI's value is right
//   <the right value> – e.g. "Data & Analytics", "Pune; Remote", "internship", "unclear"
// and in skills_ok type Y or N. Then run score.mjs.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { cell, flag, positional } from './csv.mjs';

const argv = process.argv.slice(2);
const [countArg, seedArg] = positional(argv, ['population']);
const COUNT = Number(countArg || 50);
const SEED = Number(seedArg || 20261118);
const POPULATION = flag(argv, 'population') || 'all';
const OUT = 'eval/sample-50.csv';
const META = 'eval/sample-meta.json';

if (!['all', 'claude', 'api'].includes(POPULATION)) {
  console.error(`--population must be all, claude or api (got "${POPULATION}")`);
  process.exit(1);
}
if (existsSync(OUT) && !argv.includes('--force')) {
  console.error(`${OUT} already exists (it may hold your labels). Pass --force to overwrite.`);
  process.exit(1);
}

// Small seeded PRNG (mulberry32) so the same seed gives the same sample.
function rng(seed) {
  return () => {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const isClaude = (j) => String(j.tagModel || '').startsWith('claude-code:');
const inPopulation = { all: () => true, claude: isClaude, api: (j) => !isClaude(j) }[POPULATION];
const jobs = JSON.parse(readFileSync('web/data/jobs.json', 'utf8')).filter((j) => j.tags && inPopulation(j));
const stats = existsSync('web/data/stats.json') ? JSON.parse(readFileSync('web/data/stats.json', 'utf8')) : {};
if (jobs.length < COUNT) {
  console.error(`Only ${jobs.length} tagged posts in population "${POPULATION}"; need ${COUNT}.`);
  process.exit(1);
}

const rand = rng(SEED);
const pool = [...jobs];
for (let i = pool.length - 1; i > 0; i--) {   // Fisher–Yates shuffle
  const k = Math.floor(rand() * (i + 1));
  [pool[i], pool[k]] = [pool[k], pool[i]];
}
const sample = pool.slice(0, COUNT);

const cols = ['n', 'id', 'title', 'company', 'location', 'description', 'url', 'model', 'prompt',
  'ai_role', 'ai_cities', 'ai_job_type', 'ai_fresher_fit', 'ai_skills',
  'correct_role', 'correct_cities', 'correct_job_type', 'correct_fresher_fit', 'skills_ok', 'notes'];
const rows = sample.map((j, i) => {
  const t = j.tags;
  const cities = (t.cities || []).join('; ');
  const skills = [...(t.skills || []), ...(t.impliedSkills || []).map((s) => `(${s})`)].join('; ');
  return [i + 1, j.id, j.title, j.company, j.location, j.description, j.url, j.tagModel, j.promptVersion,
    t.role, cities, t.jobType, t.fresherFit, skills,
    '', '', '', '', '', ''].map(cell).join(',');
});
writeFileSync(OUT, '﻿' + cols.join(',') + '\n' + rows.join('\n') + '\n', 'utf8');

const by = (f) => sample.reduce((m, j) => ((m[f(j)] = (m[f(j)] || 0) + 1), m), {});
const meta = {
  population: POPULATION,
  seed: SEED,
  size: sample.length,
  populationSize: jobs.length,
  snapshotAt: stats.lastRun?.startedAt ?? null,
  sampledAt: new Date().toISOString(),
  byModel: by((j) => j.tagModel),
  byPromptVersion: by((j) => j.promptVersion),
};
writeFileSync(META, JSON.stringify(meta, null, 2) + '\n');

console.log(`Wrote ${OUT}: ${sample.length} of ${jobs.length} tagged posts (population "${POPULATION}", seed ${SEED})`);
console.log('By model:', meta.byModel);
console.log('By role:', by((j) => j.tags.role));
console.log('Next: fill every correct_* column (ok or the right value) and skills_ok (Y/N), then run score.mjs.');
