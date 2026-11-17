# R&D: Fresher Jobs Tool for Beep

Research done 17 Nov 2026. Deadline is Fri 20 Nov, 11:59 PM. See [email.md](email.md) for the brief.

## TL;DR recommendation

Build a **small, transparent pipeline plus a static search page**:

```
GitHub Actions cron (daily, 06:00 IST)
  └─ Python pipeline
       1. fetch    → Adzuna API, Jooble API, Careerjet API, Unstop public pages, ATS boards
       2. normalize→ one schema for every source
       3. dedup    → URL canonicalisation + fuzzy (company, title, city)
       4. tag      → Claude Haiku 5.5, structured JSON output, only NEW posts (cached by hash)
       5. publish  → data/jobs.json + data/runs.json committed to repo
  └─ Static site (Vercel or GitHub Pages) with client-side search (MiniSearch)
```

Why this shape:
- **Refreshes on its own** at no cost, and the git history *proves* it ran every day (useful in the video).
- **Few moving parts.** No database or server, so it's easy to change live on the founder call.
- **Cheap.** Only new posts get tagged, so a daily run costs a fraction of a cent.
- **At this scale (hundreds to a few thousand posts), client-side search is instant.** A DB adds nothing yet.

Upgrade path, if asked on the call: move to Postgres plus an API route when the data outgrows one JSON file (~20k posts).

---

## 1. Sources: what's allowed and what actually has fresher posts

The hard part of this task is **sources, not code**. Two findings drive the design:

1. **The big Indian job sites forbid scraping.** LinkedIn, Naukri, Indeed and Internshala are out. We use official APIs and pages that robots.txt allows.
2. **Company career boards have almost no fresher roles.** I tested live public ATS boards. Of ~175 posts from Groww, InMobi, Druva, CRED and Meesho, **0 were genuine fresher/intern roles** (the "Associate" hits were all "Senior Associate"). So aggregator APIs with fresher/intern keywords have to supply most of the volume.

| Source | Access | Terms / robots | Fresher yield | Verdict |
|---|---|---|---|---|
| **Adzuna API** (`/v1/api/jobs/in/search`) | Free key, instant | Official API. Must show "Adzuna" attribution linking back | Good with `what=fresher` / `intern`, filter by `max_days_old` | **Primary.** Confirm India coverage once we have the key |
| **Jooble API** (`POST /api/{key}`) | Key via request form (manual approval) | Official API | Good, aggregates Indian boards | **Primary.** Request key **today**; approval may take time |
| **Careerjet API** (locale `en_IN`) | Partner account (affid) | Official API | Good | **Secondary.** Needs signup |
| **Unstop** (`/internship/`, `/job/`) | Public pages | robots.txt **allows** `/internship/`, `/job/`, `/api/public/*`. ToS page didn't load for me; **check manually** | Very high (student platform) | **Use if ToS is OK.** Polite rate (1 req/2s), proper User-Agent |
| **Greenhouse / Lever / Ashby boards** | Public JSON, no key | Officially meant for public consumption | Very low for freshers | **Bonus only.** Keep a list of Indian startups and filter to entry-level |
| Internshala | — | robots.txt disallows `/internship/details/`, `/job/details/`, search and query strings, and blocks AI crawlers | High | **Avoid** |
| LinkedIn, Naukri, Indeed, Wellfound | — | ToS prohibit scraping | — | **Avoid.** Say so in the video; it shows judgment |

Verified live: Greenhouse (`groww`, `inmobi`, `druva`) and Lever (`cred`, `meesho`) boards return JSON with no key.

**Volume math:** 3 aggregator APIs × ~6 queries (`fresher`, `intern`, `internship`, `graduate trainee`, `entry level`, `0-1 years`) × 2–5 pages gives an estimated 500–1,500 raw posts, which should leave **300+ unique posts after dedup**. Enough margin over 200.

### Action items (today)
- [ ] Sign up: Adzuna developer key
- [ ] Submit Jooble API key request form
- [ ] Sign up: Careerjet partner account
- [ ] Read Unstop's Terms of Use in a browser; decide yes/no

---

## 2. Dedup strategy

The same post often reaches us through Adzuna, Jooble and Careerjet with slightly different titles and URLs.

1. **Exact:** canonicalise the URL (strip `utm_*`, tracking params, trailing slashes) and hash it.
2. **Fuzzy:** build a key from normalised `company + title + city` (lowercase, strip "Pvt Ltd", "Limited", punctuation, `(Remote)` etc.). Within the same company, `rapidfuzz.token_set_ratio ≥ 90` on the title counts as a duplicate.
3. **Merge, don't drop:** keep one record with a `sources: [adzuna, jooble]` list. The UI can show "seen on 2 sources".
4. **Report:** print `raw → after exact → after fuzzy` counts every run. That's a good number for the video.

Optional later: embedding similarity for near-duplicate descriptions. Skip unless fuzzy matching misses obvious cases.

---

## 3. LLM tagging design

**Model:** `claude-haiku-5-5` ($0.10 / $0.50 per 1M input/output tokens). Run Sonnet 5.5 on the same 50 eval posts as a comparison point.

**Output (structured outputs, enum-constrained JSON):**
```json
{
  "role": "Software Engineering",        // one of a fixed list
  "role_title_clean": "Backend Developer Intern",
  "city": ["Bengaluru"],                // canonical names, or "Remote"
  "work_mode": "onsite|hybrid|remote",
  "is_fresher_friendly": true,          // 0–1 yr exp, intern, trainee, grad
  "confidence": "high|medium|low"
}
```

