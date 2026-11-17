# Claude Code tagging routine

A scheduled Claude Code routine (cloud, runs on the owner's Claude Pro plan) follows these steps
every morning after the 06:00 IST data refresh. It tags job posts with an LLM: you.

The Java pipeline re-checks everything you write (allowed values, skill list, rule checks) before
it reaches the website, so follow the format exactly. Never edit any file other than the one you create.

## Steps

1. `git pull` to be on the latest `main`.
2. Read `state/pending-tags.json`. It has `promptVersion`, `count` and `posts` (each: `cacheKey`,
   `title`, `company`, `location`, `description`). If `count` is 0, stop: nothing to do, no commit.
3. Read the tagging rules and formats:
   - `pipeline/src/main/resources/prompts/system-<promptVersion>.txt`: **the instructions. Follow every rule.**
   - `pipeline/src/main/resources/prompts/tag-schema.json`: every field and its allowed values
   - `pipeline/src/main/resources/prompts/skills.json`: the only skill names allowed
4. Tag **every** post in `posts`, one at a time, reading its text carefully. Descriptions are often a
   500-character preview: if experience isn't visible and the title doesn't settle it, `fresherFit` is
   `"unclear"`.
5. Write `state/claude-tags/<UTC timestamp, e.g. 2026-11-19T01-05Z>.json`:

   ```json
   {
     "model": "<your model id, e.g. claude-sonnet-5-5>",
     "promptVersion": "<the promptVersion from pending-tags.json>",
     "results": [
       {
         "cacheKey": "<copied exactly from the post>",
         "inIndia": "yes", "role": "Software Engineering", "cities": ["Bengaluru"],
         "workMode": "unknown", "jobType": "internship", "fresherFit": "yes",
         "expMinYears": null, "expMaxYears": null, "stipendMinInr": 15000, "stipendMaxInr": 15000,
         "skills": ["Java", "SQL"], "impliedSkills": ["DSA", "OOP"], "confidence": "high"
       }
     ]
   }
   ```

   One result per post, every schema field present, `cacheKey` copied exactly.
6. Check the file: `node tagging/check-tags.mjs state/claude-tags/<file>.json`. Fix every error it
   prints and run it again until it says `OK`.
7. Commit only that file and push:
   `git add state/claude-tags/ && git commit -m "Claude tags: <n> posts" && git push`.
   If the push is rejected, `git pull --rebase` and push again.

Pushing to `state/claude-tags/` starts the GitHub Action, which imports the tags and republishes the site.
