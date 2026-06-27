import test from "node:test";
import assert from "node:assert/strict";
import { categorize } from "./categorize.js";
import type { Category } from "../shared/contract.js";

const cases: Array<[string, Category | null]> = [
  ["github.com", "code_review"],
  ["api.github.com", "code_review"],
  ["gitlab.com", "code_review"],
  ["meet.google.com", "meetings"],
  ["company.zoom.us", "meetings"],
  ["app.slack.com", "comms"],
  ["mail.google.com", "comms"],
  ["claude.ai", "ai_assisted"],
  ["stackoverflow.com", "research"],
  ["rust.stackexchange.com", "research"],
  ["docs.stripe.com", "research"],
  ["en.wikipedia.org", "research"],
  ["news.ycombinator.com", null],
  ["youtube.com", null],
  ["GitHub.com", "code_review"], // case-insensitive
];

for (const [domain, want] of cases) {
  test(`categorize(${domain}) -> ${want}`, () => {
    assert.equal(categorize(domain), want);
  });
}

test("empty/whitespace -> null", () => {
  assert.equal(categorize(""), null);
  assert.equal(categorize("   "), null);
});
