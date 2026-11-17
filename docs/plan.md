# Implementation Plan: Beep Fresher Jobs Tool

## Context

Beep (career platform for Indian college students) shortlisted Pavan for the Gen AI & Engineering role (see `email.md`). The take-home asks for a **live web tool** that:
- collects **200+ real fresher job/internship posts in India** from public sources, respecting each site's terms
- **removes duplicates**
- **uses an LLM to tag each post by role and city**
- shows them on a **searchable page** that **refreshes every day on its own**

It also asks for a ≤5 min Loom (architecture, what broke, fixes), the repo, a **50-post hand-checked accuracy number**, the **cost per 1,000 posts**, and a doc/sheet with the data, AI tools and prompts. Deadline: **Fri 20 Nov 2026, 11:59 PM**. The best entries get a 30-min call where the founder asks for **live changes**, so the code must stay simple and easy to modify.

Decisions already made:
- **Pipeline:** Spring Boot (Java)
- **Hosting:** Vercel
- **LLM:** Anthropic (key available)
- **Unstop:** decide after reading its ToS, so it's built behind an on/off switch

Research findings live in `research.md`. The key one: **company career boards have ~0 fresher roles** (tested 175 live posts), so the volume has to come from aggregator APIs.

---

## Architecture

Vercel cannot run Java, so the Spring Boot app runs as a **daily batch job in GitHub Actions** and publishes JSON that a **static Vercel site** reads.

```
GitHub Actions (cron 06:00 IST + manual "Run workflow" button)
  └─ java -jar pipeline.jar   (Spring Boot, no web server)
       1. FETCH      Adzuna · Jooble · Careerjet · Greenhouse/Lever/Ashby · [Unstop, if allowed]
       2. NORMALIZE  one Job schema, clean HTML, canonical URL, parse dates
       3. DEDUP      exact (URL hash) → fuzzy (company + title + city) → merge sources
       4. TAG        Claude Haiku 5.5, structured JSON, only posts not already in the tag cache
       5. EXPIRE     drop posts older than 30 days or not seen in the last 3 runs
       6. PUBLISH    web/data/jobs.json, web/data/stats.json, state/tag-cache.json, export/jobs.csv
  └─ git commit + push (only if data changed)
          └─ Vercel auto-deploys web/  →  live site updates
```

Why not a Spring Boot server on Render/Railway with `@Scheduled`: free tiers sleep, so the daily job wouldn't fire, and it adds a database and hosting to manage. The batch job in GitHub Actions is free, and the daily git commits **prove** the refresh happened.

---

## Features (chosen for Beep's users and for the evaluation)

### Core (required by the brief)
| Feature | Notes |
|---|---|
| 200+ real posts from allowed sources | Official APIs plus public ATS JSON. Unstop is optional |
| Dedup | Exact plus fuzzy, merged with `sources[]`. Counts are reported on the stats page |
| LLM tag: **role** | Fixed 14-class taxonomy, enforced as an enum through structured output |
| LLM tag: **city** | Canonical Indian city list plus `Remote`; multiple cities allowed; alias map applied after the LLM |
| Searchable page | Full-text search plus filters |
| Daily auto-refresh | GitHub Actions cron |

### Additions (chosen deliberately; each one is cheap)
| Feature | Why it's worth it |
|---|---|
| **Fresher-fit flag** (`yes / no / unclear`) and experience range | Aggregators return "3–5 yrs" posts even for a "fresher" search. This is the filter students care about most, and it's the strongest Gen AI story |
| **Job type**: internship vs full-time | The brief says "job or internship", and students filter on this first |
| **Work mode**: onsite / hybrid / remote | A common filter. Comes almost free from the same LLM call |
| **Stipend/salary** (monthly INR, only if stated) | Students care a lot. The LLM pulls it out of free text. Shows "Not disclosed" otherwise |
| **Top skills** (up to 5) | Better search ("react", "excel") and visible chips on cards |
| **New today badge** plus sort by newest | Gives users a reason to come back daily, and makes the refresh visible |
| **Shareable filter URLs** (`?role=Data&city=Pune&type=internship`) | Students share links in WhatsApp groups. ~20 lines of JS |
| **Stats page** (`/stats.html`) | Last run time, posts per source, raw→unique dedup funnel, new today, tokens, **cost per 1,000 posts**, **eval accuracy**. This is the "put a number on it" answer, live on the site |
| **Low-confidence flag** | When the LLM's city disagrees with a rule-based parse of the location string, the post is flagged. Used to pick eval cases and the "what broke" stories |

