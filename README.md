# Beep Fresher Jobs

A live, searchable list of fresher job and internship posts in India. It refreshes every day on its own.

- Collects posts from public sources that allow it (official job APIs and public company career boards)
- Removes duplicate posts that appear on more than one source
- Uses an LLM (Claude) to tag each post by role, city, job type, work mode and fresher fit
- Shows everything on a static search page

**Status:** in progress.

## Repo layout

| Path | What it is |
|---|---|
| `pipeline/` | Spring Boot (Java 21) batch job: fetch → dedup → tag → publish |
| `web/` | Static search site, deployed on Vercel |
| `.github/workflows/` | Daily scheduled run |
| `docs/` | Brief, research, plan, prompts and build log |
