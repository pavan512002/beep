// Scores the hand-checked sample and publishes the result.
// Usage (repo root): node eval/score.mjs [eval/sample-50.csv]
// Writes eval/report.md and adds an "eval" block to web/data/stats.json (shown on the site).
import { readFileSync, writeFileSync } from 'node:fs';

const FILE = process.argv[2] || 'eval/sample-50.csv';

// Minimal RFC-4180 CSV parser (quoted fields, doubled quotes, newlines inside quotes).
function parseCsv(text) {
  text = text.replace(/^﻿/, '');
  const rows = [];
  let row = [], field = '', q = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (q) {
      if (c === '"' && text[i + 1] === '"') { field += '"'; i++; }
      else if (c === '"') q = false;
      else field += c;
    } else if (c === '"') q = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && text[i + 1] === '\n') i++;
      row.push(field); field = '';
      if (row.some((v) => v !== '')) rows.push(row);
      row = [];
    } else field += c;
  }
  if (field || row.length) { row.push(field); rows.push(row); }
  const [head, ...body] = rows;
  return body.map((r) => Object.fromEntries(head.map((h, i) => [h.trim(), (r[i] ?? '').trim()])));
}

const norm = (s) => String(s || '').toLowerCase().replace(/\s+/g, ' ').trim();
const citySet = (s) => new Set(String(s || '').split(/[;,]/).map(norm).filter(Boolean));
const sameSet = (a, b) => a.size === b.size && [...a].every((x) => b.has(x));

const rows = parseCsv(readFileSync(FILE, 'utf8'));
const fields = {
  role: (r) => norm(r.ai_role) === norm(r.correct_role),
  city: (r) => sameSet(citySet(r.ai_cities), citySet(r.correct_cities)),
  jobType: (r) => norm(r.ai_job_type) === norm(r.correct_job_type),
  fresherFit: (r) => norm(r.ai_fresher_fit) === norm(r.correct_fresher_fit),
  skills: (r) => /^y/i.test(r.skills_ok || 'y'),
};

const result = {};
const errors = {};
for (const [name, ok] of Object.entries(fields)) {
  const wrong = rows.filter((r) => !ok(r));
  result[name] = (rows.length - wrong.length) / rows.length;
  errors[name] = wrong;
}
const allRight = rows.filter((r) => Object.values(fields).every((ok) => ok(r))).length;

const pct = (v) => `${(v * 100).toFixed(0)}%`;
const byModel = {};
for (const r of rows) {
  const m = (byModel[r.model] ||= { n: 0, role: 0, city: 0 });
  m.n++; if (fields.role(r)) m.role++; if (fields.city(r)) m.city++;
}

let md = `# Tagging accuracy: hand check of ${rows.length} posts\n\n`;
md += `Random sample (fixed seed) of tagged posts from the live site. For each post I read the original text and corrected any tag that was wrong.\n\n`;
md += `| Field | Accuracy | Wrong |\n|---|---|---|\n`;
for (const k of Object.keys(fields)) md += `| ${k} | **${pct(result[k])}** | ${errors[k].length} |\n`;
md += `| all fields right | **${pct(allRight / rows.length)}** | ${rows.length - allRight} |\n\n`;
md += `## By model\n\n| Model | Posts | Role | City |\n|---|---|---|---|\n`;
for (const [m, v] of Object.entries(byModel)) md += `| ${m} | ${v.n} | ${pct(v.role / v.n)} | ${pct(v.city / v.n)} |\n`;
md += `\n## Mistakes\n`;
for (const k of ['role', 'city', 'fresherFit', 'jobType']) {
  if (!errors[k].length) continue;
  md += `\n### ${k}\n\n| # | Post | AI said | Correct | Notes |\n|---|---|---|---|---|\n`;
  const col = { role: 'role', city: 'cities', fresherFit: 'fresher_fit', jobType: 'job_type' }[k];
  for (const r of errors[k]) md += `| ${r.n} | ${r.title.replace(/\|/g, '/')} | ${r['ai_' + col]} | ${r['correct_' + col]} | ${(r.notes || '').replace(/\|/g, '/')} |\n`;
}
writeFileSync('eval/report.md', md);

const statsFile = 'web/data/stats.json';
const stats = JSON.parse(readFileSync(statsFile, 'utf8'));
stats.eval = {
  sampleSize: rows.length,
  checkedAt: new Date().toISOString().slice(0, 10),
  roleAccuracy: +result.role.toFixed(3),
  cityAccuracy: +result.city.toFixed(3),
  fresherAccuracy: +result.fresherFit.toFixed(3),
  jobTypeAccuracy: +result.jobType.toFixed(3),
  skillsAccuracy: +result.skills.toFixed(3),
  allFieldsAccuracy: +(allRight / rows.length).toFixed(3),
  notes: `Hand check of ${rows.length} random posts. Full list of mistakes: eval/report.md in the repo.`,
};
writeFileSync(statsFile, JSON.stringify(stats, null, 2) + '\n');

console.log(md.split('## By model')[0]);
console.log('Wrote eval/report.md and updated web/data/stats.json');
