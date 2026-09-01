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
                - The words 'SSN' or 'Social Security number' by themselves, including privacy-policy
                  text describing data categories, are NOT findings.
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
                """;
    }

    private String creditScoreInstructions() {
        return """
                CREDIT_SCORE
                - Requires an actual consumer credit score tied by context to a person/credit report.
                - A bare three-digit number is not necessarily a credit score; for example 'FICO score:
                  720' is a credit score while 'Invoice quantity: 720 units' is not.
                - Do not return score-range examples or explanatory numbers unless they are the actual
                  customer's score.
                """;
    }
}
