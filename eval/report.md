# Tagging accuracy: hand check of 50 posts

| | |
|---|---|
| Population | all (1142 tagged posts) |
| Snapshot | 2026-11-20T07:41:05.198199138Z |
| Sample | 50 posts, seed 20261118 |
| Prompt version(s) | v3 |
| Reviewed by | Pavan |
| Scored | 2026-11-20 |

Each post's original text was read and every tag marked right ("ok") or corrected by hand.

| Field | Right | Accuracy |
|---|---|---|
| role | 48 of 50 | **96%** |
| city | 49 of 50 | **98%** |
| jobType | 50 of 50 | **100%** |
| fresherFit | 48 of 50 | **96%** |
| skills | 49 of 50 | **98%** |
| all fields right | 46 of 50 | **92%** |

## By model

| Model | Posts | Role | City | Fresher fit |
|---|---|---|---|---|
| claude-code:claude-sonnet-5-5 | 37 | 37/37 | 37/37 | 36/37 |
| groq:openai/gpt-oss-120b | 13 | 11/13 | 12/13 | 12/13 |

## Mistakes

### role

| # | Post | AI said | Correct | Notes |
|---|---|---|---|---|
| 2 | Security Ambassador | Operations | Other |  |
| 38 | Food & Beverage Associate | Operations | Other |  |

### city

| # | Post | AI said | Correct | Notes |
|---|---|---|---|---|
| 38 | Food & Beverage Associate | India (unspecified) | Delhi |  |

### fresherFit

| # | Post | AI said | Correct | Notes |
|---|---|---|---|---|
| 12 | Securities & Derivatives Analyst 2 - C10 - PUNE | unclear | yes |  |
| 22 | SDE- 1 Backend | unclear | no |  |

### skills

| # | Post | AI skills | Notes |
|---|---|---|---|
| 38 | Food & Beverage Associate | (Operations); (Customer Service); (Communication); (Sales) |  |
