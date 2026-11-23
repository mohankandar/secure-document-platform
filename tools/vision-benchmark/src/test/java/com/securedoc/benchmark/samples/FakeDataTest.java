package com.securedoc.benchmark.samples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeDataTest {

    @Test
    void cardNumbersAreLuhnValidTestNumbers() {
        FakeData fake = new FakeData(1);
        for (int i = 0; i < 50; i++) {
            String card = fake.card().replace(" ", "");
            assertEquals(16, card.length());
            assertTrue(card.startsWith("400000"));
            assertEquals(card.charAt(15) - '0', FakeData.luhnCheckDigit(card.substring(0, 15)));
        }
    }

    @Test
    void routingNumbersHaveAValidChecksumAndSsnsAValidShape() {
        FakeData fake = new FakeData(2);
        for (int i = 0; i < 50; i++) {
            String r = fake.routing();
            int[] d = r.chars().map(c -> c - '0').toArray();
            int sum = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5] + d[8]);
            assertEquals(0, sum % 10);
            String ssn = fake.ssn();
            assertTrue(ssn.matches("\\d{3}-\\d{2}-\\d{4}"));
            int area = Integer.parseInt(ssn.substring(0, 3));
            assertTrue(area > 0 && area < 900 && area != 666);
            assertNotEquals("00", ssn.substring(4, 6));
            assertTrue(fake.phone().contains("555-01"));
        }
    }
}
