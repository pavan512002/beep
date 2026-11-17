/* Fresher Jobs India – browse, search, filter. Plain JS, no build step.
 * Data: data/jobs.json + data/stats.json, written every morning by the pipeline.
 * Best fit (match.js) plugs in through App.fit = { panel(el), rank(jobs), ready() }. */
(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const PAGE = 25;
  const DAY = 86400000;

  const state = { jobs: [], byId: new Map(), stats: null, mini: null, shown: PAGE, view: 'browse', type: '' };
  const App = (window.App = { state, esc, icon, apply, fit: null });

  const SELECTS = ['role', 'city', 'mode', 'days'];
  const LABEL = { internship: 'Internship', full_time: 'Full-time', remote: 'Remote', hybrid: 'Hybrid', onsite: 'Onsite' };
  const DAYS_LABEL = { 1: 'Last 24 hours', 3: 'Last 3 days', 7: 'Last 7 days' };

  // ---------- helpers ----------
  function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  function icon(name, cls = 'icon') {
    return `<svg class="${cls}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
  }
  const safeUrl = (u) => (/^https?:\/\//i.test(u || '') ? u : '#');
  const time = (s) => (s ? Date.parse(s) : 0);

  function ago(iso) {
    if (!iso) return '';
    const d = Math.floor((Date.now() - time(iso)) / DAY);
    if (d <= 0) return 'Posted today';
    if (d === 1) return 'Posted yesterday';
    return `Posted ${d} days ago`;
  }
  function money(n) {
    if (n >= 100000) return `${(n / 100000).toFixed(n % 100000 ? 1 : 0)}L`;
    if (n >= 1000) return `${Math.round(n / 1000)}k`;
    return String(n);
  }
  function pay(t) {
    if (!t || t.stipendMinInr == null) return null;
    if (t.stipendMinInr === 0 && !t.stipendMaxInr) return 'Unpaid';
    const hi = t.stipendMaxInr && t.stipendMaxInr !== t.stipendMinInr ? `–${money(t.stipendMaxInr)}` : '';
    return `₹${money(t.stipendMinInr)}${hi} / month`;
  }
  function initials(name) {
    const w = String(name || '?').replace(/[^\p{L}\p{N} ]/gu, '').trim().split(/\s+/);
    return ((w[0]?.[0] || '?') + (w[1]?.[0] || '')).toUpperCase();
  }

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
        if (f === 'skills') return [...(t.skills || []), ...(t.impliedSkills || [])].join(' ');
        return doc[f] || '';
      },
      searchOptions: { boost: { title: 3, skills: 2, role: 2, company: 1.5 }, prefix: true, fuzzy: 0.2 },
    });
    state.mini.addAll(jobs);

    fillSelect('role', count((j) => [j.tags?.role]));
    const cities = count((j) => j.tags?.cities || []);
    cities.sort((a, b) => (a[0] === 'India (unspecified)') - (b[0] === 'India (unspecified)'));
    fillSelect('city', cities);

    kpis();
    readUrl();
    $('results').setAttribute('aria-busy', 'false');
    setView(new URLSearchParams(location.search).get('view') === 'fit' ? 'fit' : 'browse', false);
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

  function isNew(j) {
    const start = time(state.stats?.lastRun?.startedAt);
    return start ? time(j.firstSeenAt) >= start : false;
  }

  function kpis() {
    const r = state.stats?.lastRun;
    const cities = new Set(state.jobs.flatMap((j) => j.tags?.cities || []).filter((c) => c !== 'India (unspecified)' && c !== 'Remote'));
    const fresher = state.jobs.filter((j) => j.tags && j.tags.fresherFit !== 'no').length;
    const when = r ? new Date(r.finishedAt).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }) : '';
    const items = [
      `<li class="kpi"><span class="dot" aria-hidden="true"></span>Updated <b>${esc(when)}</b></li>`,
      `<li class="kpi"><b>${state.jobs.length}</b> live posts</li>`,
      `<li class="kpi"><b>${fresher}</b> fresher-friendly</li>`,
      `<li class="kpi"><b>${state.jobs.filter(isNew).length}</b> new today</li>`,
      `<li class="kpi"><b>${cities.size}</b> cities</li>`,
    ];
    $('kpis').innerHTML = items.join('');
  }

  // ---------- URL <-> controls ----------
  function readUrl() {
    const p = new URLSearchParams(location.search);
    $('q').value = p.get('q') || '';
    SELECTS.forEach((k) => ($(k).value = p.get(k) || ''));
    state.type = p.get('type') || '';
    $('fresher').checked = p.get('fresher') !== '0';
    $('stipend').checked = p.get('pay') === '1';
    syncSeg();
  }
  function writeUrl() {
    const p = new URLSearchParams();
    if (state.view === 'fit') p.set('view', 'fit');
    if ($('q').value.trim()) p.set('q', $('q').value.trim());
    if (state.type) p.set('type', state.type);
    SELECTS.forEach((k) => $(k).value && p.set(k, $(k).value));
    if (!$('fresher').checked) p.set('fresher', '0');
    if ($('stipend').checked) p.set('pay', '1');
    const qs = p.toString();
    history.replaceState(null, '', qs ? `?${qs}` : location.pathname);
  }
  function syncSeg() {
    document.querySelectorAll('#typeSeg button').forEach((b) => b.setAttribute('aria-checked', String(b.dataset.type === state.type)));
  }

  // ---------- filter ----------
  function filtered() {
    const q = $('q').value.trim();
    let list = state.jobs;
    let ranked = false;
    if (q) {
      let hits = state.mini.search(q, { combineWith: 'AND' });
      if (!hits.length) hits = state.mini.search(q, { combineWith: 'OR' });
      list = hits.map((h) => state.byId.get(h.id));
      ranked = true;
    }
    const role = $('role').value, city = $('city').value, mode = $('mode').value, type = state.type;
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
    if (!ranked) list = [...list].sort((a, b) => time(b.postedAt) - time(a.postedAt));
    return list;
  }

  /** Chips for every active filter, each removable in one tap. */
  function activeChips() {
    const chips = [];
    const add = (label, clear) => chips.push({ label, clear });
    if ($('role').value) add($('role').value, () => ($('role').value = ''));
    if ($('city').value) add($('city').value, () => ($('city').value = ''));
    if ($('mode').value) add(LABEL[$('mode').value], () => ($('mode').value = ''));
    if ($('days').value) add(DAYS_LABEL[$('days').value], () => ($('days').value = ''));
    if (!$('fresher').checked) add('Including experienced roles', () => ($('fresher').checked = true));
    if ($('stipend').checked) add('Pay mentioned', () => ($('stipend').checked = false));
    const box = $('activeChips');
    box.innerHTML = chips.map((c, i) => `<button class="chip-x" type="button" data-i="${i}" aria-label="Remove filter ${esc(c.label)}">${esc(c.label)}${icon('x')}</button>`).join('');
    box.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { chips[b.dataset.i].clear(); apply(); }));
    const n = chips.length;
    $('filterCount').hidden = n === 0;
    $('filterCount').textContent = n;
  }

  // ---------- render ----------
  function apply(resetPage = true) {
    const before = resetPage ? 0 : state.shown - PAGE;
    if (resetPage) state.shown = PAGE;
    writeUrl();
    activeChips();

    const fitMode = state.view === 'fit' && App.fit;
    if (fitMode && !App.fit.ready()) {
      $('count').innerHTML = '';
      $('results').innerHTML = `<li class="empty fit-empty">${icon('spark')}<h3>Add a few skills to see your matches</h3><p>Type what you know above, e.g. <b>DSA, Java, SQL</b>, or tap the suggestions.</p></li>`;
      $('more').hidden = true;
      return;
    }
    const jobs = filtered();
    const rows = fitMode ? App.fit.rank(jobs) : jobs.map((job) => ({ job }));
    const label = fitMode ? 'matching posts, best fit first' : rows.length === 1 ? 'post' : 'posts';
    $('count').innerHTML = `<b>${rows.length}</b> ${label}`;

    const list = $('results');
    if (!rows.length) {
      list.innerHTML = `<li class="empty">${icon('search')}<h3>No posts match</h3><p>Try a different search${$('fresher').checked ? ', or turn off “Fresher-friendly only”' : ''}.</p></li>`;
      $('more').hidden = true;
      return;
    }
    const html = rows.slice(before, state.shown).map((r, i) => card(r.job, r, i)).join('');
    if (resetPage) list.innerHTML = html;
    else list.insertAdjacentHTML('beforeend', html);
    $('more').hidden = rows.length <= state.shown;
    $('more').textContent = `Show more (${rows.length - Math.min(rows.length, state.shown)} left)`;
  }

  function card(j, match, i) {
    const t = j.tags || {};
    const badges = [];
    if (isNew(j)) badges.push('<span class="badge new">New today</span>');
    if (t.role) badges.push(`<span class="badge role">${esc(t.role)}</span>`);
    (t.cities || []).forEach((c) => badges.push(`<span class="badge">${icon('pin')}${esc(c)}</span>`));
    if (LABEL[t.jobType]) badges.push(`<span class="badge">${icon('brief')}${LABEL[t.jobType]}</span>`);
    if (LABEL[t.workMode]) badges.push(`<span class="badge">${LABEL[t.workMode]}</span>`);
    const p = pay(t);
    if (p) badges.push(`<span class="badge pay">${icon('rupee')}${esc(p)}</span>`);
    if (t.fresherFit === 'yes') badges.push(`<span class="badge ok">${icon('check')}Freshers OK</span>`);
    else if (t.fresherFit === 'no') badges.push(`<span class="badge warn">${t.expMinYears ? `Needs ${t.expMinYears}+ yrs` : 'Experienced role'}</span>`);

    const have = new Set((match.matched || []).map((s) => s.toLowerCase()));
    const chip = (s, implied) => `<span class="skill${implied ? ' implied' : ''}${have.has(s.toLowerCase()) ? ' have' : ''}"${implied ? ' title="Not named in the post, but usually needed for this role"' : ''}>${esc(s)}</span>`;
    const skills = [...(t.skills || []).map((s) => chip(s, false)), ...(t.impliedSkills || []).map((s) => chip(s, true))].join('');

    const sources = (j.sources || []).length > 1 ? `<span>${icon('layers')}Seen on ${j.sources.length} sites</span>` : '';
    const matchHtml = match.score != null
      ? `<div class="match"><div class="ring" style="--p:${match.score}" role="img" aria-label="${match.score}% match"><span>${match.score}%</span></div><div class="why">${match.why}</div></div>`
      : '';

    return `<li class="card" style="--i:${Math.min(i, 10)}">
      <div class="card-head">
        <div class="logo" aria-hidden="true">${esc(initials(j.company))}</div>
        <div class="card-title">
          <h3>${esc(j.title)}</h3>
          <div class="company">${esc(j.company)}</div>
        </div>
        <a class="btn primary small apply" href="${esc(safeUrl(j.url))}" target="_blank" rel="noopener nofollow">Apply${icon('ext')}<span class="sr-only"> to ${esc(j.title)} (opens the original post)</span></a>
      </div>
      ${matchHtml}
      <div class="meta">${badges.join('')}</div>
      ${skills ? `<div class="skills">${skills}</div>` : ''}
      ${j.description ? `<p class="desc">${esc(j.description)}</p>` : ''}
      <div class="foot-line"><span>${icon('clock')}${esc(ago(j.postedAt))}</span>${sources}</div>
    </li>`;
  }

  // ---------- views ----------
  function setView(v, push = true) {
    state.view = v;
    document.querySelectorAll('.nav-link[data-view]').forEach((a) => {
      if (a.dataset.view === v) a.setAttribute('aria-current', 'page');
      else a.removeAttribute('aria-current');
    });
    $('hero').hidden = v === 'fit';
    $('fit').hidden = v !== 'fit';
    if (v === 'fit' && App.fit) App.fit.panel($('fit'));
    if (push) history.pushState(null, '', v === 'fit' ? '?view=fit' : location.pathname);
    apply();
  }

  // ---------- events ----------
  let timer;
  $('q').addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(apply, 150); });
  [...SELECTS, 'fresher', 'stipend'].forEach((id) => $(id).addEventListener('change', () => apply()));
  document.querySelectorAll('#typeSeg button').forEach((b) => b.addEventListener('click', () => { state.type = b.dataset.type; syncSeg(); apply(); }));
  $('more').addEventListener('click', () => { state.shown += PAGE; apply(false); });
  $('reset').addEventListener('click', () => {
    $('q').value = '';
    SELECTS.forEach((k) => ($(k).value = ''));
    state.type = '';
    syncSeg();
    $('fresher').checked = true;
    $('stipend').checked = false;
    apply();
  });
  $('filterToggle').addEventListener('click', () => {
    const open = $('filters').classList.toggle('open');
    $('filterToggle').setAttribute('aria-expanded', String(open));
  });
  $('share').addEventListener('click', async () => {
    const label = $('share').querySelector('span');
    try { await navigator.clipboard.writeText(location.href); label.textContent = 'Link copied'; }
    catch { label.textContent = 'Copy from the address bar'; }
    setTimeout(() => (label.textContent = 'Copy link'), 1600);
  });
  document.querySelectorAll('.nav-link[data-view]').forEach((a) => a.addEventListener('click', (e) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey) return;
    e.preventDefault();
    setView(a.dataset.view);
    window.scrollTo({ top: 0 });
  }));
  window.addEventListener('popstate', () => {
    readUrl();
    setView(new URLSearchParams(location.search).get('view') === 'fit' ? 'fit' : 'browse', false);
  });

  load().catch((e) => {
    console.error(e);
    $('results').innerHTML = `<li class="empty"><h3>Could not load jobs</h3><p>Please refresh the page.</p></li>`;
  });
})();
