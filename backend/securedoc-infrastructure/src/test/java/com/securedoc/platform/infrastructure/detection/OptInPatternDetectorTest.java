package com.securedoc.platform.infrastructure.detection;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0043: the opt-in pattern kinds, through the product's rule-based adapter. Fake values only: routing numbers are
 * a made-up 8-digit prefix plus the ABA check digit; the other numbers are invented. Assertions compare spans by
 * position, and no test prints a value.
 */
class OptInPatternDetectorTest {

    private static final Set<String> NEW_KINDS = Set.of(OptInPatternDetector.ROUTING, OptInPatternDetector.ACCOUNT,
            OptInPatternDetector.ITIN, OptInPatternDetector.EIN, OptInPatternDetector.DRIVERS_LICENSE,
            OptInPatternDetector.PASSPORT);

    private static final String ROUTING = aba("03200071");
    private static final String ROUTING_2 = aba("11340052");
    private static final String ROUTING_WITH_6 = aba("06100052");
    private static final String ACCOUNT = "4410098765";

    private final LocalRuleBasedDetectionAdapter adapter = new LocalRuleBasedDetectionAdapter();

    // ------------------------------------------------------------------ routing number

    @Nested
    class RoutingNumber {
        @Test
        void labelledOnTheSameLine() {
            assertSpans("Routing number: " + ROUTING, OptInPatternDetector.ROUTING, ROUTING);
            assertSpans("ABA # " + ROUTING_2, OptInPatternDetector.ROUTING, ROUTING_2);
            assertSpans("RTN " + ROUTING, OptInPatternDetector.ROUTING, ROUTING);
        }

        @Test
        void labelOnTheLineAboveInAFormHeaderRow() {
            String text = "Checking account number Bank routing number\n" + ACCOUNT + " " + ROUTING;
            assertSpans(text, OptInPatternDetector.ROUTING, ROUTING);
            assertSpans(text, OptInPatternDetector.ACCOUNT, ACCOUNT);
            String reversed = "Routing number Account number\n" + ROUTING + " " + ACCOUNT;
            assertSpans(reversed, OptInPatternDetector.ROUTING, ROUTING);
            assertSpans(reversed, OptInPatternDetector.ACCOUNT, ACCOUNT);
        }

        @Test
        void ocrMisreadIsFoundAndGoesToReview() {
            String misread = "O" + ROUTING.substring(1); // OCR read the leading 0 as the letter O
            assertTrue(ROUTING.startsWith("0"));
            List<DetectedEntity> found = of("Routing number: " + misread, OptInPatternDetector.ROUTING);
            assertEquals(1, found.size());
            assertTrue(found.get(0).confidence() < 0.80, "a corrected misread is below the default threshold");
            List<DetectedEntity> label = of("Rout1ng number: " + ROUTING, OptInPatternDetector.ROUTING);
            assertEquals(1, label.size(), "misread label");
        }

        @Test
        void nearMissesAreNotRoutingNumbers() {
            String badChecksum = ROUTING.substring(0, 8) + ((ROUTING.charAt(8) - '0' + 1) % 10);
            assertNone("Routing number: " + badChecksum, OptInPatternDetector.ROUTING);
            assertNone("Your reference " + ROUTING + " is on file.", OptInPatternDetector.ROUTING);
            assertNone("Order number: " + ROUTING, OptInPatternDetector.ROUTING);
            assertNone("Routing number: 000000000", OptInPatternDetector.ROUTING);
            assertNone("Routing: call (555) 201-0142 or ZIP 32007-1234", OptInPatternDetector.ROUTING);
        }
    }

    // ------------------------------------------------------------------ check line (MICR)

    @Nested
    class CheckLine {
        @Test
        void transitSymbolsAsPrinted() {
            String text = "PAY TO THE ORDER OF Sample Title Company\n⑆" + ROUTING + "⑆ " + ACCOUNT + "⑈ 1042";
            assertSpans(text, OptInPatternDetector.ROUTING, ROUTING);
            assertSpans(text, OptInPatternDetector.ACCOUNT, ACCOUNT);
            assertNone(text.replace(ACCOUNT, "").replace(ROUTING, ""), OptInPatternDetector.ACCOUNT);
        }