**Role taxonomy (fixed, about 14 classes):** Software Engineering · Data & Analytics · AI/ML · Product · Design · Marketing · Sales & BD · Operations · Finance & Accounting · HR & Recruiting · Content & Media · Customer Support · Consulting & Strategy · Other.

**City normalisation:** canonical list of Indian cities with aliases (Bangalore→Bengaluru, Gurgaon→Gurugram, Bombay→Mumbai, "Delhi NCR", Noida…), plus `Remote` and support for multiple cities. A deterministic alias map runs after the LLM, so "Bangalore" and "Bengaluru" never split the filter.

**Why `is_fresher_friendly` matters:** aggregator searches for "fresher" still return "3–5 yrs" posts. The LLM flag filters those out, and it's the tag Beep's users care about most.

**Prompt approach:**
- A static system prompt (taxonomy, city list, rules, a few edge-case examples), cached so repeat calls are cheaper.
- Per post: title, company, location field, and the first ~1,500 characters of the description.
- Low effort setting; this is classification, not reasoning.
- Cache tags by `sha1(title+company+description)`, so a post is never re-tagged on later days.

---

## 4. Measuring accuracy (50 posts)

1. Stratified random sample of 50: spread across sources and roles, not just the first 50.
2. Export to a Google Sheet: `title | company | location raw | description link | LLM role | LLM city | LLM fresher | correct role? | correct city? | correct fresher? | notes`.
3. Hand-label each row yourself (this is the "thinking has to be yours" part).
4. Report **per-field accuracy** (role, city, fresher), not one blended number, plus the top 3 error types and what you changed because of them.
5. Bonus: run Sonnet 5.5 on the same 50 → "Haiku got X%, Sonnet got Y% at Z× the cost, so Haiku is the right call."

---

## 5. Cost per 1,000 posts (estimate, to be replaced with measured numbers)

Assumptions: ~900 input tokens per post (prompt ~500, mostly cached, plus post ~400) and ~80 output tokens.

| Model | Input | Output | **Per 1,000 posts** |
|---|---|---|---|
| Haiku 5.5 | 0.9M × $0.10 = $0.09 | 0.08M × $0.50 = $0.04 | **≈ $0.13 (~₹11)** |
| Haiku 5.5 via Batch API (50% off) | | | **≈ $0.07** |
| Sonnet 5.5 | 0.9M × $2 = $1.80 | 0.08M × $10 = $0.80 | **≈ $2.60** |

Infra cost is $0: GitHub Actions free minutes, plus static hosting on Vercel or GitHub Pages.

**The pipeline must log real `usage` tokens from every API response**, and the final number should come from those logs, not this table. Thinking tokens (if any) are billed as output, so measure.

---

## 6. Frontend (search page)

- Search box (title, company, description) plus filters for **role**, **city**, **work mode**, **fresher-only** toggle, and **posted within N days**.
- Each card shows title, company, city chips, role tag, source badges, posted date, and an "Apply" link to the original post (with Adzuna attribution where required).
- A footer/status strip with last refreshed time, total posts, new today and duplicates removed, read from `runs.json`.
- Mobile-first, since students browse on phones.

Tech: plain HTML/JS or a tiny Vite app plus MiniSearch. Keep it small so live edits are easy.

---

## 7. Things likely to break (keep notes for the video)

- Jooble/Careerjet keys arrive late → Adzuna + Unstop must hit 200 on their own.
- Lever sometimes returns HTML instead of JSON → check the Content-Type before parsing.
- Aggregator "fresher" results include senior posts → `is_fresher_friendly` flag.
- City strings like "Bengaluru, Karnataka, India" or "Multiple locations" → alias map plus LLM.
- Duplicate posts with different URLs across aggregators → fuzzy dedup.
- Expired posts → drop posts older than 30 days, or any not seen in the last 3 runs.
- LLM returns an off-list role → structured output enum makes this impossible. Mention it as a design choice.

---

## 8. Plan for the remaining days

| Day | Goal |
|---|---|
| **Tue 17** | Get API keys. Write fetchers for Adzuna, Unstop and ATS. Normalise. Dedup. |
| **Wed 18** | LLM tagging plus caching. Write jobs.json. Build the search page. Deploy. Set up the GitHub Actions cron. |
| **Thu 19** | Add Jooble/Careerjet if keys arrived. Hand-check 50. Accuracy and cost numbers. Fix the top errors. |
| **Fri 20** | Write the doc/sheet (data, tools, prompts). Record the Loom (≤5 min). Submit before 11:59 PM. |

---

## Open decisions for you
1. **Unstop:** include it after you read their ToS? It's the best fresher source if allowed.
2. **Language:** Python for the pipeline (recommended) plus a plain JS frontend?
3. **Hosting:** Vercel (nicer URL) or GitHub Pages (everything in one repo)?

## Sources
- [Adzuna developer overview](https://developer.adzuna.com/overview) · [Adzuna API terms](https://developer.adzuna.com/docs/terms_of_service)
- [Jooble API](https://jooble.org/api/about) · [Jooble REST API docs](https://jooblehelpcenter.freshdesk.com/en/support/solutions/articles/60001448238-rest-api-documentation)
- [Careerjet Python client](https://github.com/careerjet/careerjet-api-client-python)
- [Greenhouse Job Board API](https://developers.greenhouse.io/job-board.html) · [Lever API guide](https://100hires.com/lever-api.html)
- [Internshala robots.txt](https://internshala.com/robots.txt) · [Unstop robots.txt](https://unstop.com/robots.txt)
- Anthropic model pricing (Claude API reference, cached 2026-11-03)