### Deliberately excluded (scope control; mention on the call as next steps)
Login/saved jobs, email/WhatsApp alerts, AI chat search, resume matching, a database. None of these are needed to answer the brief, and each adds failure points before Friday.

---

## Repo layout

```
beep-jobs/                         (new GitHub repo; current folder becomes its root)
├─ docs/
│  ├─ email.md, research.md        (move existing files here)
│  ├─ prompts.md                   (every prompt, versioned)
│  ├─ build-log.md                 ("what broke and how I fixed it", written as we go)
│  └─ architecture.md              (diagram used in the video)
├─ pipeline/                       (Spring Boot 3.x, Java 21, Maven)
│  ├─ pom.xml
│  └─ src/main/java/in/beep/jobs/
│     ├─ PipelineApplication.java       (CommandLineRunner, web-application-type=none)
│     ├─ PipelineRunner.java            (runs the 6 steps, times each one)
│     ├─ model/  Job.java, JobTags.java, RunStats.java, enums (Role, JobType, WorkMode, FresherFit)
│     ├─ source/ JobSource.java (interface), AdzunaSource, JoobleSource, CareerjetSource,
│     │          GreenhouseSource, LeverSource, AshbySource, UnstopSource
│     ├─ normalize/ Normalizer.java, CityAliases.java, TextCleaner.java
│     ├─ dedup/  Deduplicator.java
│     ├─ tag/    ClaudeTagger.java, TagCache.java, CostTracker.java, prompt/system-v1.txt
│     ├─ store/  JsonStore.java, CsvExporter.java
│     └─ eval/   EvalSampler.java, EvalScorer.java   (run with --mode=eval-sample / eval-score)
│  └─ src/main/resources/application.yml   (enabled sources, queries, company slugs, limits)
│  └─ src/test/java/...                    (Normalizer, CityAliases, Deduplicator tests)
├─ state/tag-cache.json            (hash → tags, so posts are never re-tagged; not deployed)
├─ web/                            (Vercel root directory; static)
│  ├─ index.html, app.js, styles.css, stats.html
│  └─ data/jobs.json, data/stats.json
├─ export/jobs.csv                 (for the Google Sheet)
├─ eval/sample-50.csv, eval/labeled-50.csv, eval/report.md
└─ .github/workflows/daily.yml
```

Dependencies: `spring-boot-starter` (RestClient via `spring-web`), Jackson, `com.anthropic:anthropic-java:3.3.0`, `org.apache.commons:commons-text` (Jaro-Winkler for fuzzy titles), `org.jsoup:jsoup` (HTML cleaning; Unstop if used), JUnit 5.

---

## Component details

### 1. Sources (`JobSource` interface: `String name(); boolean enabled(); List<RawJob> fetch();`)
- **AdzunaSource:** `GET api.adzuna.com/v1/api/jobs/in/search/{page}`, queries `fresher`, `intern`, `internship`, `graduate trainee`, `entry level`, `max_days_old=30`, 50/page, up to 5 pages per query. Attribution "Jobs by Adzuna" on the site.
- **JoobleSource:** `POST jooble.org/api/{key}`, body `{keywords, location:"India", page}`. Enabled only if the key exists.
- **CareerjetSource:** locale `en_IN`, affid. Enabled only if the key exists.
- **Greenhouse / Lever / Ashby:** a configurable list of Indian company slugs (start: groww, inmobi, druva, cred, meesho, plus more found during build). Keep only India locations and entry-level titles. Bonus volume only.
- **UnstopSource:** `enabled: false` until the ToS is read. Max 1 request every 2 s, honest User-Agent.
- Every source: timeouts, 2 retries, catches its own errors. **One failing source never fails the run**; its error goes into `stats.json`.
- A missing API key turns a source off and logs it, so the pipeline runs with whatever keys exist.