        @Test
        void transitSymbolsAsOcrReadsThem() {
            String text = "Memo\n:" + ROUTING + ":  " + ACCOUNT + "\"  1042";
            assertSpans(text, OptInPatternDetector.ROUTING, ROUTING);
            assertSpans(text, OptInPatternDetector.ACCOUNT, ACCOUNT);
            String pipes = "|:" + ROUTING + "|: " + ACCOUNT + "|| 1042";
            assertSpans(pipes, OptInPatternDetector.ROUTING, ROUTING);
            assertSpans(pipes, OptInPatternDetector.ACCOUNT, ACCOUNT);
        }

        @Test
        void businessCheckWithTheCheckNumberFirst() {
            String text = "⑈001042⑈ ⑆" + ROUTING + "⑆ " + ACCOUNT + "⑈";
            assertSpans(text, OptInPatternDetector.ROUTING, ROUTING);
            assertSpans(text, OptInPatternDetector.ACCOUNT, ACCOUNT);
        }

        @Test
        void plainRoutingAccountCheckNumberLine() {
            String text = "Sample Community Bank\n" + ROUTING + " " + ACCOUNT + " 1042\nThank you";
            assertSpans(text, OptInPatternDetector.ROUTING, ROUTING);
            assertSpans(text, OptInPatternDetector.ACCOUNT, ACCOUNT);
        }

        @Test
        void noisyOcrLineOnACheckPage() {
            // OCR added letter noise and read a 6 as b; the page reads like a check.
            assertTrue(ROUTING_WITH_6.indexOf('6') >= 0);
            String noisy = ROUTING_WITH_6.replaceFirst("6", "b");
            String text = "EARNEST MONEY DEPOSIT - COPY OF CHECK\nAmount in words and 00/100 DOLLARS\n*"
                    + noisy + ": " + ACCOUNT + "\" eoO4y2";
            assertEquals(1, of(text, OptInPatternDetector.ROUTING).size());
            assertSpans(text, OptInPatternDetector.ACCOUNT, ACCOUNT);
        }

        @Test
        void aNumberLineThatIsNotACheckLine() {
            // Checksum-invalid, or no symbols and no check wording, or a table of figures.
            String badChecksum = ROUTING.substring(0, 8) + ((ROUTING.charAt(8) - '0' + 1) % 10);
            assertNone(":" + badChecksum + ": " + ACCOUNT + "\" 1042", OptInPatternDetector.ROUTING);
            assertNone("Totals " + ROUTING + " " + ACCOUNT + " 1042 2026", OptInPatternDetector.ROUTING);
            // Task 0103: a 10-digit number nothing names is held for a person (0.70), not taken from a check line.
            assertHeldForAPerson("Totals " + ROUTING + " " + ACCOUNT + " 1042 2026", OptInPatternDetector.ACCOUNT);
        }
    }

    // ------------------------------------------------------------------ account number

    @Nested
    class AccountNumber {
        @Test
        void labelledValues() {
            assertSpans("Account number: " + ACCOUNT, OptInPatternDetector.ACCOUNT, ACCOUNT);
            assertSpans("Acct # 99120034", OptInPatternDetector.ACCOUNT, "99120034");
            assertSpans("A/C No. 5521-0098-77", OptInPatternDetector.ACCOUNT, "5521-0098-77");
            assertSpans("Account no 8812", OptInPatternDetector.ACCOUNT, "8812");
            assertSpans("Bank account number:\n" + ACCOUNT, OptInPatternDetector.ACCOUNT, ACCOUNT);
            assertSpans("Acc0unt number: " + ACCOUNT, OptInPatternDetector.ACCOUNT, ACCOUNT);
        }

