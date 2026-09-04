package com.securedoc.platform.ai.analysis;

import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy;
import com.securedoc.platform.domain.finding.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Provider-neutral prompt composition from tenant redaction policy.
 *
 * <p>Provider adapters are responsible only for transport/model invocation. They do not own
 * customer category selection or category semantics.
 */
public final class SensitiveContentPromptBuilder {

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
        if (policy.enabledEntityTypes().contains(EntityType.SSN)) {
            categories.add(policy.maskSsnLastFour()
                    ? "SSN: exposed SSN; last-4 only when explicitly labeled SSN. Protect clearly labeled synthetic/test SSNs even when the digits are non-assignable. Reject Form/Reference/Contract numbers and phrases explicitly saying a value is not an SSN. Labels alone are not findings."
                    : "SSN: exposed full SSN only; protect clearly labeled synthetic/test SSNs even when the digits are non-assignable. Reject Form/Reference/Contract numbers and phrases explicitly saying a value is not an SSN. Do not return last-4/partially masked values or labels alone.");
        }
        if (policy.enabledEntityTypes().contains(EntityType.DATE_OF_BIRTH)) {
            categories.add("DATE_OF_BIRTH: actual person's birth date only; matchedText must be the date value, never a person's name; exclude contract, sale, signature, report, payment, effective, expiration, servicing and form dates.");
        }
        if (policy.enabledEntityTypes().contains(EntityType.CREDIT_CARD_NUMBER)) {
            categories.add("CREDIT_CARD_NUMBER: actual full payment-card PAN only (13-19 digits, optional spaces/hyphens); omit masked/last-four-only card displays; exclude account/loan/contract/routing/phone numbers, amounts, dates and barcode/control markers.");
        }
        if (policy.enabledEntityTypes().contains(EntityType.CREDIT_SCORE)) {
            categories.add("CREDIT_SCORE: actual consumer credit/FICO score tied to a person/report. In tables, a 'Your credit score' column header applies to the score values in the rows below even when the label is not repeated. Exclude bare 3-digit numbers, explanatory ranges such as 300-850, and examples.");
        }

        String requested = categories.isEmpty() ? "NONE" : String.join("\n", categories);
        return """
                Extract ONLY tenant-requested sensitive values from each supplied page. Use labels and
                surrounding meaning; never invent text. matchedText must be the exact value span present
                on its declared page, not the label. contextText must be a short exact quote containing
                matchedText. Labels, policy/privacy language, category names, and examples are not
                sensitive values. If a category is merely discussed or no requested value is exposed, omit it.

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
                - Return DATE_OF_BIRTH only when the page meaning clearly ties that date to a person's
                  birth (for example 'Date of Birth', 'DOB', 'Birth Date', or a sentence saying the
                  person was born).
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
}
