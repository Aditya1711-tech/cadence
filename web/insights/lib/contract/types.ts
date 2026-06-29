// TypeScript mirrors of the backend response shapes, in the EXACT snake_case the
// backend emits (Jackson global snake_case). The BFF passes these through
// unchanged.
//   auth shapes    = com.cadence.auth.AuthDtos.*
//   nlquery shapes = com.cadence.insights.nlquery.NlQueryDtos.*

export type PrivacyLevel = "full" | "categories_only" | "aggregate_only";

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

// ---- NL query (NlQueryDtos.*) ----

export interface NlQueryResponse {
  question: string;
  sql: string;
  columns: string[];
  /** Each row is a list of cells aligned to `columns`. */
  rows: Array<Array<string | number | boolean | null>>;
  row_count: number;
  truncated: boolean;
  caption: string;
}