        @Test
        void nearMissesAreNotAccountNumbers() {
            assertNone("Account phone: 555-201-0142", OptInPatternDetector.ACCOUNT);
            assertNone("Account number: 555-201-0142", OptInPatternDetector.ACCOUNT);
            assertNone("Account balance: $4,410.98", OptInPatternDetector.ACCOUNT);
            assertNone("Account number: $ 4410", OptInPatternDetector.ACCOUNT);
            assertNone("Account opened 10/31/2026", OptInPatternDetector.ACCOUNT);
            assertNone("Account opened 2026", OptInPatternDetector.ACCOUNT);
            assertNone("Account address ZIP 32007-1234", OptInPatternDetector.ACCOUNT);
            assertNone("Account number: 123-45-6789", OptInPatternDetector.ACCOUNT); // SSN-shaped
            assertNone("Account number: 12-3456789", OptInPatternDetector.ACCOUNT); // EIN-shaped
            assertNone("Order number: " + ACCOUNT, OptInPatternDetector.ACCOUNT);
            assertNone("Loan account details\nLoan number " + ACCOUNT, OptInPatternDetector.ACCOUNT);
            assertNone("Card account number: 4000 0012 3456 7899", OptInPatternDetector.ACCOUNT);
            assertNone("Account number: 123", OptInPatternDetector.ACCOUNT);
            assertNone("Account number: 123456789012345678", OptInPatternDetector.ACCOUNT); // 18 digits
            // Task 0103: an account word in the sentence and an account's shape: a person decides (was: nothing).
            assertHeldForAPerson("Please keep your account details safe. " + ACCOUNT, OptInPatternDetector.ACCOUNT);
            assertNone("Account summary\nStatement period 10/01/2026", OptInPatternDetector.ACCOUNT);
        }
    }

    // ------------------------------------------------------------------ US tax IDs

    @Nested
    class TaxIds {
        @Test
        void itinWithItsLabel() {
            assertSpans("ITIN: 912-78-1234", OptInPatternDetector.ITIN, "912-78-1234");
            assertSpans("Individual Taxpayer Identification Number 900 94 5678", OptInPatternDetector.ITIN, "900 94 5678");
            assertSpans("SSN or ITIN: 912-90-1234", OptInPatternDetector.ITIN, "912-90-1234");
            assertSpans("912-88-1234 (ITIN)", OptInPatternDetector.ITIN, "912-88-1234");
            assertSpans("ITIN\n912-70-0001", OptInPatternDetector.ITIN, "912-70-0001");
        }

        @Test
        void itinNearMisses() {
            assertNone("SSN: 912-78-1234", OptInPatternDetector.ITIN); // an SSN label owns it
            assertNone("ITIN: 912-65-1234", OptInPatternDetector.ITIN); // group outside the ITIN ranges
            assertNone("ITIN: 912-89-1234", OptInPatternDetector.ITIN);
            assertNone("ITIN: 912-93-1234", OptInPatternDetector.ITIN);
            assertNone("ITIN: 812-78-1234", OptInPatternDetector.ITIN); // does not start with 9
            assertNone("Reference 912-78-1234", OptInPatternDetector.ITIN); // no label
        }

        @Test
        void einWithItsLabel() {
            assertSpans("EIN: 12-3456789", OptInPatternDetector.EIN, "12-3456789");
            assertSpans("Employer Identification Number 45-6789012", OptInPatternDetector.EIN, "45-6789012");
            assertSpans("Federal Tax ID 98-7654321", OptInPatternDetector.EIN, "98-7654321");
            assertSpans("FEIN 371234567", OptInPatternDetector.EIN, "371234567");
            assertSpans("12-3456789 (EIN)", OptInPatternDetector.EIN, "12-3456789");
            assertSpans("Employer EIN\n27-1234567", OptInPatternDetector.EIN, "27-1234567");
        }

        @Test
        void einNearMisses() {
            assertNone("EIN: 07-1234567", OptInPatternDetector.EIN); // prefix never issued
            assertNone("EIN: 89-1234567", OptInPatternDetector.EIN);
            assertNone("Part number 12-3456789", OptInPatternDetector.EIN);
            assertNone("EIN applied for\n371234567", OptInPatternDetector.EIN); // plain 9 digits not next to the label
            assertNone("EIN: 123-45-6789", OptInPatternDetector.EIN); // SSN-shaped
            assertNone("Tax ID: 12-34567890", OptInPatternDetector.EIN); // 10 digits
        }

        @Test
        void taxIdsAreNotSsnFindings() {
            List<DetectedEntity> all = adapter.detect(List.of(new PageInput(1, "ITIN: 912-78-1234 EIN: 12-3456789")))
                    .entities();
            assertTrue(all.stream().noneMatch(e -> e.entityType().equals("SSN")));
        }
    }

    // ------------------------------------------------------------------ driver's license and passport

