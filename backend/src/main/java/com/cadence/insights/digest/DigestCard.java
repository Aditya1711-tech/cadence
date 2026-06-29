package com.cadence.insights.digest;

/**
 * Renders the shareable "wrapped" card as a server-side SVG string (P3-A.7) — the
 * viral hook. Dependency-free (no headless browser, no image library): the SVG is
 * stored on the digest row and served by {@code GET /insights/weekly}. PNG
 * rasterization for platforms that don't preview SVG is a documented later add.
 *
 * <p>All numbers come straight from {@link DigestHeadline} (i.e. from SQL); the
 * card computes nothing. Text is XML-escaped.
 */
final class DigestCard {
    private DigestCard() {}

    private static final int W = 1200;
    private static final int H = 630;

    static String render(DigestHeadline h) {
        String title = esc(h.title());
        String week = esc(h.isoWeek());
        String deep = "%.1f".formatted(h.deepWorkH());
        String meet = "%.1f".formatted(h.meetingH());
        String cost = "$" + h.tokenCostUsd().toPlainString();
        String commits = Long.toString(h.commits());
        String focus = Integer.toString(h.focusScore());

        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" role="img">
                  <defs>
                    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0" stop-color="#0f172a"/>
                      <stop offset="1" stop-color="#1e293b"/>
                    </linearGradient>
                  </defs>
                  <rect width="%d" height="%d" fill="url(#bg)"/>
                  <text x="64" y="96" fill="#94a3b8" font-family="system-ui,sans-serif" font-size="34" font-weight="600">CADENCE · %s</text>
                  <text x="64" y="168" fill="#f8fafc" font-family="system-ui,sans-serif" font-size="64" font-weight="800">%s</text>

                  <g font-family="system-ui,sans-serif" text-anchor="middle">
                    <text x="230" y="350" fill="#38bdf8" font-size="92" font-weight="800">%s</text>
                    <text x="230" y="410" fill="#94a3b8" font-size="30">deep-work hrs</text>

                    <text x="510" y="350" fill="#34d399" font-size="92" font-weight="800">%s</text>
                    <text x="510" y="410" fill="#94a3b8" font-size="30">commits</text>

                    <text x="790" y="350" fill="#fbbf24" font-size="92" font-weight="800">%s</text>
                    <text x="790" y="410" fill="#94a3b8" font-size="30">AI tokens</text>

                    <text x="1060" y="350" fill="#f472b6" font-size="92" font-weight="800">%s</text>
                    <text x="1060" y="410" fill="#94a3b8" font-size="30">focus score</text>
                  </g>

                  <text x="64" y="566" fill="#cbd5e1" font-family="system-ui,sans-serif" font-size="32">%sh in meetings · peak %s</text>
                </svg>
                """.formatted(
                W, H, W, H, W, H,
                week, title,
                deep, commits, cost, focus,
                meet, esc(h.peakLabel()));
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