### 2. Normalize
`RawJob → Job`: strip HTML, trim the description to 2,000 characters, canonicalise the URL (drop `utm_*`, tracking params, fragments), parse `postedAt`, normalise the company name (drop "Pvt Ltd", "Private Limited", "LLP", "India"), and keep `locationRaw` for city tagging.

### 3. Dedup
1. Exact: `sha1(canonicalUrl)`.
2. Fuzzy: group by normalised company. Within a group, titles with Jaro-Winkler ≥ 0.92 after token-sort, plus an overlapping city, count as the same post.
3. Merge: keep the record with the longest description, union `sources[]`, keep the earliest `postedAt`.
4. Record the funnel `raw → afterExact → afterFuzzy` in `stats.json`.
5. Stable `id = sha1(normCompany|normTitle|primaryCity)`, so ids survive across days (needed for "New today" and the tag cache).

### 4. LLM tagging (`ClaudeTagger`)
- Model `claude-haiku-5-5`, effort `LOW` via `OutputConfig`. Typed structured output via `.outputConfig(JobTags.class)` (Java SDK `StructuredMessageCreateParams`); enums keep role, type and mode on the fixed lists.
- `JobTags` fields: `role`, `cities[]`, `workMode`, `jobType`, `fresherFit`, `expMinYears`, `expMaxYears`, `stipendMinInr`, `stipendMaxInr`, `skills[]` (≤5), `confidence`.
- The system prompt (`prompt/system-v1.txt`) holds the role taxonomy with one-line definitions, the canonical city list with alias rules, fresher rules (≤1 yr, intern, trainee, "freshers welcome", 2025/2026 batch), stipend rules (monthly INR; convert LPA ÷ 12; null if absent), and 6 tricky worked examples.
- User message: title, company, location raw, description (≤2,000 chars).
- Run 4 requests in parallel (small thread pool), and retry on 429/5xx using the SDK's retries.
- **Cache key** is `sha1(title+company+description) + promptVersion`. Only new posts get tagged. Bumping the prompt version re-tags everything (useful in the live call).
- After the LLM: map cities through `CityAliases`, and set the low-confidence flag when the LLM's city ≠ the rule-based city.
- `CostTracker` sums `usage()` input/output/cache tokens × price per model, writing per-run totals and **cost per 1,000 tagged posts** to `stats.json`.

### 5. Publish
- `web/data/jobs.json`: compact array, only the fields the UI needs.
- `web/data/stats.json`: run time, duration, per-source counts and errors, dedup funnel, new today, total, tokens, cost, cost per 1,000, plus eval accuracy (filled once eval is done).
- `export/jobs.csv`: full data for the Google Sheet.

### 6. Website (`web/`, plain HTML/JS, no build step)
- **Search:** MiniSearch (pinned CDN version), fields title/company/skills/description, fuzzy + prefix.
- **Filters:** role, city, job type, work mode, fresher-only (on by default), posted within 1/7/30 days, has stipend. Results sort newest first.
- **Card:** title, company, city chips, role tag, type and mode badges, stipend, skills chips, "New" badge, "seen on N sources", posted date, **Apply ↗** to the original URL.
- Filters sync to the URL, so links are shareable. Result count. Empty state.
- Mobile-first layout with a light/dark theme.
- Header strip: "Updated today 06:04 IST · 412 posts · 37 new" with a link to the stats page.
- `stats.html`: run numbers, dedup funnel, per-source bar chart, cost per 1,000, accuracy table.
- Footer: data sources plus the Adzuna attribution.

### 7. Daily automation (`.github/workflows/daily.yml`)
- Triggers: `schedule: cron "30 0 * * *"` (06:00 IST) **and** `workflow_dispatch` (a manual run button, useful in the demo and the live call).
- Steps: checkout → `setup-java` 21 with the Maven cache → `mvn -q -B package -DskipTests` → `java -jar` → commit `web/data`, `state`, `export` only if changed → push. Vercel deploys on push.
- Secrets: `ANTHROPIC_API_KEY`, `ADZUNA_APP_ID`, `ADZUNA_APP_KEY`, `JOOBLE_KEY`, `CAREERJET_AFFID`.
- Vercel project settings: root directory `web/`, framework "Other", no build command.

