package com.securedoc.platform.ai.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.DiscoveredSensitiveContent;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Provider-neutral parsing/guardrails for structured semantic findings. */
final class SensitiveContentResponseParser {

    private static final Logger log = LoggerFactory.getLogger(SensitiveContentResponseParser.class);
    private final ObjectMapper objectMapper;

    SensitiveContentResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<DiscoveredSensitiveContent> parse(String content,
                                           PageContent page,
                                           String provider,
                                           String model,
                                           String analyzerName,
                                           String analyzerVersion) throws IOException {
        JsonNode root = objectMapper.readTree(content);
        JsonNode findings = root.path("findings");
        if (!findings.isArray()) {
            throw new IOException("Invalid findings payload");
        }

        List<DiscoveredSensitiveContent> result = new ArrayList<>();
        for (JsonNode finding : findings) {
            String type = normalizeEntityType(finding.path("entityType").asText(""));
            String matchedText = finding.path("matchedText").asText("");
            String contextText = finding.path("contextText").asText("");
            double confidence = finding.path("confidence").asDouble(-1.0);
            if (type.isBlank() || matchedText.isBlank() || confidence < 0.0 || confidence > 1.0) {
                continue;
            }
            // Fail closed against model fabrication: only accept exact text present on this page.
            if (!page.text().contains(matchedText)) {
                continue;
            }
            if (!contextText.isBlank() && (!page.text().contains(contextText) || !contextText.contains(matchedText))) {
                contextText = "";
            }
            if ("CREDIT_CARD_NUMBER".equals(type) && !isPlausibleCreditCardPan(matchedText)) {
                log.info("AI_FINDING_REJECTED provider={} model={} type=CREDIT_CARD_NUMBER page={} reason=INVALID_PAN_SHAPE digitCount={}",
                        provider, model, page.pageNumber(), countDigits(matchedText));
                continue;
            }
            result.add(new DiscoveredSensitiveContent(
                    type, page.pageNumber(), matchedText, contextText, confidence,
                    analyzerName, analyzerVersion));
        }
        return result;
    }

    /**
     * Category-contract guardrail for AI card findings. This intentionally does not require
     * Luhn success: Luhn remains secondary validation evidence so an AI-only plausible PAN can
     * still reach the common finding/review pipeline.
     */
    static boolean isPlausibleCreditCardPan(String matchedText) {
        if (matchedText == null || matchedText.isBlank()) {
            return false;
        }
        String value = matchedText.trim();
        int digitCount = 0;
        boolean[] seenDigits = new boolean[10];
        int distinctDigits = 0;
        boolean previousWasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) {
                int digit = Character.digit(ch, 10);
                if (digit >= 0 && !seenDigits[digit]) {
                    seenDigits[digit] = true;
                    distinctDigits++;
                }
                digitCount++;
                previousWasDigit = true;
                continue;
            }
            boolean separator = ch == '-' || Character.isWhitespace(ch)
                    || Character.getType(ch) == Character.SPACE_SEPARATOR;
            if (!separator || !previousWasDigit || i + 1 >= value.length()
                    || !Character.isDigit(value.charAt(i + 1))) {
                return false;
            }
            previousWasDigit = false;
        }
        return digitCount >= 13 && digitCount <= 19 && distinctDigits >= 2;
    }

    static int countDigits(String value) {
        if (value == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    private String normalizeEntityType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase();
        return switch (normalized) {
            case "SSN", "SOCIAL_SECURITY_NUMBER" -> "SSN";
            case "DATE_OF_BIRTH", "DOB" -> "DATE_OF_BIRTH";
            case "CREDIT_CARD", "CREDIT_CARD_NUMBER" -> "CREDIT_CARD_NUMBER";
            case "CREDIT_SCORE", "FICO_SCORE" -> "CREDIT_SCORE";
            default -> "";
        };
    }
}
