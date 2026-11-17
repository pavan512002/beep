// Picks 50 random tagged posts for a hand check and writes eval/sample-50.csv.
// Usage (repo root): node eval/sample.mjs [count] [seed]
//
// The "correct_*" columns start as a copy of the AI's answer: open the CSV (Excel / Google
// Sheets), read each post, and change only the values that are wrong. Then run score.mjs.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';

const COUNT = Number(process.argv[2] || 50);
const SEED = Number(process.argv[3] || 20261118);
const OUT = 'eval/sample-50.csv';

if (existsSync(OUT) && !process.argv.includes('--force')) {
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

const jobs = JSON.parse(readFileSync('web/data/jobs.json', 'utf8')).filter((j) => j.tags);
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
const cell = (v) => {
  const s = String(v ?? '');
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};
const rows = sample.map((j, i) => {
  const t = j.tags;
  const cities = (t.cities || []).join('; ');
  const skills = [...(t.skills || []), ...(t.impliedSkills || []).map((s) => `(${s})`)].join('; ');
  return [i + 1, j.id, j.title, j.company, j.location, j.description, j.url, j.tagModel, j.promptVersion,
    t.role, cities, t.jobType, t.fresherFit, skills,
    t.role, cities, t.jobType, t.fresherFit, 'Y', ''].map(cell).join(',');
});
writeFileSync(OUT, '﻿' + cols.join(',') + '\n' + rows.join('\n') + '\n', 'utf8');

const by = (f) => sample.reduce((m, j) => ((m[f(j)] = (m[f(j)] || 0) + 1), m), {});
console.log(`Wrote ${OUT}: ${sample.length} of ${jobs.length} tagged posts (seed ${SEED})`);
console.log('By model:', by((j) => j.tagModel));
console.log('By role:', by((j) => j.tags.role));