### 8. Evaluation (50 posts)
1. `--mode=eval-sample` writes 50 posts to `eval/sample-50.csv`, stratified by source and role, with half the low-confidence posts included so errors are found.
2. Pavan hand-labels them in the Google Sheet (correct role, correct city, correct fresher-fit, notes).
3. `--mode=eval-score` reads the labelled CSV and writes `eval/report.md` with per-field accuracy, a confusion list and the top error types. The numbers go into `stats.json`.
4. Comparison: re-tag the same 50 with `claude-sonnet-5-5` (`--tag-model=`) and report the accuracy and cost difference.
5. Fix the top error type in the prompt (v2), re-score, and report the before/after numbers. This is a "what broke" story for the video.

---

## Day-by-day plan

| When | Work | Done when |
|---|---|---|
| **Day 1 (Tue 17)** | Install JDK 21. Get the Adzuna key; request Jooble and Careerjet keys. Read Unstop's ToS. Create the GitHub repo and move the docs. Scaffold Spring Boot. Write the `Job` model, Adzuna + Greenhouse/Lever sources, and Normalizer with tests. | `java -jar` prints 300+ raw posts |
| **Day 2 (Wed 18)** | Deduplicator with tests. ClaudeTagger, tag cache and cost tracker. JsonStore. Website `index.html` with search and filters. Deploy to Vercel. GitHub Actions workflow plus secrets, run once manually. | Live URL shows 200+ tagged posts and the manual workflow run succeeds |
| **Day 3 (Thu 19)** | Add Jooble/Careerjet/Unstop if available. Stats page. Eval sample, hand-label 50, score. Sonnet comparison. Prompt v2 fix and re-score. Confirm the scheduled cron fired on its own that morning. | Accuracy and cost numbers on the stats page |
| **Day 4 (Fri 20)** | Google Sheet (tabs: Data, Eval-50, Prompts, AI tools, Cost). Finish the README and architecture diagram. Rehearse the live-change scenarios. Record the Loom (≤5 min). **Submit by evening, not 11:58 PM.** | Form submitted |

Keep notes in `docs/build-log.md` throughout: what broke, why, and the fix.

---

## Live-call readiness (likely "change it live" asks → where the change goes)
- "Add a new city or role" → enum plus prompt, then bump the prompt version
- "Add a source" → a new `JobSource` class plus a yml entry
- "Add a filter" (e.g. stipend > ₹10k) → `app.js`
- "Show only remote internships" → URL params already work
- "Change the dedup threshold" → `application.yml`
- "Run it now" → the GitHub Actions "Run workflow" button

---

## Verification
- **Unit tests** (`mvn test`): URL canonicalisation, company normalisation, city aliases (Bangalore→Bengaluru, Gurgaon→Gurugram), dedup cases (same post via two sources, different posts at the same company).
- **Local end-to-end:** `java -jar pipeline.jar` gives ≥200 posts in `web/data/jobs.json`, every one with a role and city. A second run immediately after makes **0 new LLM calls** (cache works) and the cost line shows ~$0.
- **Site:** serve `web/` locally (`npx serve web`), then check search, every filter, a shared URL, mobile width in devtools, and the stats page numbers against `stats.json`.
- **Automation:** a manual `workflow_dispatch` run commits new data and Vercel redeploys. The next morning, confirm the scheduled run happened (Actions tab plus the commit timestamp).
- **Terms check:** each source's terms and robots rules are noted in the README, and the Adzuna attribution is visible on the site.

## Risks and fallbacks
- **Jooble/Careerjet keys arrive late.** Adzuna alone gives 250 fresher posts per query × 5 queries, which still clears 200. Add more ATS company slugs as backup.
- **Adzuna's India coverage is thinner than expected.** Widen the queries (`trainee`, `graduate`, `apprentice`, `campus`) and the days window.
- **Haiku is weak on some field.** Report it honestly, show the Sonnet comparison, and fix it through the prompt.
- **The GitHub cron runs late** (GitHub can delay scheduled runs). That's acceptable; the manual trigger covers demos.
