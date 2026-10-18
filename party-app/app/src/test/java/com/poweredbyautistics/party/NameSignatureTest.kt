package com.poweredbyautistics.party

import org.junit.Assert.assertEquals
import org.junit.Test

class NameSignatureTest {
    // Values the ESP32 preview sketch printed for "aniket" (same algorithm, in C).
    @Test
    fun aniketMatchesFirmwarePreview() {
        assertEquals(NameSignature(hueA = 13379, hueB = 51523, mask = 0x1E7, speed = (2224 - 1000) / 12), signatureOf("aniket"))
    }

    @Test
    fun caseAndSpacesDoNotChangeThePattern() {
        assertEquals(signatureOf("aniket"), signatureOf("  Aniket "))
    }
}
