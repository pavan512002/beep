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
  const EXP_LABEL = { open: 'Freshers + experience not stated', all: 'All experience levels' };
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

  // "New today" = first seen on the latest run's calendar day in India: the same rule the
  // pipeline uses for stats.json newToday, so the badge count and the stats page agree.
  const istDay = (iso) => (iso ? new Date(iso).toLocaleDateString('en-CA', { timeZone: 'Asia/Kolkata' }) : '');
  function isNew(j) {
    const day = istDay(state.stats?.lastRun?.startedAt);
    return day ? istDay(j.firstSeenAt) === day : false;
  }
  const whenIst = (iso) => new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit', timeZone: 'Asia/Kolkata' });

  function kpis() {
    const r = state.stats?.lastRun;
    const cities = new Set(state.jobs.flatMap((j) => j.tags?.cities || []).filter((c) => c !== 'India (unspecified)' && c !== 'Remote'));
    const fresher = state.jobs.filter((j) => j.tags?.fresherFit === 'yes').length;
    const n = (v) => Number(v).toLocaleString('en-IN');
    if (r?.finishedAt) {
      // A run can finish while a source is down: say so, and when posts were last really collected.
      const degraded = r.status === 'degraded'
        ? ` · <span>Some sources failed${r.lastSuccessfulCollectionAt ? `; posts last collected <b>${esc(whenIst(r.lastSuccessfulCollectionAt))}</b>` : ''}</span>`
        : '';
      $('updated').innerHTML = `<span class="live" aria-hidden="true"></span><span>Updated <b>${esc(whenIst(r.finishedAt))}</b>${degraded}</span>`;
    }
    const items = [
      [n(state.jobs.length), 'live posts'],
      [n(fresher), 'say freshers welcome'],
      [n(state.jobs.filter(isNew).length), 'new today'],
      [n(cities.size), 'cities'],
    ];
    $('kpis').innerHTML = items.map(([v, l]) => `<li class="kpi"><b>${v}</b><span>${l}</span></li>`).join('');
  }

  // ---------- URL <-> controls ----------
  function readUrl() {
    const p = new URLSearchParams(location.search);
    $('q').value = p.get('q') || '';
    SELECTS.forEach((k) => ($(k).value = p.get(k) || ''));
    state.type = p.get('type') || '';
    $('exp').value = ['open', 'all'].includes(p.get('exp')) ? p.get('exp') : 'yes';
    $('stipend').checked = p.get('pay') === '1';
    syncSeg();
  }
  function writeUrl() {
    const p = new URLSearchParams();
    if (state.view === 'fit') p.set('view', 'fit');
    if ($('q').value.trim()) p.set('q', $('q').value.trim());
    if (state.type) p.set('type', state.type);
    SELECTS.forEach((k) => $(k).value && p.set(k, $(k).value));
    if ($('exp').value !== 'yes') p.set('exp', $('exp').value);
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
    const exp = $('exp').value, paid = $('stipend').checked;
    const since = days ? Date.now() - days * DAY : 0;

    list = list.filter((j) => {
      const t = j.tags || {};
      if (role && t.role !== role) return false;
      if (city && !(t.cities || []).includes(city)) return false;
      if (type && t.jobType !== type) return false;
      if (mode && t.workMode !== mode) return false;
      if (since && time(j.postedAt) < since) return false;
      // Default is strict: only posts that clearly welcome freshers. "unclear" usually means the
      // requirements were cut off in the 500-character preview, not that freshers are welcome.
      if (exp === 'yes' && t.fresherFit !== 'yes') return false;
      if (exp === 'open' && t.fresherFit === 'no') return false;
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
    if ($('exp').value !== 'yes') add(EXP_LABEL[$('exp').value], () => ($('exp').value = 'yes'));
    if ($('stipend').checked) add('Pay mentioned', () => ($('stipend').checked = false));
    const box = $('activeChips');
    box.innerHTML = chips.map((c, i) => `<button class="chip-x" type="button" data-i="${i}" aria-label="Remove filter ${esc(c.label)}">${esc(c.label)}${icon('x')}</button>`).join('');
    box.querySelectorAll('button').forEach((b) => b.addEventListener('click', () => { chips[b.dataset.i].clear(); apply(); }));
    const n = chips.length;
    $('filterCount').hidden = n === 0;
    $('filterCount').textContent = n;
  }

  // ---------- render ----------
  let quiet = false;   // true while typing: re-render without the entrance animation
  function emptyState(iconName, title, body, action = '') {
    return `<li class="empty"><div class="empty-icon">${icon(iconName)}</div><h3>${title}</h3><p>${body}</p>${action}</li>`;
  }

  function apply(resetPage = true) {
    const before = resetPage ? 0 : state.shown - PAGE;
    if (resetPage) state.shown = PAGE;
    writeUrl();
    activeChips();

    const list = $('results');
    list.classList.toggle('quiet', quiet && resetPage);
    const fitMode = state.view === 'fit' && App.fit;
    if (fitMode && !App.fit.ready()) {
      $('count').innerHTML = 'Your matches will appear here';
      list.innerHTML = emptyState('spark', 'Add a few skills to see your matches', 'Type what you know above, e.g. <b>DSA, Java, SQL</b>, or tap the suggestions.');
      $('more').hidden = true;
      $('sheetDone').textContent = 'Show results';
      return;
    }
    const jobs = filtered();
    const rows = fitMode ? App.fit.rank(jobs) : jobs.map((job) => ({ job }));
    const total = rows.length.toLocaleString('en-IN');
    const label = fitMode ? 'matching posts, best fit first' : rows.length === 1 ? 'post' : 'posts';
    $('count').innerHTML = `<b>${total}</b> ${label}`;
    $('sheetDone').textContent = rows.length ? `Show ${total} ${rows.length === 1 ? 'post' : 'posts'}` : 'No posts match';

    if (!rows.length) {
      const strict = $('exp').value === 'yes';
      list.innerHTML = emptyState('search', 'No posts match',
        `Try a different search${strict ? ', or set Experience to “Freshers + not stated”' : ''}.`,
        '<button class="btn small" type="button" data-reset>Clear search and filters</button>');
      list.querySelector('[data-reset]').addEventListener('click', () => $('reset').click());
      $('more').hidden = true;
      return;
    }
    const html = rows.slice(before, state.shown).map((r, i) => card(r.job, r, i)).join('');
    if (resetPage) list.innerHTML = html;
    else list.insertAdjacentHTML('beforeend', html);
    const left = rows.length - Math.min(rows.length, state.shown);
    $('more').hidden = left <= 0;
    $('more').innerHTML = `Show more <span>· ${left.toLocaleString('en-IN')} left</span>`;
  }

  function place(c) { return c === 'India (unspecified)' ? 'India' : c; }

  function card(j, match, i) {
    const t = j.tags || {};
    const url = esc(safeUrl(j.url));

    // Line under the title: plain facts, no chrome.
    const sub = [`<span class="company">${esc(j.company)}</span>`];
    if ((t.cities || []).length) sub.push(`<span>${esc(t.cities.map(place).join(', '))}</span>`);
    if (LABEL[t.jobType]) sub.push(`<span>${LABEL[t.jobType]}</span>`);
    if (LABEL[t.workMode]) sub.push(`<span>${LABEL[t.workMode]}</span>`);

    // Badges carry meaning only: role, fresher fit, pay, new.
    const badges = [];
    if (t.role) badges.push(`<span class="badge">${esc(t.role)}</span>`);
    if (t.fresherFit === 'yes') badges.push(`<span class="badge ok">${icon('check')}Freshers OK</span>`);
    else if (t.fresherFit === 'no') badges.push(`<span class="badge warn">${t.expMinYears ? `Needs ${t.expMinYears}+ yrs` : 'Experienced role'}</span>`);
    const p = pay(t);
    if (p) {
      const from = j.paySource === 'listing' ? ' title="From the salary the listing states, converted to a monthly figure"' : '';
      badges.push(`<span class="badge pay"${from}>${icon('rupee')}${esc(p)}</span>`);
    }
    if (isNew(j)) badges.push('<span class="badge new">New today</span>');
    if (t.fresherFit !== 'yes' && t.fresherFit !== 'no') badges.push('<span class="badge muted" title="The preview does not say how much experience is needed">Experience not stated</span>');

    const have = new Set((match.matched || []).map((s) => s.toLowerCase()));
    const chip = (s, implied) => `<span class="skill${implied ? ' implied' : ''}${have.has(s.toLowerCase()) ? ' have' : ''}">${esc(s)}</span>`;
    const stated = (t.skills || []).map((s) => chip(s, false)).join('');
    const implied = (t.impliedSkills || []).map((s) => chip(s, true)).join('');
    const skills = (stated ? `<span class="skills-label">Skills</span>${stated}` : '')
      + (implied ? `<span class="skills-label">${stated ? 'Also usually needed' : 'Usually needed for this role'}</span>${implied}` : '');
    // Adzuna's API only gives the first ~500 characters; say so instead of implying that's all.
    const cut = /…$|\.\.\.$/.test((j.description || '').trim());

    const foot = [];
    if ((j.sources || []).length > 1) foot.push(`<span>${icon('layers')}Seen on ${j.sources.length} sites</span>`);
    if (cut) foot.push(`<a class="read-more" href="${url}" target="_blank" rel="noopener nofollow">Preview only, read the full post${icon('ext')}</a>`);

    let matchHtml = '';
    if (match.score != null) {
      const tier = match.score >= 70 ? '' : match.score >= 50 ? ' mid' : ' low';
      matchHtml = `<div class="match"><div class="score${tier}" role="img" aria-label="${match.score}% match"><b>${match.score}<small>%</small></b><i style="--p:${match.score}"></i></div><div class="why">${match.why}</div></div>`;
    }

    return `<li class="card" style="--i:${Math.min(i, 10)}">
      <div class="logo" aria-hidden="true">${esc(initials(j.company))}</div>
      <div class="card-main">
        <div class="card-title">
          <h3>${esc(j.title)}</h3>
          <div class="card-sub">${sub.join('')}</div>
        </div>
        ${matchHtml}
        <div class="meta">${badges.join('')}</div>
        ${skills ? `<div class="skills">${skills}</div>` : ''}
        ${j.description ? `<p class="desc">${esc(j.description)}</p>` : ''}
        ${foot.length ? `<div class="foot-line">${foot.join('')}</div>` : ''}
      </div>
      <div class="card-aside">
        <a class="btn apply" href="${url}" target="_blank" rel="noopener nofollow">Apply${icon('ext')}<span class="sr-only"> to ${esc(j.title)} (opens the original post)</span></a>
        <span class="posted">${esc(ago(j.postedAt))}</span>
      </div>
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
  $('q').addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => { quiet = true; apply(); quiet = false; }, 150);
  });
  [...SELECTS, 'exp', 'stipend'].forEach((id) => $(id).addEventListener('change', () => apply()));
  document.querySelectorAll('#typeSeg button').forEach((b) => b.addEventListener('click', () => { state.type = b.dataset.type; syncSeg(); apply(); }));
  $('more').addEventListener('click', () => { state.shown += PAGE; apply(false); });
  $('reset').addEventListener('click', () => {
    $('q').value = '';
    SELECTS.forEach((k) => ($(k).value = ''));
    state.type = '';
    syncSeg();
    $('exp').value = 'yes';
    $('stipend').checked = false;
    apply();
  });
  // Below 1024px the filters are a bottom sheet with a scrim; above, a plain sticky column.
  const sheetQuery = window.matchMedia('(max-width: 1023px)');
  function setSheet(open) {
    const sheet = $('filters');
    const isSheet = sheetQuery.matches;
    open = open && isSheet;
    sheet.classList.toggle('open', open);
    $('scrim').hidden = !open;
    document.body.classList.toggle('sheet-open', open);
    $('filterToggle').setAttribute('aria-expanded', String(open));
    if (open) {
      sheet.setAttribute('role', 'dialog');
      sheet.setAttribute('aria-modal', 'true');
      requestAnimationFrame(() => $('role').focus({ preventScroll: true }));
    } else {
      sheet.removeAttribute('role');
      sheet.removeAttribute('aria-modal');
    }
  }
  $('filterToggle').addEventListener('click', () => setSheet(!$('filters').classList.contains('open')));
  $('scrim').addEventListener('click', () => setSheet(false));
  $('sheetDone').addEventListener('click', () => { setSheet(false); $('filterToggle').focus(); });
  sheetQuery.addEventListener('change', () => setSheet(false));

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && $('filters').classList.contains('open')) { setSheet(false); $('filterToggle').focus(); return; }
    // "/" jumps to search, like most search-first tools.
    const typing = /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement?.tagName) || document.activeElement?.isContentEditable;
    if (e.key === '/' && !typing && !e.metaKey && !e.ctrlKey && !e.altKey) { e.preventDefault(); $('q').focus(); }
  });

  let shareTimer;
  $('share').addEventListener('click', async () => {
    const btn = $('share');
    const label = btn.querySelector('span');
    const use = btn.querySelector('use');
    let ok = true;
    try { await navigator.clipboard.writeText(location.href); label.textContent = 'Link copied'; }
    catch { ok = false; label.textContent = 'Copy from the address bar'; }
    btn.classList.toggle('done', ok);
    if (ok) use.setAttribute('href', '#i-check');
    clearTimeout(shareTimer);
    shareTimer = setTimeout(() => {
      label.textContent = 'Copy link';
      btn.classList.remove('done');
      use.setAttribute('href', '#i-link');
    }, 1800);
  });

  const topbar = document.querySelector('.topbar');
  const onScroll = () => topbar.classList.toggle('scrolled', window.scrollY > 4);
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
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
    $('results').setAttribute('aria-busy', 'false');
    $('results').innerHTML = `<li class="empty error"><div class="empty-icon">${icon('alert')}</div><h3>Could not load jobs</h3><p>Check your connection and try again.</p><button class="btn small" type="button" onclick="location.reload()">Reload</button></li>`;
  });
})();
