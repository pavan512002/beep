# AI tools and prompts

## AI tools used

| Where | Tool / model | Why |
|---|---|---|
| Building the project | Claude Code (Claude Opus 5.5) | Pair-programmed the pipeline, site, prompts and tests; I reviewed and directed each step |
| Tagging posts (primary) | **Claude Sonnet 5.5 via a scheduled Claude Code routine** (Pro plan, no API key) | Daily at 06:30 IST: reads `state/pending-tags.json`, follows [`tagging/ROUTINE.md`](../tagging/ROUTINE.md) and prompt v3, validates with `tagging/check-tags.mjs`, pushes `state/claude-tags/*.json`; the GitHub Action re-checks and imports |
| Same-day fallback for brand-new posts | Groq free tier, `openai/gpt-oss-120b`, reasoning effort low | Free, fast, strict JSON-schema output. Limit: 200K tokens/day, 8K tokens/min |
| Fallback when Groq is out of quota | Google AI Studio free tier, `gemini-3.1-flash-lite` | Takes over automatically when Groq's daily limit is reached |
| Supported, switched off | Anthropic `claude-haiku-5-5` | Paid; one line in `application.yml` turns it on |
| Best fit matching | No LLM: rules in `web/match.js` | Runs in the browser, instant, free, and the student's profile never leaves the device |

Provider order, models and limits live in [`pipeline/src/main/resources/application.yml`](../pipeline/src/main/resources/application.yml).

## How a post is tagged

1. 8 posts go in one request: title, company, location text and description (Adzuna cuts descriptions at 500 characters).
2. The system prompt (below) explains every field. The answer must match [`tag-schema.json`](../pipeline/src/main/resources/prompts/tag-schema.json): fixed values for role, job type, work mode, fresher fit, India check and confidence.
3. Java then checks every value again, maps cities to canonical names (Bangalore → Bengaluru), keeps only skills on the fixed list ([`skills.json`](../pipeline/src/main/resources/prompts/skills.json), 96 names), and flags a post as low confidence when the model's city disagrees with a rule-based parse of the location.
4. Results are cached by prompt version + post text, so each post is tagged once. Changing the prompt version re-tags everything.

## Prompt versions

| Version | File | What changed and why |
|---|---|---|
| v1 | [`system-v1.txt`](../pipeline/src/main/resources/prompts/system-v1.txt) | Role (14 fixed roles), cities, work mode, job type, fresher fit, experience, monthly pay in INR, up to 5 free-text skills, confidence. 5 worked examples |
| v2 | [`system-v2.txt`](../pipeline/src/main/resources/prompts/system-v2.txt) | **India check** (`inIndia`): the site showed an Australian hospital job listed under "Chandigarh"; posts marked "no" are not published. **Senior titles**: "Consultant"/"Specialist" in medicine are senior. **Skills from a fixed list** of 96 names, split into skills the post names and skills the role implies (an SDE role implies DSA and OOP even when unstated). v1's free-text skills gave 395 different strings and 42% of posts had none, which made matching students to posts unreliable. |
| v3 | [`system-v3.txt`](../pipeline/src/main/resources/prompts/system-v3.txt) | **Previews**: descriptions are often the first ~500 characters, so a missing requirement is not "no requirement"; `fresherFit` is "unclear" unless the post signals early-career. Used by the Claude routine |

### Things that broke while getting v2 to run

- Putting the 96 skills in the JSON schema as an `enum` (twice) made Gemini refuse the schema ("too much branching") and made Groq occasionally fail its own strict check. Fix: the list moved into the prompt text, and Java drops any name not on it.
- A changed prompt version means every post needs new tags, more than one day of free quota. Fix: until a post is re-tagged it keeps its previous tags instead of being published untagged.

## System prompt (v3, current; v2 plus the preview rule)

See [`system-v2.txt`](../pipeline/src/main/resources/prompts/system-v2.txt) for the exact text. Summary of the rules:

- **IN INDIA**: yes / no / unclear. Foreign city, foreign-currency salary or "no visa sponsorship" for another country → no, even if the location field names an Indian city.
- **ROLE**: exactly one of 14, chosen by the day-to-day work, not the company's industry. Each role has a one-line definition.
- **CITIES**: canonical names (Bengaluru, Gurugram…), all cities listed, `Remote` for remote jobs, `India (unspecified)` when no city is stated. Never guess from the company's headquarters.
- **WORK MODE / JOB TYPE**: only if stated or clearly implied; otherwise unknown.
- **FRESHER FIT**: yes (internships, "fresher", 0–1 years, graduate trainee, 2025/2026 batch); no (2+ years or a senior title); unclear otherwise.
- **PAY**: monthly INR only if stated; yearly ÷ 12 ("3-4.5 LPA" → 25,000–37,500); unpaid → 0.
- **SKILLS**: up to 6 named in the post + up to 4 implied by the role, all from the fixed list, with synonym rules (MySQL → SQL, "problem solving" → DSA).
- **CONFIDENCE**: high / medium / low.
- 6 worked examples, including the Australian job and a vague one-line post.

## User message format

```
Tag each of these 8 job posts. Return exactly one result per post in "results", with "i" set to the post's number.

Post 1
Title: Data Analyst Intern
Company: Acme
Location: Bangalore, Karnataka
Description: 6-month internship, stipend 12k/month, Excel and SQL required
...
```

Each result carries `"i"`, so answers can't be matched to the wrong post even if the model reorders them.

## Cost per 1,000 posts

- **Claude routine (primary):** included in the Claude Pro subscription, so there's no per-post bill. One run tags 150 posts in about 3 minutes; the whole site (~620 posts) took 5 runs. For comparison, the same work through the paid API at Sonnet 5.5 list prices ($2 input / $10 output per 1M tokens) would be roughly **$2 per 1,000 posts** when the ~2.3K-token instructions are read once per batch of 150 (~230 input + ~150 output tokens per post), and up to ~$7 if the instructions were resent with every post. These are estimates, not measured.
- **Groq / Gemini fallback (measured):** $0 on the free tiers; the same tokens at their paid prices are $0.14 (Groq gpt-oss-120b) and $0.32 (Gemini Flash-Lite) per 1,000 posts. Shown live on the site's How it works page.
