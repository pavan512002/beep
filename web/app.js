/* Fresher Jobs India – browse, search, filter. No build step.
 * Data: data/jobs.json and data/stats.json, written daily by the pipeline.
 * Best fit (match.js) plugs in through App.fit = { panel(), rank(jobs) }. */
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const PAGE = 30;
  const DAY = 86400000;

  const state = { jobs: [], byId: new Map(), stats: null, mini: null, shown: PAGE, view: 'browse' };
  const App = (window.App = { state, esc, apply, renderCard: card, fit: null });

  const controls = ['q', 'role', 'city', 'type', 'mode', 'days', 'fresher', 'stipend'];

  // ---------- helpers ----------
  function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  const safeUrl = (u) => (/^https?:\/\//i.test(u || '') ? u : '#');
  const time = (s) => (s ? Date.parse(s) : 0);

  function ago(iso) {
    if (!iso) return '';
    const d = Math.floor((Date.now() - time(iso)) / DAY);
    if (d <= 0) return 'today';
    if (d === 1) return 'yesterday';
    return `${d} days ago`;
  }

  function money(n) {
    if (n >= 100000) return `${(n / 100000).toFixed(n % 100000 ? 1 : 0)}L`;
    if (n >= 1000) return `${Math.round(n / 1000)}k`;
    return String(n);
  }

  function pay(t) {
    if (!t || t.stipendMinInr == null) return null;
    if (t.stipendMinInr === 0 && !t.stipendMaxInr) return 'Unpaid';
    const lo = money(t.stipendMinInr);
    const hi = t.stipendMaxInr && t.stipendMaxInr !== t.stipendMinInr ? `–${money(t.stipendMaxInr)}` : '';
    return `₹${lo}${hi}/month`;
  }

  const LABEL = { internship: 'Internship', full_time: 'Full-time', remote: 'Remote', hybrid: 'Hybrid', onsite: 'Onsite' };

  // ---------- load ----------
  async function load() {
    const [jobs, stats] = await Promise.all([
      fetch('data/jobs.json', { cache: 'no-cache' }).then((r) => r.json()),
      fetch('data/stats.json', { cache: 'no-cache' }).then((r) => r.json()).catch(() => null),
    ]);
    state.jobs = jobs;
    state.stats = stats;
    jobs.forEach((j) => state.byId.set(j.id, j));

    state.mini = new MiniSearch({
      idField: 'id',
      fields: ['title', 'company', 'role', 'cities', 'skills', 'description'],
      extractField: (doc, f) => {
        const t = doc.tags || {};
        if (f === 'role') return t.role || '';
        if (f === 'cities') return (t.cities || []).join(' ');
        if (f === 'skills') return (t.skills || []).join(' ');
        return doc[f] || '';
      },
      searchOptions: { boost: { title: 3, skills: 2, role: 2, company: 1.5 }, prefix: true, fuzzy: 0.2 },
    });
    state.mini.addAll(jobs);

    fillSelect('role', count((j) => [j.tags?.role]));
    const cities = count((j) => j.tags?.cities || []);
    cities.sort((a, b) => (a[0] === 'India (unspecified)') - (b[0] === 'India (unspecified)'));
    fillSelect('city', cities);

    statusLine();
    readUrl();
    setView(new URLSearchParams(location.search).get('view') === 'fit' ? 'fit' : 'browse');
  }

  function count(keysOf) {
    const m = new Map();
    state.jobs.forEach((j) => keysOf(j).forEach((k) => k && m.set(k, (m.get(k) || 0) + 1)));
    return [...m.entries()].sort((a, b) => b[1] - a[1]);
  }

  function fillSelect(id, entries) {
    const sel = $(id);
    entries.forEach(([v, n]) => sel.add(new Option(`${v} (${n})`, v)));
  }

  function statusLine() {
    const r = state.stats?.lastRun;
    if (!r) { $('status').textContent = `${state.jobs.length} posts`; return; }
    const when = new Date(r.finishedAt).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
    $('status').innerHTML = `Updated ${esc(when)} · <b>${state.jobs.length}</b> posts · <b>${newCount()}</b> new today · <a href="stats.html">how this works</a>`;
  }

  /** New = first seen by the latest daily run (firstSeenAt is set to that run's start time). */
  function isNew(j) {
    const start = time(state.stats?.lastRun?.startedAt);
    return start ? time(j.firstSeenAt) >= start : false;
  }
  const newCount = () => state.jobs.filter(isNew).length;

  // ---------- URL <-> controls ----------
  function readUrl() {
    const p = new URLSearchParams(location.search);
    $('q').value = p.get('q') || '';
    ['role', 'city', 'type', 'mode', 'days'].forEach((k) => ($(k).value = p.get(k) || ''));
    $('fresher').checked = p.get('fresher') !== '0';
    $('stipend').checked = p.get('pay') === '1';
  }

  function writeUrl() {
    const p = new URLSearchParams();
    if ($('q').value.trim()) p.set('q', $('q').value.trim());
    ['role', 'city', 'type', 'mode', 'days'].forEach((k) => $(k).value && p.set(k, $(k).value));
    if (!$('fresher').checked) p.set('fresher', '0');
    if ($('stipend').checked) p.set('pay', '1');
    if (state.view === 'fit') p.set('view', 'fit');
    const qs = p.toString();
    history.replaceState(null, '', qs ? `?${qs}` : location.pathname);
  }

  // ---------- filter + rank ----------
  function filtered() {
    const q = $('q').value.trim();
    let list = state.jobs;
    let scores = null;
    if (q) {
      let hits = state.mini.search(q, { combineWith: 'AND' });
      if (!hits.length) hits = state.mini.search(q, { combineWith: 'OR' });
      scores = new Map(hits.map((h) => [h.id, h.score]));
      list = hits.map((h) => state.byId.get(h.id));
    }
    const role = $('role').value, city = $('city').value, type = $('type').value, mode = $('mode').value;
    const days = Number($('days').value) || 0;
    const fresher = $('fresher').checked, paid = $('stipend').checked;
    const since = days ? Date.now() - days * DAY : 0;

    list = list.filter((j) => {
      const t = j.tags || {};
      if (role && t.role !== role) return false;
      if (city && !(t.cities || []).includes(city)) return false;
      if (type && t.jobType !== type) return false;
      if (mode && t.workMode !== mode) return false;
      if (since && time(j.postedAt) < since) return false;
      if (fresher && t.fresherFit === 'no') return false;
      if (paid && t.stipendMinInr == null) return false;
      return true;
    });
    if (!scores) list = [...list].sort((a, b) => time(b.postedAt) - time(a.postedAt));
    return list;
  }

  function apply(resetPage = true) {
    if (resetPage) state.shown = PAGE;
    writeUrl();
    let rows = filtered().map((j) => ({ job: j }));
    if (state.view === 'fit' && App.fit) rows = App.fit.rank(rows.map((r) => r.job));

    const list = $('results');
    if (state.view === 'fit' && App.fit && !App.fit.ready()) {
      list.innerHTML = '';
      $('count').textContent = '';
      $('more').hidden = true;
      return;
    }
    $('count').textContent = `${rows.length} ${rows.length === 1 ? 'post' : 'posts'}`;
    list.innerHTML = rows.length
      ? rows.slice(0, state.shown).map((r) => card(r.job, r)).join('')
      : `<li class="empty">No posts match. Try removing a filter${$('fresher').checked ? ' or turning off “Fresher-friendly only”' : ''}.</li>`;
    $('more').hidden = rows.length <= state.shown;
  }

  // ---------- card ----------
  function card(j, match = {}) {
    const t = j.tags || {};
    const badges = [];
    if (isNew(j)) badges.push('<span class="badge new">New</span>');
    if (t.role) badges.push(`<span class="badge role">${esc(t.role)}</span>`);
    (t.cities || []).forEach((c) => badges.push(`<span class="badge">📍 ${esc(c)}</span>`));
    if (LABEL[t.jobType]) badges.push(`<span class="badge">${LABEL[t.jobType]}</span>`);
    if (LABEL[t.workMode]) badges.push(`<span class="badge">${LABEL[t.workMode]}</span>`);
    const p = pay(t);
    if (p) badges.push(`<span class="badge pay">${esc(p)}</span>`);
    if (t.fresherFit === 'no') {
      badges.push(`<span class="badge warn">${t.expMinYears ? `Needs ${t.expMinYears}+ yrs` : 'Not for freshers'}</span>`);
    } else if (t.fresherFit === 'yes') {
      badges.push('<span class="badge">Freshers OK</span>');
    }

    const have = new Set((match.matched || []).map((s) => s.toLowerCase()));
    const skills = (t.skills || []).map((s) => `<span class="skill${have.has(s.toLowerCase()) ? ' have' : ''}">${esc(s)}</span>`).join('');
    const sources = (j.sources || []).length > 1 ? ` · seen on ${j.sources.length} sites` : '';

    return `<li class="card">
      <div class="card-head">
        <div>
          <h3>${match.score != null ? `<span class="score">${match.score}%</span> · ` : ''}${esc(j.title)}</h3>
          <div class="company">${esc(j.company)}</div>
        </div>
        <a class="apply" href="${esc(safeUrl(j.url))}" target="_blank" rel="noopener nofollow">Apply ↗</a>
      </div>
      <div class="meta">${badges.join('')}</div>
      ${match.why ? `<div class="why">${match.why}</div>` : ''}
      ${skills ? `<div class="skills">${skills}</div>` : ''}
      ${j.description ? `<p class="desc">${esc(j.description)}</p>` : ''}
      <div class="foot-line">Posted ${esc(ago(j.postedAt))}${sources}</div>
    </li>`;
  }

  // ---------- views ----------
  function setView(v) {
    state.view = v;
    document.querySelectorAll('.tab[data-view]').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.view === v)));
    const panel = $('profile');
    panel.hidden = v !== 'fit';
    if (v === 'fit') {
      if (App.fit) App.fit.panel(panel);
      else panel.innerHTML = '<h2>Best fit for me</h2><p class="hint">Coming soon.</p>';
    }
    apply();
  }

  // ---------- events ----------
  let timer;
  $('q').addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(apply, 150); });
  controls.slice(1).forEach((id) => $(id).addEventListener('change', () => apply()));
  $('more').addEventListener('click', () => { state.shown += PAGE; apply(false); });
  $('reset').addEventListener('click', () => {
    $('q').value = '';
    ['role', 'city', 'type', 'mode', 'days'].forEach((k) => ($(k).value = ''));
    $('fresher').checked = true;
    $('stipend').checked = false;
    apply();
  });
  $('share').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(location.href);
      $('share').textContent = 'Copied!';
    } catch {
      $('share').textContent = 'Copy the address bar';
    }
    setTimeout(() => ($('share').textContent = 'Copy link'), 1500);
  });
  document.querySelectorAll('.tab[data-view]').forEach((b) => b.addEventListener('click', () => setView(b.dataset.view)));

  load().catch((e) => {
    $('status').textContent = 'Could not load jobs. Please refresh.';
    console.error(e);
  });
})();
