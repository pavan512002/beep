# Build Log

What broke or surprised us while building, why, and what we did about it. This feeds the "what broke and how I fixed it" part of the video.

| Date | Finding | Why it matters | Decision / fix |
|---|---|---|---|
| 17 Nov | Company career boards (Greenhouse/Lever) had **0 real fresher roles** in ~175 live posts from Groww, InMobi, Druva, CRED and Meesho | They can't be the main source | Aggregator APIs supply the volume; career boards are a bonus source only |
| 17 Nov | Adzuna India works. Matches over the last 30 days: `fresher` 1,421, `intern` 2,128, `entry level` 652, `graduate trainee` 52. **197 unique posts from just page 1 of 5 queries** | 200+ posts is easily reachable from one source | Adzuna is the primary source; fetch 2–5 pages per query |
| 17 Nov | `intern` and `internship` return the **same** 2,128 results | Wasted API calls | Keep only one of the two queries |
| 17 Nov | Adzuna descriptions are **cut at 500 characters** | Less text for the LLM to tag fresher-fit, stipend and skills | Tag from title + 500-char snippet; measure the effect on accuracy in the 50-post check |
| 17 Nov | **65 of 197** posts have the location only as `"India"` | City tagging can't come from the location field alone | The LLM reads the description for a city; otherwise tag as `India (unspecified)`. Count these separately in the eval |
| 17 Nov | The same post appears twice with different dates (e.g. "Entry Level Associate", Ascendion, Vadodara, 15 Nov and 7 Nov) | Duplicates exist even within one source | The fuzzy dedup key ignores date; keep the earliest date |
| 17 Nov | Locations come as `"Bangalore, Karnataka"`, `"Gurgaon, Haryana"` | Old city names need mapping | City alias map: Bangalore→Bengaluru, Gurgaon→Gurugram |
| 17 Nov | User asked to switch the build from Maven to Gradle; Spring Initializr returned HTTP 500 and no longer offers Boot 3.5 | Couldn't download a starter with the Gradle wrapper | Generated the wrapper with a locally cached Gradle 9.8.2, pinned to Gradle 8.14.7 (supports Spring Boot 3.5) |
| 17 Nov | First full run: **653 raw → 596 after exact-URL dedup → 530 after fuzzy dedup** in 25 s | Dedup removed 19%; 66 removed only by fuzzy matching | Both passes are needed |
| 17 Nov | Company career boards again gave **1** entry-level India post (Groww) out of 174 | Confirms the research | Kept as a bonus source |
| 17 Nov | Many "fresher" search results are senior roles ("Senior Trading Specialist", "Collections Manager") | A keyword search alone isn't a fresher filter | The LLM `fresherFit` tag; the site shows fresher-friendly posts by default |
| 17 Nov | Adzuna `created` timestamps are a few hours **in the future** | "Posted in 4 hours" would look broken | Cap future timestamps at the current time during normalisation |
| 17 Nov | 171 of 529 posts have no city in the location field | The rule-based city parse can't cover them | The LLM reads the description; otherwise "India (unspecified)" |
| 17 Nov | The stored `ANTHROPIC_API_KEY` was rejected (401): it isn't an Anthropic key (`sk-ant-…`) | Tagging calls failed | Failure handling worked: no crash, $0 charged, posts published untagged and retried next run. Needs a real key |
| 17 Nov | `.gitignore` was written for Maven, so Gradle's `build/` and `.gradle/` were staged; `gradlew` would get CRLF line endings on Windows | Would break the Linux CI runner | Fixed `.gitignore`; added `.gitattributes` (LF everywhere) |
