---
name: hiremetech-jobs
description: Search HireMe Tech (hiremetech.com) for job listings. Default platform — public JSON API, no login, no browser.
---

# Adapter: HireMe

`source` = `hiremetech` · public JSON API, no login, no browser.

## 1. Access

None needed. `https://hiremetech.com/api/jobs/*` is public. Never open the site in
a browser for this platform.

## 2. Discovery

Search: `GET https://hiremetech.com/api/jobs/search`

- Params: `israeli`, `title`, `company`, `location`, `country`, `state`, `remote`,
  `job_level`, `employment_type`, `department`, `tech`, `ai_category`,
  `posted_days`, `sort_by`, `sort_order`, `limit`, `page`
- Response: `{ jobs: [...], page, size, pagination: { page, limit, has_more,
  total_count, total_pages }, filters_applied, performance }`
- Detail: `GET https://hiremetech.com/api/jobs/<id>` → `{ job: {...} }`
- Valid filter values: `GET https://hiremetech.com/api/jobs/filter-options`
  (`remote`, `job_level`, `employment_type`, `ai_category`, `tech`, `locations`, …)

Building the query from the criteria:

- Role keywords → `title`; location → `country` / `location` / `state` / `israeli`;
  work mode → `remote`; seniority → `job_level`; stack → `tech`; AI focus → `ai_category`.
- Always `sort_by=posted_date`, `sort_order=desc`; `limit=20`; page with `pagination.has_more`.
- Map criteria words to real param values with `filter-options` (values are
  free-text and inconsistent).
- A hard criterion that has no param is applied in the core evaluation step, not dropped.

Useful fields per result: `id`, `job_url`, `title`, `company_name`,
`location.basic.city` / `.country`, `location.work_model.display_tag`,
`job_level`, `posted_date`, `experience`, `tech_stack`, `description`, `requirements`.

## 3. Identity

- ID = the numeric job id (`<id>` in `/api/jobs/<id>`, or the trailing segment of
  `https://hiremetech.com/job/<id>`).
- Normalized URL = `https://hiremetech.com/job/<id>`. Locale paths such as
  `/he-il/job/<id>?f=1` are the same posting — dedupe by **id**, and store the
  canonical URL.
- Never invent an id; if a posting has none (rare, e.g. a redirect to another
  board), use the normalized URL as the id and keep the real apply URL in `--url`.

## 4. Field mapping

| Flag | Source |
| --- | --- |
| `--title` | `title` |
| `--company` | `company_name`, else `company.name`, else `Not disclosed` when the employer is masked (`employer_masked` / empty) |
| `--location` | `location.basic.city`; when the city is empty use `location.basic.country` |
| `--work-mode` | `location.work_model.display_tag` |
| `--url` | `https://hiremetech.com/job/<id>` |
| `--id` | the numeric id |

## 5. Failure modes

- Non-200, timeout, connection error, empty body, malformed JSON, unexpected
  shape → STOP and report. No retries and no fallback source.
- Rate limiting (HTTP 429) → STOP and report; do not hammer the API.
- `requires_login: true` on a posting is normal board metadata, not an access
  problem — the API is still public.

## 6. Apply route (verified 2026-11)

The detail payload tells you whether the application can actually be submitted,
so screen on it before picking a target:

- `is_linkedin_apply: true` / `apply_source: "linkedin"` → the Apply button opens
  the LinkedIn posting, which needs a signed-in account → **authenticated, do not
  attempt**.
- `ats_source` (`comeet`, `greenhouse`, `ashby`, `workday`, …) or
  `is_auto_apply_supported: true` → a real company form is reachable and can be
  filled without login (Comeet frames the form in an iframe; the fields are
  `#inputFirstName` / `#inputLastName` / `#inputEmail` / `#inputTel` / `#inputLink`
  / `#cv`).
- Everything else (both fields null) → the Apply button is gated behind the
  HireMe browser extension, or opens a company form that may demand data the CV
  does not carry (Israeli national ID `תעודת זהות`, reCAPTCHA) → treat as
  unsubmittable and record `!`.

**Verified 2026-11 (comeet jobs):** the HireMe `הגש מועמדות` / Apply button on a
comeet posting opens a HireMe sign-in modal ("התחבר כדי להגיש"), so the board
route is authenticated. The same posting is reachable unauthenticated on the
employer's own Comeet page: `<company careers page>/careers/co/<city>/<COMEET id>/<slug>/all`
(find it via the company's careers page, whose job card link carries that path).
Fields there are First name / Last name / Email / Phone / Resume / Cover Letter /
Portfolio / Personal note; success renders `Thank you <first name>! Your
application has been submitted.` A missing port/company careers page is not a
fallback for discovery — only for the apply step of an already-chosen posting.
