import { MemberDrilldown } from "@/components/member/member-drilldown";

// P2-E.6 — per-member drilldown, bounded by the org privacy level.
export default function MemberPage({ params }: { params: { id: string } }) {
  return <MemberDrilldown memberId={params.id} />;
}
