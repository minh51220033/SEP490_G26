package com.cvdcms.emr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmrHashingTest {
    @Test
    void sha256IsDeterministicAndUsesCanonicalLength() {
        String first = EmrHashing.sha256("snapshot-content");
        String second = EmrHashing.sha256("snapshot-content");

        assertEquals(first, second);
        assertEquals(64, first.length());
        assertNotEquals(first, EmrHashing.sha256("snapshot-content-changed"));
    }
}
