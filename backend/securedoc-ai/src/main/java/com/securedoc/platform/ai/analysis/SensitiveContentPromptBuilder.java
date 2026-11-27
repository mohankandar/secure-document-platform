package com.securedoc.platform.ai.analysis;

import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy;
import com.securedoc.platform.domain.finding.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral prompt composition from tenant redaction policy.
 *
 * <p>Provider adapters are responsible only for transport/model invocation. They do not own
 * customer category selection or category semantics.
 */
public final class SensitiveContentPromptBuilder {

    /**
     * Task 0072 (BL7): the recorded version of {@link #buildNames()}; any change of that text is a new one. At most 6
     * characters: a finding stores it with what was checked, in 50.
     */
    public static final String NAMES_VERSION = "name1";

    /** Task 0072: names have a request of their own; beside the other kinds the model returned none. */
    static boolean asksForNames(AnalysisPolicy policy) {
        return policy.enabledEntityTypes().contains(EntityType.PERSON_NAME);
    }

    /** The other kinds, whose prompt and answer shape are the same with and without names. */
    static AnalysisPolicy withoutNames(AnalysisPolicy policy) {
        return new AnalysisPolicy(policy.enabledEntityTypes().stream().filter(t -> t != EntityType.PERSON_NAME)
                .collect(java.util.stream.Collectors.toSet()), policy.maskSsnLastFour());
    }

    /** Task 0083 (BL7): the second check's text prompt; any change of the file is a new version. */
    public static final String SECOND_CHECK_VERSION = "second-check-text-v1";

    /** Task 0065 (BL7): a document's people, and what a known name is in a sentence; any change is a new version. */
    public static final String SUMMARY_VERSION = "document-summary-v1";
    public static final String KNOWN_NAMES_VERSION = "known-names-v1";

    static String secondCheck() {
        return text(SECOND_CHECK_VERSION);
    }

    static String text(String version) {
        try (java.io.InputStream in = SensitiveContentPromptBuilder.class.getResourceAsStream(
                "/" + version + ".txt")) {
            if (in == null) throw new IllegalStateException(version + " is missing");
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(version + " could not be read");
        }
    }

    /** What the model is sent for a page of the second check, or for the beginning of a document. */
    static String secondCheckPage(String text) {
        return "<PAGE>\n" + text + "\n</PAGE>";
    }

    public String buildNames() {
        return """
                Find every person name. Read the page line by line. matchedText is the name exactly as written: a full name, a name in capitals, "Last, First", or a last name standing alone in a sentence. Give every different written form once. Never return companies, banks, courts, agencies, counties, streets, places, programs or roles (Borrower, Plaintiff, Loan Officer); if nobody is named, return no findings. Return JSON only: findings[] with pageNumber, entityType (always PERSON_NAME), matchedText, confidence.""";
    }

    public String build(AnalysisPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");

        List<String> categories = new ArrayList<>();
        if (policy.enabledEntityTypes().contains(EntityType.SSN)) {
            categories.add(ssnInstructions(policy.maskSsnLastFour()));
        }
        if (policy.enabledEntityTypes().contains(EntityType.DATE_OF_BIRTH)) {
            categories.add(dobInstructions());
        }
        if (policy.enabledEntityTypes().contains(EntityType.CREDIT_CARD_NUMBER)) {
            categories.add(creditCardInstructions());
        }
        if (policy.enabledEntityTypes().contains(EntityType.CREDIT_SCORE)) {
            categories.add(creditScoreInstructions());
        }
        // Task 0103: the other kinds found in text, asked of the AI too.
        categories.addAll(otherKinds(policy.enabledEntityTypes()));

        String requested = categories.isEmpty()
                ? "No sensitive categories are enabled for this analysis. Return {\"findings\":[]}."
                : String.join("\n\n", categories);
        String exampleEntityType = policy.enabledEntityTypes().stream()
                .map(Enum::name)
                .sorted()
                .findFirst()
                .orElse("REQUESTED_CATEGORY");

        return """
                You are a sensitive-content discovery engine for secure document redaction.
                Independently inspect the supplied page text. Do not wait for regex or rule candidates.

                Analyze ONLY the categories requested by the tenant policy below. Do not discover,
                infer, or return other PII categories merely because they are present on the page.

                Use meaning, labels, relationships, and surrounding document context. Return the exact
                VALUE span that should be redacted, never the label surrounding it. Do not invent values.
                If a page only discusses a sensitive category without exposing a customer's actual value,
                return no finding for that category.

                TENANT-REQUESTED CATEGORIES:
                %s

                Respond with JSON only in this shape:
                {"findings":[{"entityType":"%s","matchedText":"exact value text","contextText":"short exact quote containing the value","confidence":0.98}]}
                If nothing requested and sensitive is present, return {"findings":[]}.
                """.formatted(requested, exampleEntityType);
    }

