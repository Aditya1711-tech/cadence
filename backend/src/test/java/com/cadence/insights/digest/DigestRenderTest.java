package com.cadence.insights.digest;

import com.cadence.insights.InsightFacts.PeakBlock;
import com.cadence.insights.digest.DigestNarrator.Narration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the digest render path (no DB, no LLM): card SVG + template narration. */
class DigestRenderTest {

    private static DigestHeadline headline(Integer frag, PeakBlock peak, boolean lowConf) {
        return new DigestHeadline("Octo Dev", "2026-W26", 18.4, 6.2,
                new BigDecimal("4.73"), 27, frag, peak, lowConf);
    }

    @Test
    void focusScoreAndPeakLabel() {
        DigestHeadline noPeak = headline(null, null, false);
        assertEquals(100, noPeak.focusScore());                 // null fragmentation ⇒ fully focused
        assertEquals("no clear peak block", noPeak.peakLabel());

        DigestHeadline h = headline(38, new PeakBlock("Tue", 10, "deep_work", 5_400_000L), false);
        assertEquals(62, h.focusScore());                       // 100 − 38
        assertEquals("Tue around 10:00", h.peakLabel());
    }

    @Test
    void cardIsWellFormedSvgWithHeroNumbers() {
        String svg = DigestCard.render(headline(38, new PeakBlock("Tue", 10, "deep_work", 5_400_000L), false));
        assertTrue(svg.startsWith("<svg"), svg);
        assertTrue(svg.trim().endsWith("</svg>"), svg);
        for (String token : new String[]{"2026-W26", "Octo Dev", "18.4", "27", "$4.73", "62"}) {
            assertTrue(svg.contains(token), "card missing " + token);
        }
    }

    @Test
    void cardEscapesXml() {
        DigestHeadline h = new DigestHeadline("A & <B>", "2026-W26", 1.0, 0.0,
                BigDecimal.ZERO, 0, 0, null, false);
        String svg = DigestCard.render(h);
        assertTrue(svg.contains("A &amp; &lt;B&gt;"), svg);
        assertFalse(svg.contains("A & <B>"), "raw unescaped title must not appear");
    }

    @Test
    void templateNarrativeIsGroundedAndHasThreeSpotted() {
        Narration n = DigestNarrator.template(
                headline(38, new PeakBlock("Tue", 10, "deep_work", 5_400_000L), false));
        // every number traces to the headline (which came from SQL) — nothing invented
        assertTrue(n.narrative().contains("18.4"), n.narrative());
        assertTrue(n.narrative().contains("$4.73"), n.narrative());
        assertTrue(n.narrative().contains("27 commit"), n.narrative());
        assertTrue(n.narrative().contains("62/100"), n.narrative());
        assertFalse(n.narrative().contains("Early days"));      // lowConfidence=false ⇒ no early note

        assertEquals(3, n.spotted().size());
        assertEquals("Peak focus", n.spotted().get(0).title());
        assertEquals("AI efficiency", n.spotted().get(1).title());
        assertEquals("Meeting load", n.spotted().get(2).title());
    }

    @Test
    void lowConfidenceAppendsEarlyNote() {
        Narration n = DigestNarrator.template(headline(null, null, true));
        assertTrue(n.narrative().contains("Early days"), n.narrative());
    }
}
