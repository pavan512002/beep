# Beep Fresher Jobs

A live, searchable list of fresher job and internship posts in India, refreshed every morning without anyone touching it.

**Live site: <https://fresher-jobs-india.vercel.app>** · [How it works (live run numbers)](https://fresher-jobs-india.vercel.app/stats.html) · [Daily runs](https://github.com/pavan512002/beep/actions/workflows/daily.yml)

Walkthrough video: _link to be added_

## What it does

1. **Collects** posts from sources that allow it: the Adzuna jobs API (India), optional Jooble, and public company career boards (Greenhouse, Lever, Ashby).
2. **Cleans and merges duplicates**: strips HTML and tracking parameters, merges the same link, then near-identical titles at the same company and city.
3. **Tags** each post with role, cities, job type, work mode, fresher fit, experience, pay and skills using an LLM, then checks the answer with rules (allowed values, skill list, experience and seniority checks).
4. **Publishes** a static site: search, filters, shareable URLs, and **Best fit**, which ranks posts against the skills a student enters.

## Where to look

| Deliverable | Where |
|---|---|
| Live tool | <https://fresher-jobs-india.vercel.app> |
| All posts as a spreadsheet | [`export/jobs.csv`](export/jobs.csv) (rewritten every run) |
| Tagging accuracy (50-post hand check) | [`eval/report.md`](eval/report.md) once scored; method below |
| Cost per 1,000 posts | [`docs/prompts.md` → Cost per 1,000 posts](docs/prompts.md#cost-per-1000-posts), plus measured numbers on the stats page |
| Prompts and AI tools used | [`docs/prompts.md`](docs/prompts.md), [`pipeline/src/main/resources/prompts/`](pipeline/src/main/resources/prompts/) |
| What was built, in order, and what broke | [`docs/build-log.md`](docs/build-log.md) |
| Claude tagging routine | [`tagging/ROUTINE.md`](tagging/ROUTINE.md) |

## How it fits together

```
GitHub Actions (daily 06:00 IST, manual, or when Claude tags are pushed)
  └─ pipeline/ (Spring Boot, Java 21, Gradle; a batch job, no server)
       fetch ─► normalize ─► dedup ─► merge with yesterday ─► expire ─► tag ─► publish
                                                                     │
                       Groq / Gemini free tiers tag brand-new posts ─┤
     Claude Code routine (daily) re-tags from state/pending-tags.json┘
  └─ tools/verify-output.mjs checks the output, then the job commits web/data, state, export
Vercel serves web/ as static files (no build). The browser loads data/jobs.json and searches it locally.
```

| Path | What it is |
|---|---|
| `pipeline/` | The daily batch job. `PipelineRunner` is the whole run in one place. |
| `web/` | The static site: `app.js` (browse/search), `match.js` (Best fit), `stats.js` (How it works). |
| `web/data/` | Published output: `jobs.json` (the catalog) and `stats.json` (latest run, history, source health, accuracy). |
| `state/` | Pipeline memory: tag cache, the Claude routine's work list, and its incoming tag files. |
| `eval/` | Hand-check tooling: `sample.mjs`, `score.mjs`. |
| `tools/verify-output.mjs` | Consistency and secret check run before every publish. |
| `tests/e2e/` | Browser smoke tests (Playwright). |
| `docs/` | Prompts and cost, build log, research. `docs/plan.md` is the original plan, kept for history. |

## Run it locally

Needs JDK 21 and Node 22.

```sh
# Tests and the runnable jar
cd pipeline && ./gradlew test bootJar && cd ..

# One full run from the repo root (rewrites web/data, state, export).
# With no keys set, sources are skipped and cached tags still apply; the run reports itself "degraded".
java -jar pipeline/build/libs/pipeline.jar

# Read-only checks, no keys needed
node tools/verify-output.mjs                 # output consistent, no credentials in public files
node tests/e2e/serve.mjs                     # site on http://127.0.0.1:4173
cd tests/e2e && npm ci && npx playwright test   # PW_CHANNEL=chrome uses your installed Chrome
```

### Keys (environment variables; GitHub Actions secrets in production)

| Variable | Needed for | Required? |
|---|---|---|
| `ADZUNA_APP_ID`, `ADZUNA_APP_KEY` | the main job source | yes, for fresh posts |
| `GROQ_API_KEY`, `GEMINI_API_KEY` | same-day tagging of new posts (free tiers) | optional; without them new posts wait for the Claude routine |
| `JOOBLE_KEY` | a second aggregator | optional |
| `ANTHROPIC_API_KEY` | paid Claude API tagging (off unless added to `beep.tagging.use`) | optional |

Settings (queries, providers, expiry windows) live in [`pipeline/src/main/resources/application.yml`](pipeline/src/main/resources/application.yml).

## Operating it

**Checking a run.** Open the stats page or `web/data/stats.json`:

- `lastRun.status` is `healthy`, or `degraded` when a source that matters failed, returned only some pages, or suddenly returned nothing.
- `lastRun.sources[]` gives each source's status (`ok`, `partial`, `empty`, `error`, `disabled`), pages attempted and succeeded, and failure *categories*. Error messages are never published, because request URLs can contain keys.
- `lastRun.lastSuccessfulCollectionAt` and `sourceHealth.<source>.lastOkAt` show when posts were last really collected.
- `lastRun.llm.pendingEligible` and `oldestPendingFirstSeenAt` show the Claude routine's backlog.

**During a source outage** nothing needs doing. A post only expires as "not seen for 3 days" on runs where its source worked fully, so an outage can't empty the site. Posts older than 30 days still expire. When the source comes back, posts that really closed drop out on that run.

**If a bad catalog was published**, revert the data commit and push. Vercel redeploys the previous catalog, and the next run starts from it.

```sh
git revert <sha of the "Daily refresh" commit> && git push
```

A run that fails validation (duplicate ids, missing links, mismatched CSV, anything that looks like a credential) exits before the commit step, so the live site keeps the last good catalog. Re-run it from the Actions tab ("Run workflow") once fixed.

**Claude routine problems.** Tag files that aren't valid JSON are moved to `state/claude-tags/rejected/` and the rest still import. The count shows as `claudeUnreadableFiles` in stats.

## Tagging accuracy: the hand check

```sh
node eval/sample.mjs --force          # 50 random posts from the live catalog (Claude + API-tagged mix)
# Open eval/sample-50.csv. For each post, read the original and fill every correct_* column with
# "ok" (the AI was right) or the right value, and skills_ok with Y or N.
node eval/score.mjs --reviewer "<your name or initials>"
```

`score.mjs` refuses to score until every row has a decision, so an unread sample can't be published as a result. It writes `eval/report.md` (per-field "N of 50", by model, every mistake) and the accuracy block on the stats page. The sample's population, seed and snapshot are recorded in `eval/sample-meta.json`.

## Tests

- `pipeline/src/test`: JUnit. Covers normalization, city parsing, India eligibility, dedup and id collisions, pay parsing, tag rules, provider fallback, Claude import and queue order, and multi-day runner scenarios (outage and recovery, re-ids, held-back posts, no secrets in stats).
- `tests/e2e`: Playwright. Covers browse, search (also with the search library blocked), keyboard Best fit, the mobile filter sheet, and the stats page in normal and degraded states.
- `.github/workflows/ci.yml` runs all of it, plus syntax checks and `verify-output`, on every pull request, with no secrets.
