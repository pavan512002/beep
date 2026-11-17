// Checks a Claude routine tag file before it is pushed. The Java pipeline checks again on import;
// this gives the agent fast, specific feedback so it can fix mistakes itself.
// Usage: node tagging/check-tags.mjs state/claude-tags/<file>.json
import { readFileSync } from 'node:fs';

const file = process.argv[2];
if (!file) { console.error('Usage: node tagging/check-tags.mjs <file>'); process.exit(2); }

const P = 'pipeline/src/main/resources/prompts/';
const schema = JSON.parse(readFileSync(P + 'tag-schema.json', 'utf8'));
const skills = new Set(JSON.parse(readFileSync(P + 'skills.json', 'utf8')));
const pending = JSON.parse(readFileSync('state/pending-tags.json', 'utf8'));
const keys = new Set(pending.posts.map((p) => p.cacheKey));

let data;
try { data = JSON.parse(readFileSync(file, 'utf8')); }
catch (e) { console.error(`Not valid JSON: ${e.message}`); process.exit(1); }

const errors = [];
if (!data.model) errors.push('missing "model"');
if (data.promptVersion !== pending.promptVersion) errors.push(`promptVersion must be "${pending.promptVersion}"`);
if (!Array.isArray(data.results)) errors.push('"results" must be an array');

const fields = schema.required;
const seen = new Set();
(data.results || []).forEach((r, n) => {
  const at = `result ${n + 1}${r.cacheKey ? ` (${r.cacheKey.slice(0, 8)})` : ''}`;
  if (!keys.has(r.cacheKey)) errors.push(`${at}: cacheKey not in pending-tags.json`);
  if (seen.has(r.cacheKey)) errors.push(`${at}: duplicate cacheKey`);
  seen.add(r.cacheKey);
  for (const f of fields) if (!(f in r)) errors.push(`${at}: missing "${f}"`);
  for (const [f, def] of Object.entries(schema.properties)) {
    if (def.enum && !def.enum.includes(r[f])) errors.push(`${at}: ${f} = ${JSON.stringify(r[f])}, allowed: ${def.enum.join(' | ')}`);
    if (def.anyOf && r[f] !== null && !Number.isInteger(r[f])) errors.push(`${at}: ${f} must be an integer or null`);
  }
  if (!Array.isArray(r.cities) || !r.cities.length) errors.push(`${at}: cities must be a non-empty array`);
  for (const f of ['skills', 'impliedSkills']) {
    if (!Array.isArray(r[f])) { errors.push(`${at}: ${f} must be an array`); continue; }
    for (const s of r[f]) if (!skills.has(s)) errors.push(`${at}: ${f} has "${s}", not in skills.json`);
  }
  if (r.skills?.length > 6) errors.push(`${at}: at most 6 skills`);
  if (r.impliedSkills?.length > 4) errors.push(`${at}: at most 4 impliedSkills`);
});
const missing = [...keys].filter((k) => !seen.has(k)).length;
if (missing) errors.push(`${missing} pending posts have no result`);

if (errors.length) {
  console.error(`${errors.length} problems:\n- ` + errors.slice(0, 60).join('\n- '));
  process.exit(1);
}
console.log(`OK: ${data.results.length} posts, all fields valid`);
