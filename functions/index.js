"use strict";

/**
 * DRAFT — pending sign-off. See docs/PRD-crashlytics-autotriage.md.
 *
 * On a new FATAL Crashlytics issue, create (exactly once) a GitHub issue labeled
 * `crashlytics-auto`. A GitHub Action (.github/workflows/crashlytics-autotriage.yml)
 * then has Claude triage it and, when confident, open a draft fix PR.
 *
 * Runtime: Node 22, firebase-functions v6 (2nd gen). Uses the global `fetch` (Node 18+),
 * so there are no extra HTTP dependencies.
 */

const {onNewFatalIssuePublished} = require("firebase-functions/v2/alerts/crashlytics");
const {defineSecret} = require("firebase-functions/params");
const logger = require("firebase-functions/logger");

// Fine-grained PAT (Issues: read & write on stozo04/OpenLoop) or a GitHub App token.
const GITHUB_TOKEN = defineSecret("GITHUB_TOKEN");

const REPO = "stozo04/OpenLoop";
const FIREBASE_PROJECT = "openloop-8c266";
const APP_ID = "android:io.github.stozo04.openloop";
const LABEL = "crashlytics-auto";

/** Standard GitHub REST headers. */
function ghHeaders(token) {
  return {
    "Authorization": `Bearer ${token}`,
    "Accept": "application/vnd.github+json",
    "X-GitHub-Api-Version": "2022-11-28",
    "User-Agent": "openloop-crashlytics-autotriage",
  };
}

/** Has an issue already been filed for this Crashlytics issue ID? (idempotency) */
async function issueAlreadyFiled(token, crashId) {
  const q = `repo:${REPO} label:${LABEL} "Crashlytics-Issue-ID: ${crashId}"`;
  const url = `https://api.github.com/search/issues?q=${encodeURIComponent(q)}`;
  const res = await fetch(url, {headers: ghHeaders(token)});
  if (!res.ok) {
    logger.warn(`Dedupe search failed (${res.status}); proceeding to create.`);
    return false;
  }
  const body = await res.json();
  return (body.total_count || 0) > 0;
}

exports.crashlyticsToGithub = onNewFatalIssuePublished(
  {secrets: [GITHUB_TOKEN], region: "us-central1"},
  async (event) => {
    const issue = (event.data && event.data.payload && event.data.payload.issue) || {};
    const crashId = issue.id || "unknown";
    const title = issue.title || "Unknown crash";
    const subtitle = issue.subtitle || "";
    const appVersion = issue.appVersion || "unknown";
    const token = GITHUB_TOKEN.value();

    if (await issueAlreadyFiled(token, crashId)) {
      logger.info(`Issue for Crashlytics ${crashId} already exists; skipping.`);
      return;
    }

    const consoleUrl =
      `https://console.firebase.google.com/project/${FIREBASE_PROJECT}` +
      `/crashlytics/app/${APP_ID}/issues/${crashId}`;

    const body = [
      "**Automated triage** — a new fatal crash was reported by Firebase Crashlytics.",
      "",
      `- **Crashlytics-Issue-ID:** ${crashId}`,
      `- **Title:** ${title}`,
      `- **Subtitle:** ${subtitle}`,
      `- **First seen app version:** ${appVersion}`,
      `- **Console:** ${consoleUrl}`,
      "",
      "---",
      "",
      `@claude please triage this crash. Use the Firebase MCP Crashlytics tools to fetch the`,
      `stacktrace and sample events for issue \`${crashId}\`, consult \`docs/lessons_learned/\`,`,
      "and post a root-cause comment. If you can point to a specific cause in the code, open a",
      "**draft** PR with a minimal fix per `docs/DEFINITION_OF_DONE.md` (note CI could not run",
      "the emulator). Otherwise label `needs-human` and explain. Never auto-merge.",
    ].join("\n");

    const res = await fetch(`https://api.github.com/repos/${REPO}/issues`, {
      method: "POST",
      headers: {...ghHeaders(token), "Content-Type": "application/json"},
      body: JSON.stringify({title: `[Crashlytics] ${title}`, body, labels: [LABEL, "bug"]}),
    });

    if (!res.ok) {
      const text = await res.text();
      logger.error(`GitHub issue creation failed: ${res.status} ${text}`);
      throw new Error(`GitHub API ${res.status}`);
    }

    const created = await res.json();
    logger.info(`Created GitHub issue #${created.number} for Crashlytics ${crashId}.`);
  },
);
