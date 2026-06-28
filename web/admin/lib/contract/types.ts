// TypeScript mirrors of the backend's frozen response shapes, in the EXACT
// snake_case the backend emits (Jackson global snake_case strategy). The BFF
// passes these through unchanged, so anything coupled to the wire shape lives
// here — a future contract change stays contained to this file.
//
//   auth shapes  = com.cadence.auth.AuthDtos.*
//   query shapes = com.cadence.query.Summaries.*

/** Frozen v1 category enum (00-SYSTEM-KNOWLEDGE §5). */
export type Category =
  | "deep_work"
  | "meetings"
  | "comms"
  | "research"
  | "code_review"
  | "ai_assisted"
  | "idle"
  | "other";

export const CATEGORIES: Category[] = [
  "deep_work",
  "meetings",
  "comms",
  "research",
  "code_review",
  "ai_assisted",
  "idle",
  "other",
];

/** Org privacy level (§8). Read-only in the UI until P2-A ships a set endpoint. */
export type PrivacyLevel = "full" | "categories_only" | "aggregate_only";

// ---- auth (AuthDtos.*) ----

export interface MemberView {
  id: string;
  email: string;
  display_name: string | null;
  role: string;
  status: string;
}

export interface OrgView {
  id: string;
  name: string;
  slug: string;
  privacy_level: PrivacyLevel;
}

export interface AuthResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in_seconds: number;
  member: MemberView;
  org: OrgView;
}

export interface TokenPair {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in_seconds: number;
}

export interface InvitePreview {
  org_name: string;
  email: string | null;
}

export interface CreateInviteResponse {
  token: string;
  url: string;
  expires_at: string;
}

export interface DeviceCodeResponse {
  code: string;
  expires_at: string;
}

// ---- query (Summaries.*) ----

export interface CategoryBucket {
  category: string;
  total_ms: number;
  event_count: number;
}

export interface DayBucket {
  date: string; // ISO local date (yyyy-mm-dd)
  by_category: CategoryBucket[];
}

export interface ModelBucket {
  model: string;
  cost_usd: number;
  tokens_in: number;
  tokens_out: number;
}

export interface TokenSummary {
  total_cost_usd: number;
  by_model: ModelBucket[];
}

export interface MemberSummary {
  member_id: string;
  email: string;
  display_name: string | null;
  role: string;
  status: string;
  teams: string[];
}

export interface MembersResponse {
  items: MemberSummary[];
  next_cursor: string | null;
}

export interface MemberRollup {
  member_id: string;
  display_name: string | null;
  by_category: CategoryBucket[];
  tokens: TokenSummary;
}

export interface OrgSummary {
  from: string;
  to: string;
  team: string | null;
  privacy_level: PrivacyLevel;
  org_totals_by_category: CategoryBucket[];
  org_by_day: DayBucket[];
  by_member: MemberRollup[];
}

/** Time ranges the backend's RangeParser accepts (§6 summary endpoints). */
export type Range = "today" | "24h" | "7d" | "30d" | "90d";
export const RANGES: { value: Range; label: string }[] = [
  { value: "today", label: "Today" },
  { value: "24h", label: "24 hours" },
  { value: "7d", label: "7 days" },
  { value: "30d", label: "30 days" },
  { value: "90d", label: "90 days" },
];