    /**
     * Compact extraction prompt for local Ollama inference. It keeps the same policy semantics
     * and precision exclusions as {@link #build(AnalysisPolicy)} while reducing repeated prompt
     * tokens when many pages are analyzed.
     */
    public String buildCompact(AnalysisPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");

        List<String> categories = new ArrayList<>();
        Set<EntityType> kinds = policy.enabledEntityTypes();
        if (kinds.contains(EntityType.SSN)) {
            categories.add(policy.maskSsnLastFour()
                    ? "SSN: an exposed SSN (3-2-4 digits), with or without a label, also an old, short or Spanish label (Soc. Sec., SS#, Seguro Social); last-4 only when labeled SSN. Protect synthetic/test SSNs even when the digits are non-assignable. Skip a value the page explicitly calls a Form/Reference/Contract number or says is not an SSN. Labels alone are not findings."
                    : "SSN: an exposed full SSN (3-2-4 digits), with or without a label, also an old, short or Spanish label (Soc. Sec., SS#, Seguro Social). Protect synthetic/test SSNs even when the digits are non-assignable. Skip a value the page explicitly calls a Form/Reference/Contract number or says is not an SSN. Do not return last-4/partially masked values or labels alone.");
        }
        if (kinds.contains(EntityType.DATE_OF_BIRTH)) {
            categories.add("DATE_OF_BIRTH: a date that is, or may be, a person's birth date, in any format or language (DOB, B'date, Fecha de nacimiento, born on), also without a label; matchedText is the date, never a person's name. Without a label give confidence 0.6. Skip dates the page clearly gives another meaning: contract, sale, signature, report, payment, due, effective, expiration, statement, servicing and form dates.");
        }
        if (kinds.contains(EntityType.CREDIT_CARD_NUMBER)) {
            categories.add("CREDIT_CARD_NUMBER: a full payment-card number (13-19 digits, optional spaces/hyphens), with or without a label; omit masked/last-four-only displays; skip routing/phone numbers, amounts, dates and barcode markers.");
        }
        if (kinds.contains(EntityType.CREDIT_SCORE)) {
            categories.add("CREDIT_SCORE: a consumer credit/FICO/Beacon/Vantage score of a person, also with a Spanish label (puntaje de credito); in tables a 'Your credit score' column header applies to the score values in the rows below. Exclude explanatory ranges such as 300-850 and examples.");
        }
        categories.addAll(otherKinds(kinds));

        String requested = categories.isEmpty() ? "NONE" : String.join("\n", categories);
        return """
                Extract ONLY tenant-requested sensitive values from each supplied page. Find a value by what it
                is, with or without a label; a label or meaning raises confidence. Never invent text. matchedText
                must be the exact value span present on its declared page, not the label. contextText must be a
                short exact quote containing matchedText. Labels, policy/privacy language, category names, and
                examples are not sensitive values. If no requested value is exposed, omit it.

                REQUESTED:
                %s

                Return JSON only: findings[] with pageNumber, entityType, matchedText, contextText, confidence.
                """.formatted(requested);
    }

    private String ssnInstructions(boolean maskLastFour) {
        String partial = maskLastFour
                ? """
                  Partially masked SSNs are in scope. If the page explicitly identifies a value as an
                  SSN and only the final four digits remain visible (for example 'SSN: XXX-XX-1234'
                  or 'SSN last four: 1234'), return only the exposed four digits as matchedText.
                  """
                : """
                  Do NOT return last-four-only or otherwise partially masked SSN values; this tenant has
                  disabled masking of exposed SSN last-four representations.
                  """;
        return """
                SSN
                - Requires an actual exposed numeric Social Security Number value.
                - A clearly labeled synthetic/test SSN is still sensitive even when the numeric
                  range is intentionally non-assignable; do not use issuance/allocation rules to
                  suppress an exposed value that the document explicitly labels as an SSN.
                - The words 'SSN' or 'Social Security number' by themselves, including privacy-policy
                  text describing data categories, are NOT findings.
                - A Form Number, Reference Number, Contract Number, or other business identifier is
                  NOT an SSN merely because it has a 3-2-4 shape. If nearby text explicitly says a
                  value is not a Social Security Number, do not return it.
                - Never treat an unrelated four-digit number as SSN without clear SSN context.
                %s
                """.formatted(partial.trim());
    }

