package com.cadence.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CategoryTest {

    @Test
    void constantNamesAreTheWireValues() {
        assertEquals("deep_work", Category.deep_work.name());
        assertEquals("ai_assisted", Category.ai_assisted.name());
        assertEquals(8, Category.values().length);
        assertEquals(8, Category.wireValues().size());
    }

    @Test
    void onlyOtherIsNonSpecific() {
        assertFalse(Category.other.isSpecific());
        for (Category c : Category.values()) {
            if (c != Category.other) {
                assertTrue(c.isSpecific(), c + " should be specific");
            }
        }
    }

    @Test
    void fromWireParsesKnownAndRejectsUnknown() {
        assertEquals(Category.code_review, Category.fromWire("code_review").orElseThrow());
        assertTrue(Category.fromWire("nonsense").isEmpty());
        assertTrue(Category.fromWire(null).isEmpty());
    }

    @Test
    void needsLlmForNullOrOtherOnly() {
        assertTrue(Category.needsLlm(null));            // never classified
        assertTrue(Category.needsLlm("other"));         // rule classifier gave up
        assertTrue(Category.needsLlm("bogus"));         // unknown → re-do
        assertFalse(Category.needsLlm("deep_work"));    // confident
        assertFalse(Category.needsLlm("idle"));
    }
}