    @Nested
    class LicenseAndPassport {
        @Test
        void driversLicenseWithItsLabel() {
            assertSpans("Driver's license number: K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("Driver licence number\nX4419020", OptInPatternDetector.DRIVERS_LICENSE, "X4419020");
            assertSpans("DL NO. K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("DLNO. Q5530912", OptInPatternDetector.DRIVERS_LICENSE, "Q5530912"); // OCR joined the label
            assertSpans("DL N0. K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215"); // OCR read O as 0
            assertSpans("Drivor licence-number\n44190207", OptInPatternDetector.DRIVERS_LICENSE, "44190207");
            assertSpans("Driver's License # D123-456-78-901-0", OptInPatternDetector.DRIVERS_LICENSE, "D123-456-78-901-0");
        }

        @Test
        void driversLicenseInAHeaderRowNextToACreditScore() {
            String text = "Driver licence number\nCredit score used for this application\nX4419020\n721";
            // Header rows can also be stacked label, label, value, value; the nearest owner decides.
            assertEquals(List.of(), spans(text, OptInPatternDetector.DRIVERS_LICENSE).stream()
                    .filter(s -> s.equals("721")).toList());
            String sideBySide = "Driver licence number Credit score used for this application\nX4419020 721";
            assertSpans(sideBySide, OptInPatternDetector.DRIVERS_LICENSE, "X4419020");
        }

        @Test
        void driversLicenseNearMisses() {
            // Task 0103: a letter and digits nothing names: a person decides (was: keyword required).
            assertHeldForAPerson("K7302215", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("License plate ABC1234", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("Driver's license expires 10/31/2028", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("Driver's license number: 1234", OptInPatternDetector.DRIVERS_LICENSE); // too short
            assertNone("Driver's license: see attached copy", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("Driver's license number: 555-201-0142", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("Order number: K7302215", OptInPatternDetector.DRIVERS_LICENSE);
        }

        /** Task 0069: one value of each common state format, and shapes no state uses. */
        @Test
        void driversLicenseStateFormats() {
            for (String value : List.of("12345678", "1234567890123", "K7302215", "S530429100123", "D123-4567-8901",
                    "AB123456", "AB123456C", "ABC123456", "123AB4567", "12ABC34567", "1234567A")) {
                assertSpans("Driver's license number: " + value, OptInPatternDetector.DRIVERS_LICENSE, value);
                assertNone("Reference " + value, OptInPatternDetector.DRIVERS_LICENSE);
            }
            for (String shape : List.of("1A2B3456", "12345AB", "123456789012345", "A123B456C")) {
                assertNone("Driver's license number: " + shape, OptInPatternDetector.DRIVERS_LICENSE);
            }
        }

        /** Task 0069: the labels the requirement names, the right check digits, and the same values without them. */
        @Test
        void theNamedLabelsAndTheCheckDigits() {
            assertSpans("Routing 032000719", OptInPatternDetector.ROUTING, "032000719");
            assertNone("Routing 032000718", OptInPatternDetector.ROUTING);
            assertNone("Batch 032000719", OptInPatternDetector.ROUTING);
            assertSpans("Acct 4410098765", OptInPatternDetector.ACCOUNT, "4410098765");
            assertNone("Batch 4410098765", OptInPatternDetector.ACCOUNT);
            assertSpans("DL# K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("Passport No. X12345678", OptInPatternDetector.PASSPORT, "X12345678");
            assertNone("EIN 00-1234567", OptInPatternDetector.EIN);
            assertNone("ITIN 912-00-1234", OptInPatternDetector.ITIN);
        }

        /** Task 0069: a labelled number no SSN can have is kept with less confidence; unlabelled, nothing is found. */
        @Test
        void impossibleSsnRanges() {
            LocalRuleBasedDetectionAdapter adapter = new LocalRuleBasedDetectionAdapter();
            for (String value : List.of("000-12-3456", "666-12-3456", "987-65-4320", "412-00-7531", "412-55-0000")) {
                assertEquals(List.of(0.75), ssnConfidences(adapter, "Borrower SSN: " + value), value);
                assertEquals(List.of(), ssnConfidences(adapter, "Batch " + value), value);
            }
            assertEquals(List.of(0.95), ssnConfidences(adapter, "Borrower SSN: 412-55-7531"));
            // Task 0103: in the SSN ranges, a value the document calls something else ("Batch") goes to a person.
            assertEquals(List.of(0.70), ssnConfidences(adapter, "Batch 412-55-7531"));
            assertEquals(List.of(0.90), ssnConfidences(adapter, "Notes: 412-55-7531"), "no label: covered");
        }

        /** Cards: the check digit and a card label, both. */
        @Test
        void cardsNeedTheirCheckDigit() {
            LocalRuleBasedDetectionAdapter adapter = new LocalRuleBasedDetectionAdapter();
            assertEquals(1, count(adapter, "Card number 4000 0566 5566 5556", "CREDIT_CARD_NUMBER"));
            // Task 0103: card words, an issuer's digits, a failed check digit: a person (OCR may have misread one).
            assertEquals(List.of(0.60), cardConfidences(adapter, "Card number 4000 0566 5566 5557"));
            assertEquals(0, count(adapter, "Batch 4000 0566 5566 5557", "CREDIT_CARD_NUMBER"), "no card words, no check");
            // Task 0103 (changed from 0099): a valid card number is a card wherever it stands; one the document names
            // as something else ("Batch", "Contract number") goes to a person, grouped or not.
            assertEquals(List.of(0.75), cardConfidences(adapter, "Batch 4000 0566 5566 5556"));
            assertEquals(List.of(0.75), cardConfidences(adapter, "Batch 4000056655665556"));
            assertEquals(List.of(0.75), cardConfidences(adapter, "Contract number 4000 0566 5566 5556"));
            assertEquals(List.of(0.90), cardConfidences(adapter, "Posted 4000056655665556"));
        }

        private static List<Double> cardConfidences(LocalRuleBasedDetectionAdapter adapter, String text) {
            return adapter.detect(List.of(new com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput(1, text)))
                    .entities().stream().filter(e -> e.entityType().equals("CREDIT_CARD_NUMBER"))
                    .map(DetectedEntity::confidence).toList();
        }

        private static List<Double> ssnConfidences(LocalRuleBasedDetectionAdapter adapter, String text) {
            return adapter.detect(List.of(new com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput(1, text)))
                    .entities().stream().filter(e -> e.entityType().equals("SSN")).map(DetectedEntity::confidence).toList();
        }

        private static long count(LocalRuleBasedDetectionAdapter adapter, String text, String kind) {
            return adapter.detect(List.of(new com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput(1, text)))
                    .entities().stream().filter(e -> e.entityType().equals(kind)).count();
        }

        @Test
        void passportWithItsLabel() {
            assertSpans("Passport number: X12345678", OptInPatternDetector.PASSPORT, "X12345678");
            assertSpans("Passport No. 912345678", OptInPatternDetector.PASSPORT, "912345678");
            assertSpans("PASSPORT\nC03005988", OptInPatternDetector.PASSPORT, "C03005988");
        }

        @Test
        void passportNearMisses() {
            assertHeldForAPerson("X12345678", OptInPatternDetector.PASSPORT); // task 0103: no label, a person decides
            assertNone("Passport photo attached", OptInPatternDetector.PASSPORT);
            assertNone("Passport number: 12345", OptInPatternDetector.PASSPORT);
            assertNone("Passport issued 10/31/2020", OptInPatternDetector.PASSPORT);
        }
    }

    // ------------------------------------------------------------------ task 0047

    @Nested
    class Task0047 {
        /** Item 3: one field for either; an SSN never starts with 9, so the ITIN detector takes it in both orders. */
        @Test
        void itinOrSsnInEitherOrderIsAnItin() {
            assertSpans("SSN or ITIN: 912-78-1234", OptInPatternDetector.ITIN, "912-78-1234");
            assertSpans("ITIN or SSN: 912-78-1234", OptInPatternDetector.ITIN, "912-78-1234");
            assertSpans("Social Security Number or ITIN 912-78-1234", OptInPatternDetector.ITIN, "912-78-1234");
            assertSpans("SSN/ITIN\n912-78-1234", OptInPatternDetector.ITIN, "912-78-1234");
            assertNone("SSN: 912-78-1234", OptInPatternDetector.ITIN); // an SSN label alone still never makes an ITIN
            assertNone("ITIN or SSN: 412-55-7531", OptInPatternDetector.ITIN); // not ITIN-shaped: the SSN detector's
        }

        /** Item 4: "license number" alone is not a driver's licence label. */
        @Test
        void otherLicencesAreNotDriversLicences() {
            assertNone("Business license number: K7302215", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("Contractor license K7302215", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("Contractor license number: K7302215", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("Marriage license number 44712290", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("Marriage license no. 44712290", OptInPatternDetector.DRIVERS_LICENSE);
            assertNone("License number: K7302215", OptInPatternDetector.DRIVERS_LICENSE); // no driver wording
            assertNone("License #\nK7302215", OptInPatternDetector.DRIVERS_LICENSE);
            // Driver wording nearby does not make another licence a driver's licence.
            assertNone("Driver: test borrower. Business license number: K7302215", OptInPatternDetector.DRIVERS_LICENSE);
        }

        @Test
        void driverWordingMakesItADriversLicence() {
            assertSpans("Driver's license no. K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("Driver’s licence no. K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("DL # K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("D.L. K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("Operator's license number: K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("DMV number: K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            // The driver wording in the label window, the bare "license number" directly before the value.
            assertSpans("Driver information\nLicense number: K7302215", OptInPatternDetector.DRIVERS_LICENSE, "K7302215");
            assertSpans("Department of Motor Vehicles\nLicense no. K7302215", OptInPatternDetector.DRIVERS_LICENSE,
                    "K7302215");
        }
    }

    // ------------------------------------------------------------------ everything together

    @Test
    void ordinaryBusinessTextHasNoNewKindFindings() {
        String text = """
                Order number: 4410098765   Invoice # 2026-1104   Phone: (555) 201-0142
                Amount due: $12,345.67 on 10/31/2026. Mail to 100 Main St, Sampleton, SS 32007-1234.
                Reference 912-78-1234. Part 12-3456789. Tracking number 1Z999AA10123456784.
                Customer since 2019; 3 accounts; 120 days; account review on 11/02/2026.
                Score 721. Loan number 7700123456. Policy number 88120034.
                """;
        assertTrue(adapter.detect(List.of(new PageInput(1, text))).entities().stream()
                .noneMatch(e -> NEW_KINDS.contains(e.entityType())), "no false flags on ordinary text");
    }

    @Test
    void newKindsCarryTheirOwnDetectorNameAndNeverChangeV1Findings() {
        String text = "SSN: 123-45-6789 Date of birth: 01/02/1980\nRouting number: " + ROUTING
                + "\nAccount number: " + ACCOUNT;
        List<DetectedEntity> all = adapter.detect(List.of(new PageInput(1, text))).entities();
        all.stream().filter(e -> NEW_KINDS.contains(e.entityType()))
                .forEach(e -> assertEquals(OptInPatternDetector.DETECTOR_NAME, e.detectorName()));
        assertTrue(all.stream().map(DetectedEntity::entityType).toList().containsAll(List.of("SSN", "DATE_OF_BIRTH")));
        assertEquals(1, of(text, OptInPatternDetector.ROUTING).size());
        assertEquals(1, of(text, OptInPatternDetector.ACCOUNT).size());
        all.stream().filter(e -> !NEW_KINDS.contains(e.entityType()))
                .forEach(e -> assertEquals("local-rule-based", e.detectorName()));
    }

    // ------------------------------------------------------------------ helpers

    private List<DetectedEntity> of(String text, String kind) {
        return adapter.detect(List.of(new PageInput(1, text))).entities().stream()
                .filter(e -> e.entityType().equals(kind)).toList();
    }

    private List<String> spans(String text, String kind) {
        return of(text, kind).stream().map(e -> text.substring(e.startOffset(), e.endOffset())).toList();
    }

    /** Exactly one finding of the kind, covering exactly the expected text. Compares spans; prints nothing. */
    private void assertSpans(String text, String kind, String expected) {
        List<String> spans = spans(text, kind);
        assertEquals(1, spans.size(), () -> "one " + kind + " finding, got " + spans.size());
        assertTrue(spans.get(0).equals(expected), () -> kind + ": the finding covers a different span");
    }

    /** Task 0103: found for a person to decide (below the 0.80 threshold), never covered on its own. */
    private void assertHeldForAPerson(String text, String kind) {
        List<DetectedEntity> found = of(text, kind);
        assertEquals(1, found.size(), () -> "one " + kind + " finding for a person, got " + found.size());
        assertTrue(found.get(0).confidence() < 0.80, () -> kind + " is held for a person, not covered");
    }

    private void assertNone(String text, String kind) {
        int count = of(text, kind).size();
        assertEquals(0, count, () -> "no " + kind + " finding expected, got " + count);
    }

    /** A fake routing number: an 8-digit prefix and its ABA 3-7-1 check digit. */
    static String aba(String eight) {
        int[] d = eight.chars().map(c -> c - '0').toArray();
        int sum = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5]);
        return eight + ((10 - sum % 10) % 10);
    }
}
