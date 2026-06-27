// Domain → category classification for the Chrome collector (P1-C.5).
//
// The browser only ever sees a domain (P1-C.2), so classification is by host.
// We assign a category only for domains we're confident about and return null
// otherwise — an unknown domain stays unclassified so the daemon's classifier
// (P1-A.7, "browsers → research unless matched") can apply its own default
// rather than us guessing. Pure and data-driven so P1-C.7 can table-test it.

import type { Category } from "../shared/contract.js";

interface DomainRule {
  category: Category;
  /** Exact hostnames. */
  hosts: string[];
  /** Suffixes matched against the hostname (covers subdomains), e.g. ".zoom.us". */
  suffixes: string[];
}

// First match wins; order from most-specific intent to least.
const RULES: readonly DomainRule[] = [
  {
    category: "code_review",
    hosts: ["github.com", "gitlab.com", "bitbucket.org", "dev.azure.com"],
    suffixes: [".github.com", ".gitlab.com", ".githubenterprise.com"],
  },
  {
    category: "meetings",
    hosts: [
      "meet.google.com",
      "zoom.us",
      "teams.microsoft.com",
      "teams.live.com",
      "webex.com",
      "whereby.com",
    ],
    suffixes: [".zoom.us", ".webex.com"],
  },
  {
    category: "comms",
    hosts: [
      "slack.com",
      "discord.com",
      "mail.google.com",
      "outlook.office.com",
      "outlook.live.com",
      "web.whatsapp.com",
      "web.telegram.org",
    ],
    suffixes: [".slack.com"],
  },
  {
    category: "ai_assisted",
    hosts: [
      "claude.ai",
      "chatgpt.com",
      "chat.openai.com",
      "gemini.google.com",
      "perplexity.ai",
      "copilot.microsoft.com",
    ],
    suffixes: [],
  },
  {
    category: "research",
    hosts: [
      "stackoverflow.com",
      "developer.mozilla.org",
      "devdocs.io",
      "pkg.go.dev",
      "docs.python.org",
      "wikipedia.org",
    ],
    suffixes: [".stackexchange.com", ".readthedocs.io", ".wikipedia.org"],
  },
];

/**
 * Classifies a hostname into a Category, or null if unknown. Matching is
 * case-insensitive. As a final heuristic, any `docs.*` host is treated as
 * research (documentation portals), per the P1-C.5 brief.
 */
export function categorize(domain: string): Category | null {
  const host = domain.trim().toLowerCase();
  if (host.length === 0) return null;

  for (const rule of RULES) {
    if (rule.hosts.includes(host)) return rule.category;
    if (rule.suffixes.some((suffix) => host.endsWith(suffix))) return rule.category;
  }

  // Generic documentation portals: docs.stripe.com, docs.aws.amazon.com, …
  if (host === "docs" || host.startsWith("docs.")) return "research";

  return null;
}