    private String dobInstructions() {
        return """
                DATE_OF_BIRTH
                - Means an actual person's birth date.
                - Do NOT classify ordinary dates such as sale dates, contract dates, signature dates,
                  report dates, payment/due dates, effective dates, expiration dates, servicing dates,
                  form revision dates, or other transaction dates as DOB.
                - matchedText must be the birth-date VALUE itself. A person's name can never be the
                  matchedText for DATE_OF_BIRTH.
                - A label ('Date of Birth', 'DOB', 'B'date', 'Fecha de nacimiento', 'born on') makes it
                  certain, but a missing label never rules a date out: a date that may be a person's
                  birth date and that the page does not clearly give another meaning is returned with
                  confidence 0.6, so a person decides.
                """;
    }

    private String creditCardInstructions() {
        return """
                CREDIT_CARD_NUMBER
                - Requires an actual exposed payment-card PAN value. Return only the PAN itself.
                - A plausible candidate contains 13 to 19 digits and may use only spaces or hyphens
                  between digits.
                - Do NOT classify contract numbers, member/account numbers, loan numbers, routing
                  numbers, phone numbers, dollar amounts, APRs, payment counts, dates, addresses,
                  barcode/control-number markers (including repeated-digit strings), blank card fields,
                  or the words 'credit card'/'debit card' as CREDIT_CARD_NUMBER.
                - Do not identify a long digit string as a card merely because its length resembles a
                  PAN; document context must actually indicate a payment-card number.
                - V1 default: do not return masked/last-four-only card displays when no full PAN is exposed.
                """;
    }

    private String creditScoreInstructions() {
        return """
                CREDIT_SCORE
                - Requires an actual consumer credit score tied by context to a person/credit report.
                - In a table, a column header such as 'Your credit score' semantically applies to
                  the score cells in the rows below. Return each actual score value even when the
                  words 'credit score' are not repeated in every row.
                - A bare three-digit number is not necessarily a credit score; for example 'FICO score:
                  720' is a credit score while 'Invoice quantity: 720 units' is not.
                - Do not return score-range examples or explanatory numbers (for example the generic
                  300-to-850 range) unless the number is separately shown as the actual customer's score.
                """;
    }

    /** Task 0103: the opt-in kinds and the card codes, one line each. */
    private static List<String> otherKinds(Set<EntityType> kinds) {
        List<String> categories = new ArrayList<>();
        if (kinds.contains(EntityType.BANK_ROUTING_NUMBER)) {
            categories.add("BANK_ROUTING_NUMBER: a 9-digit bank routing/ABA/transit number, with or without a label (Routing, RTN, Bnk Rte, Numero de ruta), also on a check's bottom line.");
        }
        if (kinds.contains(EntityType.BANK_ACCOUNT_NUMBER)) {
            categories.add("BANK_ACCOUNT_NUMBER: a bank account number (Account, Acct., A/C, Checking, Numero de cuenta), also on a check's bottom line; not loan, order, invoice or policy numbers.");
        }
        if (kinds.contains(EntityType.ITIN)) {
            categories.add("ITIN: a 9-digit individual taxpayer identification number starting with 9 (9NN-NN-NNNN).");
        }
        if (kinds.contains(EntityType.EIN)) {
            categories.add("EIN: a 9-digit employer identification number (NN-NNNNNNN; EIN, FEIN, Federal Tax ID, Fed. Employer No.).");
        }
        if (kinds.contains(EntityType.DRIVERS_LICENSE_NUMBER)) {
            categories.add("DRIVERS_LICENSE_NUMBER: a driver's licence number (DL, Drv. Lic., Licencia de conducir); not business, contractor or marriage licences or plates.");
        }
        if (kinds.contains(EntityType.PASSPORT_NUMBER)) {
            categories.add("PASSPORT_NUMBER: a passport number (Passport, PP No., Pasaporte).");
        }
        if (kinds.contains(EntityType.CVV)) {
            categories.add("CVV: a payment card's 3 or 4 digit security code (CVV, CVC, CSC, CID, security code, Codigo de seguridad), also in a card table or next to a card number without a label.");
        }
        if (kinds.contains(EntityType.PIN)) {
            categories.add("PIN: a 4 to 6 digit personal identification number (PIN, Pin #, NIP, clave), also next to a card number.");
        }
        return categories;
    }
}
