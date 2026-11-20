/* Best fit: the student lists what they know; every post gets a match score.
 *
 * Score = 50% role fit + 35% skill overlap + 15% fresher fit
 *  - role fit: how much the student's skills point at the post's role (ROLE_SKILLS below).
 *    This is what makes "DSA + Java" find SDE posts that never mention DSA.
 *  - skill overlap: share of the post's skills the student has. Skills the post names count
 *    fully; skills the role implies (tagged by the LLM, shown dashed) count 60%.
 *  - fresher fit: yes 1, unclear 0.6, no 0.
 * Runs entirely in the browser: the profile never leaves the device.
 * Skill names must match pipeline/src/main/resources/prompts/skills.json. */
(() => {
  'use strict';
  const App = window.App;
  const STORE_KEY = 'fit.skills.v1';
  const MIN_SCORE = 35;
  const W = { role: 0.5, skill: 0.35, fresher: 0.15 };
  const IMPLIED_WEIGHT = 0.6;

  // How strongly each skill points to each role (0–1).
  const ROLE_SKILLS = {
    'Software Engineering': { DSA: 1, OOP: .8, Java: .8, 'C++': .8, Python: .6, C: .5, JavaScript: .7, TypeScript: .6, React: .6, Angular: .5, Vue: .4, 'Node.js': .6, 'Spring Boot': .7, 'Django/Flask': .5, 'REST APIs': .5, SQL: .5, MongoDB: .4, Git: .5, Linux: .4, 'System Design': .5, 'HTML/CSS': .5, Android: .6, iOS: .5, Flutter: .5, Kotlin: .5, Swift: .4, Go: .5, PHP: .5, 'C#/.NET': .6, 'Cloud (AWS/Azure/GCP)': .4, DevOps: .5, 'Docker/Kubernetes': .4, 'Manual Testing': .5, 'Test Automation': .6, Cybersecurity: .5, Networking: .4, 'Embedded Systems': .6, IoT: .4 },
    'Data & Analytics': { SQL: 1, Excel: 1, 'Data Analysis': 1, 'Power BI': .8, Tableau: .8, Statistics: .7, Python: .7, 'Data Engineering': .6, 'Business Analysis': .5, 'MS Office': .3 },
    'AI/ML': { Python: 1, 'Machine Learning': 1, 'Deep Learning': .8, 'Generative AI/LLMs': .8, NLP: .7, 'Computer Vision': .7, Statistics: .6, 'Data Analysis': .4, DSA: .4, SQL: .3 },
    'Product Management': { 'Business Analysis': .6, 'Market Research': .6, 'Project Management': .5, Communication: .5, 'UI/UX Design': .4, Figma: .4, SQL: .4, 'Data Analysis': .4 },
    Design: { 'UI/UX Design': 1, Figma: 1, 'Graphic Design': 1, 'Adobe Photoshop': .8, 'Video Editing': .6, 'HTML/CSS': .3 },
    Marketing: { 'Digital Marketing': 1, 'Social Media': .9, SEO: .8, 'Email Marketing': .6, 'Content Writing': .5, Copywriting: .5, 'Market Research': .4, Communication: .4, Excel: .3 },
    'Sales & Business Development': { Sales: 1, Communication: .8, 'Lead Generation': .8, 'Cold Calling': .8, Negotiation: .7, CRM: .6, 'English Fluency': .4, Hindi: .3, 'Regional Language': .3 },
    Operations: { Operations: 1, 'Supply Chain': .8, Excel: .6, 'MS Office': .5, 'Data Entry': .5, 'Project Management': .5, Communication: .3 },
    'Finance & Accounting': { Accounting: 1, 'Financial Analysis': .9, Tally: .9, 'GST/Taxation': .8, Excel: .7, 'MS Office': .3 },
    'HR & Recruiting': { Recruitment: 1, 'HR Operations': .9, Payroll: .7, Communication: .6, 'MS Office': .3, Excel: .3 },
    'Content & Media': { 'Content Writing': 1, Copywriting: .8, 'Video Editing': .6, 'Social Media': .5, 'English Fluency': .5, SEO: .4 },
    'Customer Support': { 'Customer Service': 1, Communication: .9, 'English Fluency': .6, CRM: .5, Hindi: .4, 'Regional Language': .4, 'MS Office': .3 },
    'Consulting & Strategy': { 'Business Analysis': .9, 'Market Research': .8, Excel: .7, Research: .6, 'Financial Analysis': .6, Communication: .5, 'Data Analysis': .5 },
  };

  // What students actually type → the fixed skill names.
  const ALIASES = {
    DSA: ['dsa', 'data structures', 'data structure', 'algorithms', 'leetcode', 'competitive programming', 'competitive coding', 'problem solving', 'codechef', 'codeforces', 'gfg'],
    Java: ['java', 'core java'], Python: ['python'], 'C++': ['c++', 'cpp'], C: ['c', 'c language', 'c programming'],
    JavaScript: ['javascript', 'js', 'es6'], TypeScript: ['typescript', 'ts'], Go: ['golang'], PHP: ['php', 'laravel'],
    'C#/.NET': ['c#', '.net', 'dotnet', 'asp.net'], Kotlin: ['kotlin'], Swift: ['swift'],
    'HTML/CSS': ['html', 'css', 'html5', 'css3', 'tailwind', 'bootstrap'], React: ['react', 'reactjs', 'react.js', 'react native'],
    Angular: ['angular'], Vue: ['vue', 'vuejs'], 'Node.js': ['node', 'nodejs', 'node.js', 'express', 'expressjs'],
    'Spring Boot': ['spring', 'spring boot', 'springboot'], 'Django/Flask': ['django', 'flask', 'fastapi'],
    'REST APIs': ['rest', 'rest api', 'rest apis', 'api', 'apis'], SQL: ['sql', 'mysql', 'postgres', 'postgresql', 'oracle', 'dbms', 'database', 'databases'],
    MongoDB: ['mongodb', 'mongo', 'nosql'], Git: ['git', 'github'], Linux: ['linux', 'unix', 'shell', 'bash'],
    OOP: ['oop', 'oops', 'object oriented'], 'System Design': ['system design', 'lld', 'hld'],
    Android: ['android'], iOS: ['ios'], Flutter: ['flutter', 'dart'], 'Cloud (AWS/Azure/GCP)': ['aws', 'azure', 'gcp', 'cloud'],
    DevOps: ['devops', 'ci/cd', 'jenkins'], 'Docker/Kubernetes': ['docker', 'kubernetes', 'k8s'],
    'Manual Testing': ['manual testing', 'testing', 'qa'], 'Test Automation': ['selenium', 'automation testing', 'test automation', 'cypress'],
    Cybersecurity: ['cybersecurity', 'cyber security', 'ethical hacking', 'pentesting', 'security'], Networking: ['networking', 'ccna', 'computer networks'],
    'Embedded Systems': ['embedded', 'embedded systems', 'microcontroller', 'arduino'], IoT: ['iot'],
    Excel: ['excel', 'ms excel', 'spreadsheets', 'google sheets'], 'Power BI': ['power bi', 'powerbi'], Tableau: ['tableau'],
    'Data Analysis': ['data analysis', 'data analytics', 'analytics', 'pandas', 'numpy'], Statistics: ['statistics', 'stats', 'probability'],
    'Data Engineering': ['data engineering', 'etl', 'spark', 'airflow'], 'Machine Learning': ['machine learning', 'ml', 'scikit-learn', 'sklearn'],
    'Deep Learning': ['deep learning', 'pytorch', 'tensorflow', 'neural networks'], NLP: ['nlp', 'natural language processing'],
    'Computer Vision': ['computer vision', 'opencv'], 'Generative AI/LLMs': ['genai', 'generative ai', 'llm', 'llms', 'prompt engineering', 'langchain', 'gen ai'],
    'UI/UX Design': ['ui/ux', 'ui ux', 'ux', 'ui design', 'user experience'], Figma: ['figma'],
    'Graphic Design': ['graphic design', 'canva', 'illustrator'], 'Adobe Photoshop': ['photoshop'], 'Video Editing': ['video editing', 'premiere', 'after effects', 'capcut'],
    'Content Writing': ['content writing', 'writing', 'blogging', 'blog'], Copywriting: ['copywriting'], 'Social Media': ['social media', 'instagram', 'linkedin'],
    SEO: ['seo'], 'Digital Marketing': ['digital marketing', 'marketing', 'google ads', 'meta ads', 'performance marketing'], 'Email Marketing': ['email marketing'],
    Communication: ['communication', 'communication skills', 'presentation', 'public speaking'], 'English Fluency': ['english', 'spoken english', 'english fluency'],
    Hindi: ['hindi'], 'Regional Language': ['tamil', 'telugu', 'kannada', 'malayalam', 'marathi', 'bengali', 'gujarati', 'punjabi', 'odia'],
    'Foreign Language': ['german', 'french', 'japanese', 'spanish'], Sales: ['sales', 'selling'], 'Lead Generation': ['lead generation'],
    'Cold Calling': ['cold calling', 'telecalling', 'telesales'], Negotiation: ['negotiation'], CRM: ['crm', 'salesforce', 'hubspot', 'zoho'],
    'Customer Service': ['customer service', 'customer support', 'customer handling'], 'Market Research': ['market research'],
    'Business Analysis': ['business analysis', 'business analyst'], 'Project Management': ['project management', 'jira', 'agile', 'scrum'],
    Operations: ['operations'], 'Supply Chain': ['supply chain', 'logistics', 'inventory'], 'Data Entry': ['data entry', 'typing'],
    'MS Office': ['ms office', 'powerpoint', 'microsoft office'], Accounting: ['accounting', 'accounts', 'bookkeeping'], Tally: ['tally', 'tally erp'],
    'GST/Taxation': ['gst', 'taxation', 'income tax', 'tds'], 'Financial Analysis': ['financial analysis', 'finance', 'financial modelling', 'valuation'],
    Recruitment: ['recruitment', 'recruiting', 'sourcing', 'talent acquisition'], 'HR Operations': ['hr', 'human resources', 'hr operations'], Payroll: ['payroll'],
    Teaching: ['teaching', 'tutoring'], AutoCAD: ['autocad'], 'Mechanical Design': ['mechanical design', 'solidworks', 'catia', 'creo'],
    Electrical: ['electrical'], 'Civil Engineering': ['civil engineering', 'civil', 'staad'], Electronics: ['electronics', 'vlsi', 'pcb'],
    Manufacturing: ['manufacturing', 'production'], Healthcare: ['healthcare', 'nursing', 'pharmacy', 'medical'], Hospitality: ['hospitality', 'hotel'],
    Legal: ['legal', 'law'], Research: ['research'],
  };
  const SKILLS = Object.keys(ALIASES);

  const QUICK = [
    ['Software', ['DSA', 'Java', 'Python', 'C++', 'JavaScript', 'React', 'SQL', 'Git']],
    ['Data & AI', ['Excel', 'SQL', 'Power BI', 'Python', 'Statistics', 'Machine Learning']],
    ['Business', ['Communication', 'Sales', 'English Fluency', 'MS Office', 'Customer Service']],
    ['Creative', ['Figma', 'Graphic Design', 'Content Writing', 'Social Media', 'Video Editing']],
    ['Finance & HR', ['Accounting', 'Tally', 'GST/Taxation', 'Recruitment']],
  ];

  // Longest alias first so "spring boot" wins over "spring", "c++" over "c".
  const COMMON_WORDS = new Set(['go']);   // skill names that are also everyday words ("I go to college")
  const ALIAS_LIST = Object.entries(ALIASES)
    .flatMap(([skill, list]) => [skill.toLowerCase(), ...list].map((a) => [a, skill]))
    .filter(([alias]) => !COMMON_WORDS.has(alias))
    .sort((a, b) => b[0].length - a[0].length);
  const reEsc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

  /** Free text ("I solve DSA in Java and know SQL") → fixed skill names. */
  function parse(text) {
    let s = ` ${text.toLowerCase()} `;
    const found = [];
    for (const [alias, skill] of ALIAS_LIST) {
      const re = new RegExp(`(?<![\\w+#.])${reEsc(alias)}(?![\\w+#])`, 'g');
      if (re.test(s)) {
        if (!found.includes(skill)) found.push(skill);
        s = s.replace(re, ' ');
      }
    }
    return found;
  }

  // ---------- profile (kept only in this browser) ----------
  let skills = [];
  try { skills = JSON.parse(localStorage.getItem(STORE_KEY) || '[]').filter((s) => SKILLS.includes(s)); } catch { skills = []; }
  function save() { try { localStorage.setItem(STORE_KEY, JSON.stringify(skills)); } catch { /* private mode: fine */ } }
  function add(list) {
    let changed = false;
    list.forEach((s) => { if (SKILLS.includes(s) && !skills.includes(s)) { skills.push(s); changed = true; } });
    if (changed) { save(); renderChips(); App.apply(); }
  }
  function remove(s) {
    skills = skills.filter((x) => x !== s);
    save(); renderChips(); App.apply();
  }

  // ---------- scoring ----------
  function rank(jobs) {
    const mine = new Set(skills);
    const rows = [];
    for (const job of jobs) {
      const t = job.tags;
      if (!t) continue;
      const weights = ROLE_SKILLS[t.role] || {};
      let roleSum = 0;
      mine.forEach((s) => (roleSum += weights[s] || 0));
      const roleFit = Math.min(1, roleSum / 1.5);

      const need = [...(t.skills || []).map((s) => [s, 1]), ...(t.impliedSkills || []).map((s) => [s, IMPLIED_WEIGHT])];
      const total = need.reduce((a, [, w]) => a + w, 0);
      const got = need.filter(([s]) => mine.has(s));
      const skillFit = total ? got.reduce((a, [, w]) => a + w, 0) / total : roleFit * 0.5;
      const fresher = t.fresherFit === 'yes' ? 1 : t.fresherFit === 'unclear' ? 0.6 : 0;

      if (roleFit === 0 && got.length === 0) continue;
      const score = Math.round(100 * (W.role * roleFit + W.skill * skillFit + W.fresher * fresher));
      if (score < MIN_SCORE) continue;

      const matched = got.map(([s]) => s);
      const missing = need.map(([s]) => s).filter((s) => !mine.has(s)).slice(0, 3);
      const why = [];
      if (matched.length) why.push(`<span><b>You have:</b> ${matched.map(App.esc).join(', ')}</span>`);
      if (roleFit >= 0.5) why.push(`<span>Your skills suit <b>${App.esc(t.role)}</b> roles</span>`);
      if (!why.length) why.push('<span>Partly related to your skills</span>');
      if (missing.length) why.push(`<span class="gap">To learn: ${missing.map(App.esc).join(', ')}</span>`);
      rows.push({ job, score, matched, why: why.join('') });
    }
    const see = document.getElementById('seeMatches');
    if (see) { see.hidden = !skills.length; see.textContent = `See my ${rows.length} matches ↓`; }
    return rows.sort((a, b) => b.score - a.score || Date.parse(b.job.postedAt || 0) - Date.parse(a.job.postedAt || 0));
  }

  // ---------- panel ----------
  let built = false;
  let active = -1;
  function panel(el) {
    if (built) return;
    built = true;
    el.innerHTML = `
      <div class="fit-card">
        <div class="fit-main">
          <p class="eyebrow">${App.icon('spark')}<span>Best fit</span></p>
          <h1 id="fit-title">Find jobs that fit your skills</h1>
          <p class="lead">Not sure which roles to look for? Tell us what you know. We match it to roles, rank every post, and show what you'd still need to learn.</p>
          <ol class="steps"><li><b>1</b>Add your skills</li><li><b>2</b>Narrow by type or city</li><li><b>3</b>Apply to your best matches</li></ol>
          <div class="field">
            <span id="skillLabel">Your skills</span>
            <div class="suggest">
              <div class="skill-box" id="skillBox">
                <span id="myChips" style="display:contents"></span>
                <input id="skillInput" type="text" autocomplete="off" enterkeyhint="done" placeholder="e.g. dsa in java, sql, excel"
                  role="combobox" aria-labelledby="skillLabel" aria-autocomplete="list" aria-expanded="false" aria-controls="skillSuggest">
              </div>
              <ul class="suggest-list" id="skillSuggest" role="listbox" hidden></ul>
            </div>
            <p class="hint">Press Enter or comma after each one. You can also paste a sentence like “I solve DSA problems in Java and know SQL”.</p>
          </div>
          <p class="privacy">${App.icon('shield')}<span>Your skills are saved only in this browser. Nothing is sent to a server.</span></p>
          <button type="button" class="btn primary see-matches" id="seeMatches" hidden></button>
        </div>
        <div class="quick">
          <h3>Quick add</h3>
          ${QUICK.map(([name, list]) => `<div class="quick-group"><span>${App.esc(name)}</span><div class="quick-chips">${list.map((s) => `<button type="button" class="add-chip" data-skill="${App.esc(s)}" aria-pressed="false">${App.esc(s)}</button>`).join('')}</div></div>`).join('')}
        </div>
      </div>`;

    const input = el.querySelector('#skillInput');
    const list = el.querySelector('#skillSuggest');
    el.querySelector('#skillBox').addEventListener('click', () => input.focus());
    el.querySelectorAll('.add-chip').forEach((b) => b.addEventListener('click', () => {
      const s = b.dataset.skill;
      if (skills.includes(s)) remove(s); else add([s]);
    }));

    const commit = () => {
      const text = input.value.trim();
      if (!text) return;
      const pick = active >= 0 ? list.children[active]?.dataset.skill : null;
      const found = pick ? [pick] : parse(text);
      if (!found.length && list.children[0]) found.push(list.children[0].dataset.skill);
      add(found);
      input.value = '';
      hideSuggest();
    };
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); commit(); }
      else if (e.key === 'Backspace' && !input.value && skills.length) remove(skills[skills.length - 1]);
      else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const n = list.children.length;
        if (!n) return;
        active = (active + (e.key === 'ArrowDown' ? 1 : -1) + n) % n;
        [...list.children].forEach((li, i) => li.setAttribute('aria-selected', String(i === active)));
        // Focus stays in the input, so tell assistive tech which option is current.
        input.setAttribute('aria-activedescendant', list.children[active].id);
        list.children[active].scrollIntoView({ block: 'nearest' });
      } else if (e.key === 'Escape') hideSuggest();
    });
    input.addEventListener('input', () => suggest(input.value));
    input.addEventListener('paste', () => setTimeout(() => {
      const found = parse(input.value);
      if (found.length > 1) { add(found); input.value = ''; hideSuggest(); }
    }));
    input.addEventListener('blur', () => setTimeout(hideSuggest, 150));
    list.addEventListener('mousedown', (e) => {
      const li = e.target.closest('li');
      if (li) { e.preventDefault(); add([li.dataset.skill]); input.value = ''; hideSuggest(); input.focus(); }
    });

    function suggest(text) {
      const frag = text.split(',').pop().trim().toLowerCase();
      active = -1;
      input.removeAttribute('aria-activedescendant');
      if (!frag) return hideSuggest();
      const hits = [];
      for (const [alias, skill] of ALIAS_LIST) {
        if (alias.startsWith(frag) || (frag.length > 2 && alias.includes(frag))) {
          if (!hits.includes(skill) && !skills.includes(skill)) hits.push(skill);
        }
        if (hits.length >= 6) break;
      }
      if (!hits.length) return hideSuggest();
      list.innerHTML = hits.map((s, i) => `<li role="option" id="sg${i}" data-skill="${App.esc(s)}" aria-selected="false">${App.esc(s)}</li>`).join('');
      list.hidden = false;
      input.setAttribute('aria-expanded', 'true');
    }
    function hideSuggest() {
      list.hidden = true;
      list.innerHTML = '';
      active = -1;
      input.removeAttribute('aria-activedescendant');
      input.setAttribute('aria-expanded', 'false');
    }
    el.querySelector('#seeMatches').addEventListener('click', () =>
      document.getElementById('count').scrollIntoView({ behavior: 'smooth', block: 'start' }));
    renderChips();
  }

  function renderChips() {
    const box = document.getElementById('myChips');
    if (!box) return;
    box.innerHTML = skills.map((s) => `<span class="my-skill">${App.esc(s)}<button type="button" data-skill="${App.esc(s)}" aria-label="Remove ${App.esc(s)}">${App.icon('x')}</button></span>`).join('');
    box.querySelectorAll('button').forEach((b) => b.addEventListener('click', (e) => { e.stopPropagation(); remove(b.dataset.skill); }));
    document.querySelectorAll('.add-chip').forEach((b) => b.setAttribute('aria-pressed', String(skills.includes(b.dataset.skill))));
  }

  App.fit = { panel, rank, ready: () => skills.length > 0, parse };
})();
