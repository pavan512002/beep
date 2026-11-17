/* "How it works" page: renders data/stats.json and a few counts from data/jobs.json. */
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const n = (v) => (v == null ? '–' : Number(v).toLocaleString('en-IN'));
  const usd = (v, d = 2) => (v == null ? '–' : `$${Number(v).toFixed(d)}`);
  const pct = (v) => (v == null ? '–' : `${Math.round(v * 100)}%`);
  const when = (iso) => (iso ? new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }) : '–');

  function stat(label, value, sub, i) {
    return `<div class="stat" style="--i:${i}"><div class="label">${esc(label)}</div><div class="value">${value}</div><div class="sub">${sub}</div></div>`;
  }

  function bars(el, rows, alt = false) {
    const max = Math.max(1, ...rows.map((r) => r[1]));
    el.innerHTML = rows.map(([label, v]) => `
      <div class="bar-row"><span>${esc(label)}</span>
        <div class="bar-track" role="img" aria-label="${esc(label)}: ${v}"><div class="bar-fill${alt ? ' alt' : ''}" data-w="${(v / max) * 100}"></div></div>
        <span class="num">${n(v)}</span></div>`).join('');
    // Grow the bars after first paint so the width change animates.
    requestAnimationFrame(() => requestAnimationFrame(() =>
      el.querySelectorAll('.bar-fill').forEach((b) => (b.style.width = `${b.dataset.w}%`))));
  }

  function countBy(jobs, keys) {
    const m = new Map();
    jobs.forEach((j) => keys(j).forEach((k) => k && m.set(k, (m.get(k) || 0) + 1)));
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }

  async function main() {
    const [stats, jobs] = await Promise.all([
      fetch('data/stats.json', { cache: 'no-cache' }).then((r) => r.json()),
      fetch('data/jobs.json', { cache: 'no-cache' }).then((r) => r.json()),
    ]);
    const r = stats.lastRun || {};
    const llm = r.llm || {};
    const d = r.dedup || {};
    const ev = stats.eval;
    const fresher = jobs.filter((j) => j.tags?.fresherFit === 'yes').length;

    $('kpis').innerHTML = [
      stat('Live posts', n(r.totalLive), `${n(fresher)} say freshers welcome`, 0),
      stat('New today', n(r.newToday), `Updated ${esc(when(r.finishedAt))}`, 1),
      stat('Duplicates merged', n((d.raw || 0) - (d.afterFuzzy || 0)), `${n(d.raw)} fetched → ${n(d.afterFuzzy)} unique`, 2),
      stat('AI cost / 1,000 posts', usd(llm.costPer1000PostsUsd ?? 0), `Free tier · ${usd(llm.paidEquivalentPer1000PostsUsd)} on a paid plan`, 3),
      stat('Tagging accuracy', ev?.roleAccuracy != null ? pct(ev.roleAccuracy) : 'Pending', ev ? `Role, ${ev.sampleSize} posts hand-checked` : '50-post hand check in progress', 4),
    ].join('');

    const sourcesOk = (r.sources || []).filter((s) => s.status === 'ok');
    const steps = [
      ['1 · Fetch', `${n(d.raw)} posts from ${sourcesOk.length} sources`],
      ['2 · Clean', 'Strip HTML, canonical links and city names'],
      ['3 · Merge duplicates', `${n(d.raw)} → ${n(d.afterFuzzy)} unique`],
      ['4 · AI tags', `${n(llm.newlyTagged)} new posts tagged in ${n(llm.calls)} requests`],
      ['5 · Publish', `${n(r.totalLive)} live posts, ${n(r.untagged)} untagged`],
    ];
    $('flow').innerHTML = steps.map(([t, s]) => `<div class="flow-step"><b>${esc(t)}</b><span>${s}</span></div>`).join('');

    bars($('funnel'), [
      ['Fetched', d.raw || 0],
      ['After same-link merge', d.afterExactUrl || 0],
      ['After similar-title merge', d.afterFuzzy || 0],
      ['Live (incl. earlier days)', r.totalLive || 0],
    ]);

    $('sources').innerHTML = `<thead><tr><th>Source</th><th>Status</th><th class="num">Posts</th></tr></thead><tbody>${
      (r.sources || []).map((s) => {
        const cls = s.status === 'ok' ? 'status-ok' : s.status === 'error' ? 'status-err' : 'status-off';
        const label = s.status === 'disabled' ? 'Off (no key yet)' : s.status === 'ok' ? 'OK' : 'Error';
        return `<tr><td>${esc(s.name)}</td><td class="${cls}">${label}</td><td class="num">${n(s.fetched)}</td></tr>`;
      }).join('')}</tbody>`;

    const models = Object.entries(llm.byModel || {});
    $('llm-intro').innerHTML = `Each new post is tagged once with role, cities, job type, work mode, fresher fit, pay and skills, using prompt <b>${esc(llm.promptVersion)}</b>. Eight posts go in one request so the long instructions are sent once, which keeps the run inside free-tier limits. If one provider runs out of daily quota, the next one takes over. This run: <b>${n(llm.inputTokens)}</b> input and <b>${n(llm.outputTokens)}</b> output tokens, actual cost <b>${usd(llm.costUsd, 4)}</b>.`;
    $('models').innerHTML = `<thead><tr><th>Model</th><th class="num">Requests</th><th class="num">Tokens in / out</th><th class="num">Cost</th><th class="num">On a paid plan</th></tr></thead><tbody>${
      models.length ? models.map(([m, u]) => `<tr><td>${esc(m)}</td><td class="num">${n(u.calls)}</td><td class="num">${n(u.inputTokens)} / ${n(u.outputTokens)}</td><td class="num">${usd(u.costUsd, 4)}</td><td class="num">${u.paidEquivalentUsd ? usd(u.paidEquivalentUsd, 4) : '–'}</td></tr>`).join('')
        : '<tr><td colspan="5">No new posts needed tagging in this run.</td></tr>'}</tbody>`;

    $('accuracy').innerHTML = ev
      ? `<div class="bars" id="acc"></div><p class="hint" style="margin-top:12px">${esc(ev.notes || '')}</p>`
      : '<p class="hint" style="margin:0">The hand check of 50 random posts is in progress. Accuracy for role, city and fresher fit will appear here.</p>';
    if (ev) {
      bars($('acc'), [['Role', Math.round((ev.roleAccuracy || 0) * 100)], ['City', Math.round((ev.cityAccuracy || 0) * 100)], ['Fresher fit', Math.round((ev.fresherAccuracy || 0) * 100)]], true);
    }

    bars($('roles'), countBy(jobs, (j) => [j.tags?.role]));
    bars($('cities'), countBy(jobs, (j) => j.tags?.cities || []).filter(([c]) => c !== 'India (unspecified)').slice(0, 12), true);

    $('history').innerHTML = `<thead><tr><th>Run</th><th class="num">Live posts</th><th class="num">New</th><th class="num">AI cost</th></tr></thead><tbody>${
      (stats.history || []).map((h) => `<tr><td>${esc(when(h.at))}</td><td class="num">${n(h.totalLive)}</td><td class="num">${n(h.newToday)}</td><td class="num">${usd(h.costUsd, 4)}</td></tr>`).join('')}</tbody>`;
  }

  main().catch((e) => {
    console.error(e);
    $('kpis').innerHTML = '<p>Could not load the run data. Please refresh.</p>';
  });
})();
