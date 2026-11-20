// Scores the hand-checked sample and publishes the result.
// Usage (repo root): node eval/score.mjs [eval/sample-50.csv] --reviewer "<who checked it>" [--dry-run]
// Writes eval/report.md and adds an "eval" block to web/data/stats.json (shown on the site).
//
// Refuses to score unless every row carries an explicit human decision (see sample.mjs), so an
// untouched sample can't turn into a "100% accurate" report.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { parseCsv, flag, positional } from './csv.mjs';

const argv = process.argv.slice(2);
const FILE = positional(argv, ['reviewer'])[0] || 'eval/sample-50.csv';
const REVIEWER = flag(argv, 'reviewer');
const DRY = argv.includes('--dry-run');
const META_FILE = FILE.replace(/[^/\\]+$/, 'sample-meta.json');

function fail(lines) {
  console.error(['Not scored:', ...[].concat(lines)].join('\n  '));
  process.exit(1);
}

if (!REVIEWER) fail('pass --reviewer "<name or initials of the person who checked the posts>"');
const rows = parseCsv(readFileSync(FILE, 'utf8'));
if (!rows.length) fail(`${FILE} has no rows`);

// ---------- every row needs a decision for every field ----------
const FIELDS = [
  // key, AI column, human column
  ['role', 'ai_role', 'correct_role'],
  ['city', 'ai_cities', 'correct_cities'],
  ['jobType', 'ai_job_type', 'correct_job_type'],
  ['fresherFit', 'ai_fresher_fit', 'correct_fresher_fit'],
];
const problems = [];
const seen = new Map();
for (const r of rows) {
  const where = `row ${r.n || '?'} (${r.id || 'no id'})`;
  if (!r.id) problems.push(`${where}: missing id`);
  else if (seen.has(r.id)) problems.push(`${where}: duplicate of row ${seen.get(r.id)}`);
  else seen.set(r.id, r.n);
  const blank = FIELDS.filter(([, , c]) => !r[c]).map(([, , c]) => c);
  if (blank.length) problems.push(`${where}: blank ${blank.join(', ')} (type "ok" or the right value)`);
  if (!/^(y|yes|n|no)$/i.test(r.skills_ok || '')) problems.push(`${where}: skills_ok must be Y or N (got "${r.skills_ok || ''}")`);
}
if (problems.length) fail([`${problems.length} problem(s) in ${FILE}:`, ...problems.slice(0, 60),
  ...(problems.length > 60 ? [`…and ${problems.length - 60} more`] : [])]);

// ---------- score ----------
const norm = (s) => String(s || '').toLowerCase().replace(/\s+/g, ' ').trim();
const citySet = (s) => new Set(String(s || '').split(/[;,]/).map(norm).filter(Boolean));
const sameSet = (a, b) => a.size === b.size && [...a].every((x) => b.has(x));
const truth = (r, aiCol, col) => (norm(r[col]) === 'ok' ? r[aiCol] : r[col]);   // "ok" = the AI was right

const right = {
  role: (r) => norm(r.ai_role) === norm(truth(r, 'ai_role', 'correct_role')),
  city: (r) => sameSet(citySet(r.ai_cities), citySet(truth(r, 'ai_cities', 'correct_cities'))),
  jobType: (r) => norm(r.ai_job_type) === norm(truth(r, 'ai_job_type', 'correct_job_type')),
  fresherFit: (r) => norm(r.ai_fresher_fit) === norm(truth(r, 'ai_fresher_fit', 'correct_fresher_fit')),
  skills: (r) => /^y/i.test(r.skills_ok),
};
const total = rows.length;
const fields = {};
const errors = {};
for (const [name, ok] of Object.entries(right)) {
  errors[name] = rows.filter((r) => !ok(r));
  fields[name] = { correct: total - errors[name].length, total };
}
const allRight = rows.filter((r) => Object.values(right).every((ok) => ok(r))).length;
fields.allFields = { correct: allRight, total };

const meta = existsSync(META_FILE) ? JSON.parse(readFileSync(META_FILE, 'utf8')) : {};
const prompts = [...new Set(rows.map((r) => r.prompt).filter(Boolean))];
const today = new Date().toISOString().slice(0, 10);
const pct = (f) => `${Math.round((f.correct / f.total) * 100)}%`;
const frac = (f) => `${f.correct} of ${f.total}`;

