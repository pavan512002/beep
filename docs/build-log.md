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
