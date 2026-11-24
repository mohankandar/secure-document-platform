package com.securedoc.platform.infrastructure.detection;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0062: CVV and PIN, with fake values, and near-misses that must not match. */
class LockedItemDetectorTest {

    @Test
    void cardSecurityCodesNextToTheirLabel() {
        assertEquals("CVV=739", found("CVV: 739"));
        assertEquals("CVV=7394", found("Amex CVV2 number 7394"));
        assertEquals("CVV=739", found("CVC2 #739 exp 11/29"));
        assertEquals("CVV=739", found("Card security code: 739"));
        assertEquals("CVV=739", found("Card verification value is 739"));
        assertEquals("CVV=739", found("Visa card ending 1111, security code 739"));
        assertEquals("CVV=739", found("Card number  Exp  CVV\n4111 1111 1111 1111  11/29  739"));
        assertEquals("CVV=739", found("CVV\n739"));
    }

    @Test
    void pinsNextToTheirLabel() {
        assertEquals("PIN=8264", found("PIN: 8264"));
        assertEquals("PIN=826415", found("ATM PIN number is 826415"));
        assertEquals("PIN=8264", found("Personal identification number 8264"));
        assertEquals("PIN=8264", found("P.I.N. 8264"));
        assertEquals("PIN=8264", found("PIN\n8264"));
    }

    @Test
    void nearMissesAreLeftAlone() {
        for (String text : List.of("Alarm security code 4821", "CVV", "CVV on file", "Suite 739", "CVV: 73945",
                "Enter the CVV from the back of the card\nOrder 739 of 12 shipped 2026",
                "Total due 739 of 2026", "pin the notice 8264 on the board", "PINE 8264", "SPIN 8264",
                "PIN: 14-21-106-017-0000", "PIN 14211060170000", "PIN set on 11/29/2026", "PIN amount 8264.50",
                "Your PIN will be mailed\nAccount opened 2026", "Invoice no 8264")) {
            assertEquals("", found(text), text);
        }
    }

    private static String found(String text) {
        List<DetectedEntity> out = new ArrayList<>();
        new LockedItemDetector().detect(text, 1, out);
        StringBuilder all = new StringBuilder();
        for (DetectedEntity e : out) {
            if (!all.isEmpty()) all.append(' ');
            all.append(e.entityType()).append('=').append(text, e.startOffset(), e.endOffset());
        }
        return all.toString();
    }
}