let md = `# Tagging accuracy: hand check of ${total} posts\n\n`;
md += `| | |\n|---|---|\n`;
md += `| Population | ${meta.population ?? 'unknown'}${meta.populationSize ? ` (${meta.populationSize} tagged posts)` : ''} |\n`;
md += `| Snapshot | ${meta.snapshotAt ?? 'unknown'} |\n`;
md += `| Sample | ${total} posts, seed ${meta.seed ?? 'unknown'} |\n`;
md += `| Prompt version(s) | ${prompts.join(', ') || 'unknown'} |\n`;
md += `| Reviewed by | ${REVIEWER} |\n`;
md += `| Scored | ${today} |\n\n`;
md += `Each post's original text was read and every tag marked right ("ok") or corrected by hand.\n\n`;
md += `| Field | Right | Accuracy |\n|---|---|---|\n`;
for (const [k, f] of Object.entries(fields)) md += `| ${k === 'allFields' ? 'all fields right' : k} | ${frac(f)} | **${pct(f)}** |\n`;

const byModel = {};
for (const r of rows) {
  const m = (byModel[r.model || 'unknown'] ||= { n: 0, role: 0, city: 0, fresherFit: 0 });
  m.n++;
  for (const k of ['role', 'city', 'fresherFit']) if (right[k](r)) m[k]++;
}
md += `\n## By model\n\n| Model | Posts | Role | City | Fresher fit |\n|---|---|---|---|---|\n`;
for (const [m, v] of Object.entries(byModel)) md += `| ${m} | ${v.n} | ${v.role}/${v.n} | ${v.city}/${v.n} | ${v.fresherFit}/${v.n} |\n`;

md += `\n## Mistakes\n`;
const col = { role: ['ai_role', 'correct_role'], city: ['ai_cities', 'correct_cities'],
  fresherFit: ['ai_fresher_fit', 'correct_fresher_fit'], jobType: ['ai_job_type', 'correct_job_type'] };
const pipe = (s) => String(s || '').replace(/\|/g, '/');
for (const k of ['role', 'city', 'fresherFit', 'jobType', 'skills']) {
  if (!errors[k].length) continue;
  if (k === 'skills') {
    md += `\n### skills\n\n| # | Post | AI skills | Notes |\n|---|---|---|---|\n`;
    for (const r of errors.skills) md += `| ${r.n} | ${pipe(r.title)} | ${pipe(r.ai_skills)} | ${pipe(r.notes)} |\n`;
    continue;
  }
  const [ai, human] = col[k];
  md += `\n### ${k}\n\n| # | Post | AI said | Correct | Notes |\n|---|---|---|---|---|\n`;
  for (const r of errors[k]) md += `| ${r.n} | ${pipe(r.title)} | ${pipe(r[ai])} | ${pipe(r[human])} | ${pipe(r.notes)} |\n`;
}
if (Object.values(errors).every((e) => !e.length)) md += `\nNone in this sample.\n`;

console.log(md.split('## By model')[0]);
if (DRY) { console.log('--dry-run: nothing written'); process.exit(0); }
writeFileSync('eval/report.md', md);

const ratio = (f) => +(f.correct / f.total).toFixed(3);
const statsFile = 'web/data/stats.json';
const stats = JSON.parse(readFileSync(statsFile, 'utf8'));
stats.eval = {
  sampleSize: total,
  population: meta.population ?? null,
  snapshotAt: meta.snapshotAt ?? null,
  promptVersions: prompts,
  reviewedBy: REVIEWER,
  checkedAt: today,
  fields,
  roleAccuracy: ratio(fields.role),
  cityAccuracy: ratio(fields.city),
  fresherAccuracy: ratio(fields.fresherFit),
  jobTypeAccuracy: ratio(fields.jobType),
  skillsAccuracy: ratio(fields.skills),
  allFieldsAccuracy: ratio(fields.allFields),
  notes: `Hand check of ${total} random posts (${meta.population ?? 'unknown'} population). Every mistake is listed in eval/report.md in the repo.`,
};
writeFileSync(statsFile, JSON.stringify(stats, null, 2) + '\n');
console.log('Wrote eval/report.md and updated web/data/stats.json');
