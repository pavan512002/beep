// Checks the files a pipeline run publishes before they are committed and deployed.
// Usage (repo root): node tools/verify-output.mjs
// Exit code 1 (and nothing gets published by the workflow) if any check fails.
//
// Checks: jobs.json, stats.json and export/jobs.csv parse; ids are unique and the CSV lists the
// same ids in the same order; every post has a web link; stats agree with jobs.json; and no API
// key (or a request URL that carried one) leaked into the public files.
import { readFileSync, existsSync } from 'node:fs';
import { parseCsv } from '../eval/csv.mjs';

const problems = [];
const check = (ok, msg) => { if (!ok) problems.push(msg); };
const read = (f) => readFileSync(f, 'utf8');

function json(file) {
  try { return JSON.parse(read(file)); } catch (e) { problems.push(`${file}: not valid JSON (${e.message})`); return null; }
}

const jobs = json('web/data/jobs.json');
const stats = json('web/data/stats.json');

if (Array.isArray(jobs)) {
  const ids = new Set();
  jobs.forEach((j, i) => {
    check(j.id, `jobs.json[${i}]: no id`);
    check(!ids.has(j.id), `jobs.json[${i}]: duplicate id ${j.id}`);
    ids.add(j.id);
    check(j.title && String(j.title).trim(), `jobs.json[${i}] (${j.id}): no title`);
    check(/^https?:\/\//.test(j.url || ''), `jobs.json[${i}] (${j.id}): link is not http(s): ${j.url}`);
  });
  check(jobs.length > 0, 'jobs.json is empty');

  if (existsSync('export/jobs.csv')) {
    const rows = parseCsv(read('export/jobs.csv'));
    check(rows.length === jobs.length, `export/jobs.csv has ${rows.length} rows, jobs.json has ${jobs.length}`);
    const firstDiff = rows.findIndex((r, i) => r.id !== jobs[i]?.id);
    check(firstDiff < 0, `export/jobs.csv row ${firstDiff + 1} is ${rows[firstDiff]?.id}, jobs.json has ${jobs[firstDiff]?.id}`);
  } else problems.push('export/jobs.csv is missing');

  if (stats?.lastRun) {
    check(stats.lastRun.totalLive === jobs.length,
      `stats.json lastRun.totalLive is ${stats.lastRun.totalLive}, jobs.json has ${jobs.length}`);
    const fresher = jobs.filter((j) => j.tags?.fresherFit === 'yes').length;
    check(stats.lastRun.fresherFriendly == null || stats.lastRun.fresherFriendly === fresher,
      `stats.json fresherFriendly is ${stats.lastRun.fresherFriendly}, jobs.json has ${fresher}`);
  }
} else if (jobs) problems.push('jobs.json is not a list');

// ---------- secrets ----------
// Request URLs with keys in them (Adzuna: query string; Jooble: path) must never appear in
// run stats or the Claude work list. Descriptions are left alone: job text is free-form.
const leakPatterns = [/app_key=/i, /app_id=/i, /jooble\.org\/api\/(?!\*\*\*)[A-Za-z0-9-]{8,}/i];
for (const f of ['web/data/stats.json', 'state/pending-tags.json']) {
  if (!existsSync(f)) continue;
  const text = read(f);
  for (const p of leakPatterns) check(!p.test(text), `${f}: contains a request URL with credentials (${p})`);
}
// The real key values, when the workflow passes them in: none may appear in any public file.
const secrets = ['ADZUNA_APP_ID', 'ADZUNA_APP_KEY', 'JOOBLE_KEY', 'GROQ_API_KEY', 'GEMINI_API_KEY', 'ANTHROPIC_API_KEY']
  .map((k) => process.env[k]).filter((v) => v && v.length >= 6);
for (const f of ['web/data/jobs.json', 'web/data/stats.json', 'export/jobs.csv', 'state/pending-tags.json', 'state/tag-cache.json']) {
  if (!existsSync(f) || !secrets.length) continue;
  const text = read(f);
  secrets.forEach((s, i) => check(!text.includes(s), `${f}: contains secret #${i + 1}`));
}

if (problems.length) {
  console.error(`Output check failed (${problems.length}):\n  ` + problems.slice(0, 40).join('\n  '));
  process.exit(1);
}
console.log(`Output OK: ${jobs.length} posts, CSV matches, stats agree, no credentials found.`);
