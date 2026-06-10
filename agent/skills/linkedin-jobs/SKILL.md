---
name: linkedin-jobs
description: Search LinkedIn for job listings matching a job description. Returns structured results via Playwright browser automation.
---

# LinkedIn Job Search Skill

## Your Credentials
# email: ${LINKEDIN_EMAIL}
# password: ${LINKEDIN_PASSWORD}

## Browser Requirement
**You MUST use the Playwright MCP tools to interact with LinkedIn.** Do NOT use `xdg-open`, shell commands, `web_search_tool`, or any other method.

### Available Playwright Tools
- `browser_navigate` — open a URL
- `browser_snapshot` — read the current page content and structure
- `browser_click` — click an element by its ref from the snapshot
- `browser_type` — type text into a field
- `browser_scroll` — scroll the page

## Operational Protocols

### Stealth
- Wait 5–15 seconds (random) between page loads before reading content.
- Scroll gradually to simulate human browsing.

### Read-Only
- Never click Like, Follow, Connect, Apply, or Save.
- Never type into message fields, comment boxes, or post editors.
- Only type into the search bar and login fields.

---

## Job Search Process

### Step 1: Login
If not already authenticated:
- Navigate to `https://www.linkedin.com/login`
- Type `${LINKEDIN_EMAIL}` into the email field
- Type `${LINKEDIN_PASSWORD}` into the password field
- Click the Sign In button
- Wait for the feed to load

### Step 2: Navigate to Jobs
Navigate to `https://www.linkedin.com/jobs/`

### Step 3: Search
- Locate the search box on the jobs page
- Type the user's job description (the `Query` from the prompt) into the search field
- Press Enter or click the search button
- Wait for results to load

### Step 4: Find and Return Matches
Scan through the search results. Stop early once you have found up to 3 strong matches. If you scan 25 results without finding 3, return what you found.

Present the results in your preferred format. For each match, include:
  - **title**: The job title text
  - **company**: The company name
  - **location**: The location text (e.g. "Tel Aviv, Israel")
  - **matchExplanation**: A brief explanation of why this job is a strong match (e.g., role overlap, matching skills, industry fit)
  - **link**: The full URL to the job listing

## Session Cleanup
Once finished, close the browser tab.
