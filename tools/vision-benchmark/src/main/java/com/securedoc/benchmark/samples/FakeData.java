package com.securedoc.benchmark.samples;

import java.util.List;
import java.util.Random;

/**
 * Fake values for synthetic samples, from a fixed seed so the set is reproducible. Nothing here is a
 * real person's data: names and streets come from short made-up lists, phone numbers use the
 * fictional 555-01xx range, card numbers are random Luhn-valid numbers under the 4000 00 test prefix,
 * and routing numbers are random 9-digit numbers with a valid checksum. SSNs are random numbers in a
 * structurally valid range (the product's checks reject invalid ranges, so an invalid range would
 * measure the checks, not the reading).
 *
 * <p>The values exist only inside the generated PDFs, which live outside the repository. They are never
 * written to an answer file, a log or a result.
 */
final class FakeData {

    private static final List<String> FIRST = List.of(
            "Arlen", "Bettina", "Corwin", "Delphine", "Emeric", "Fenella", "Garrick", "Henrietta",
            "Isidor", "Jessamy", "Kendric", "Linnea", "Marlo", "Nerissa", "Osric", "Perpetua");
    private static final List<String> LAST = List.of(
            "Quillfeather", "Ravensworth", "Stonebridge", "Thistlewood", "Underhay", "Vantwell",
            "Wexcombe", "Yarrowby", "Ashdown", "Brackenridge", "Coldharbour", "Dunmore");
    private static final List<String> STREET = List.of(
            "Sample Street", "Example Avenue", "Placeholder Road", "Fictional Lane", "Testing Court",
            "Specimen Way", "Demo Boulevard");
    private static final List<String> CITY = List.of(
            "Sampleton", "Exampleville", "Mockford", "Testbury", "Fauxhaven");

    private final Random random;

    FakeData(long seed) {
        this.random = new Random(seed);
    }

    Random random() {
        return random;
    }

    record Person(String first, String last, String ssn, String dob, String phone, String street,
                  String cityLine, String card, String account, String routing, String licence,
                  int creditScore) {
        String name() {
            return first + " " + last;
        }
    }

    Person person() {
        String first = pick(FIRST);
        String last = pick(LAST);
        return new Person(first, last, ssn(), dob(), phone(), (100 + random.nextInt(8900)) + " " + pick(STREET),
                pick(CITY) + ", SS " + (10000 + random.nextInt(89999)), card(), digits(10 + random.nextInt(3)),
                routing(), (char) ('A' + random.nextInt(26)) + digits(7), 580 + random.nextInt(241));
    }

    String ssn() {
        int area;
        do {
            area = 1 + random.nextInt(899);
        } while (area == 666);
        int group = 1 + random.nextInt(99);
        int serial = 1 + random.nextInt(9999);
        return String.format("%03d-%02d-%04d", area, group, serial);
    }

    String dob() {
        return String.format("%02d/%02d/%04d", 1 + random.nextInt(12), 1 + random.nextInt(28), 1950 + random.nextInt(50));
    }

    String recentDate() {
        return String.format("%02d/%02d/2026", 1 + random.nextInt(12), 1 + random.nextInt(28));
    }

    String phone() {
        return String.format("(%03d) 555-01%02d", 200 + random.nextInt(700), random.nextInt(100));
    }

    /** A Luhn-valid 16-digit number under the 4000 00 test prefix, grouped in fours. */
    String card() {
        StringBuilder number = new StringBuilder("400000");
        while (number.length() < 15) {
            number.append(random.nextInt(10));
        }
        number.append(luhnCheckDigit(number.toString()));
        return number.substring(0, 4) + " " + number.substring(4, 8) + " " + number.substring(8, 12) + " " + number.substring(12);
    }

    /** A 9-digit number with a valid ABA checksum (3-7-1 weights). */
    String routing() {
        int[] d = new int[9];
        d[0] = random.nextInt(2);
        d[1] = 1 + random.nextInt(9);
        for (int i = 2; i < 8; i++) {
            d[i] = random.nextInt(10);
        }
        int sum = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5]);
        d[8] = (10 - sum % 10) % 10;
        StringBuilder out = new StringBuilder();
        for (int digit : d) {
            out.append(digit);
        }
        return out.toString();
    }

    String digits(int count) {
        StringBuilder out = new StringBuilder();
        out.append(1 + random.nextInt(9));
        while (out.length() < count) {
            out.append(random.nextInt(10));
        }
        return out.toString();
    }

    String amount() {
        return String.format("%,d.%02d", 100 + random.nextInt(9900), random.nextInt(100));
    }

    <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    static int luhnCheckDigit(String partial) {
        int sum = 0;
        boolean doubleIt = true;
        for (int i = partial.length() - 1; i >= 0; i--) {
            int n = partial.charAt(i) - '0';
            if (doubleIt) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            doubleIt = !doubleIt;
        }
        return (10 - sum % 10) % 10;
    }
}
