package com.securedoc.platform.ai.analysis;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy;
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
        return parse(content, List.of(page), provider, model, analyzerName, analyzerVersion);
    }

    List<DiscoveredSensitiveContent> parse(String content,
                                           List<PageContent> pages,
                                           String provider,
                                           String model,
                                           String analyzerName,
                                           String analyzerVersion) throws IOException {
        JsonNode root = readTreeWithoutEcho(objectMapper, content, "Invalid findings payload");
        JsonNode findings = root.path("findings");
        if (!findings.isArray()) {
            throw new IOException("Invalid findings payload");
        }

        java.util.Map<Integer, PageContent> pagesByNumber = new java.util.LinkedHashMap<>();
        for (PageContent page : pages) {
            pagesByNumber.put(page.pageNumber(), page);
        }

        List<DiscoveredSensitiveContent> result = new ArrayList<>();
        // Task 0023 C7 (#131): count each reason a model finding is dropped, so a silent loss
        // (for example an OCR whitespace mismatch) is visible. Counts only: the answer text, the
        // matched text and the unknown type string can all hold document content.
        int pageNotInBatch = 0;
        int unknownType = 0;
        int blankText = 0;
        int confidenceOutOfRange = 0;
        int textNotOnPage = 0;
        for (JsonNode finding : findings) {
            int pageNumber = JsonNodeValues.asInt(finding.path("pageNumber"), -1);
            if (pageNumber < 1 && pages.size() == 1) {
                pageNumber = pages.getFirst().pageNumber();
            }
            PageContent page = pagesByNumber.get(pageNumber);
            if (page == null) {
                pageNotInBatch++;
                continue;
            }

            String type = normalizeEntityType(JsonNodeValues.asText(finding.path("entityType"), ""));
            String matchedText = JsonNodeValues.asText(finding.path("matchedText"), "");
            String contextText = JsonNodeValues.asText(finding.path("contextText"), "");
            double confidence = JsonNodeValues.asDouble(finding.path("confidence"), -1.0);
            if (type.isBlank()) {
                unknownType++;
                continue;
            }
            if (matchedText.isBlank()) {
                blankText++;
                continue;
            }
            if (confidence < 0.0 || confidence > 1.0) {
                confidenceOutOfRange++;
                continue;
            }
            // Fail closed against model fabrication: only accept exact text present on its declared page.
            if (!page.text().contains(matchedText)) {
                textNotOnPage++;
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
        int dropped = pageNotInBatch + unknownType + blankText + confidenceOutOfRange + textNotOnPage;
        if (dropped > 0) {
            log.info("AI_FINDINGS_DROPPED provider={} model={} pages={} received={} accepted={} pageNotInBatch={} unknownType={} blankText={} confidenceOutOfRange={} textNotOnPage={}",
                    provider, model, pages.size(), findings.size(), result.size(),
                    pageNotInBatch, unknownType, blankText, confidenceOutOfRange, textNotOnPage);
        }
        return result;
    }

    /** Task 0083: the kinds the second check's text prompt asks for; a face is never in text. */
    static final List<String> SECOND_CHECK_KINDS = List.of("SSN", "DATE_OF_BIRTH", "CREDIT_CARD_NUMBER", "CREDIT_SCORE",
            "BANK_ACCOUNT", "ROUTING_NUMBER", "GOVERNMENT_ID");

    /** Task 0083: the second check's answer for one page; only text that stands on the page counts. */
    List<DiscoveredSensitiveContent> parseSecondCheck(String content, PageContent page, String analyzerName)
            throws IOException {
        JsonNode found = readTreeWithoutEcho(objectMapper, content, "Invalid second check payload").path("found");
        if (!found.isArray()) throw new IOException("Invalid second check payload");
        List<DiscoveredSensitiveContent> result = new ArrayList<>();
        for (JsonNode item : found) {
            String kind = JsonNodeValues.asText(item.path("kind"), "");
            String value = JsonNodeValues.asText(item.path("value"), "");
            if (!SECOND_CHECK_KINDS.contains(kind) || value.isBlank() || !page.text().contains(value)) continue;
            result.add(new DiscoveredSensitiveContent(kind, page.pageNumber(), value, "", 0.0, analyzerName,
                    SensitiveContentPromptBuilder.SECOND_CHECK_VERSION));
        }
        return result;
    }

    private static final int SUMMARY_PEOPLE = 60;
    static final List<String> NAME_USES = List.of("person", "thing_or_word", "place_or_street", "season_or_date",
            "name_of_something_else");

    /** Task 0065: a document's people. A person or other name that does not stand in the text is dropped. */
    List<DiscoveredSensitiveContent> parseSummary(String content, PageContent page, String analyzerName) throws IOException {
        JsonNode people = readTreeWithoutEcho(objectMapper, content, "Invalid summary payload").path("people");
        if (!people.isArray()) throw new IOException("Invalid summary payload");
        String text = comparable(page.text());
        List<DiscoveredSensitiveContent> result = new ArrayList<>();
        int taken = 0;
        for (JsonNode person : people) {
            if (taken == SUMMARY_PEOPLE) break;
            taken++;
            summaryItem(result, page, text, AnalysisPolicy.SUMMARY_PERSON,
                    JsonNodeValues.asText(person.path("name"), ""), analyzerName);
            for (JsonNode other : person.path("alsoCalled")) {
                summaryItem(result, page, text, AnalysisPolicy.SUMMARY_ALSO_CALLED, JsonNodeValues.asText(other, ""),
                        analyzerName);
            }
        }
        return result;
    }

    private static void summaryItem(List<DiscoveredSensitiveContent> into, PageContent page, String text, String kind,
                                    String value, String analyzerName) {
        if (value.isBlank() || !text.contains(comparable(value))) return;
        into.add(new DiscoveredSensitiveContent(kind, page.pageNumber(), value, "", 0.0, analyzerName,
                SensitiveContentPromptBuilder.SUMMARY_VERSION));
    }

    private static String comparable(String text) {
        return text.replaceAll("\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** Task 0065: the sentences in which the word names a person. One without a usable answer is taken as a person. */
    List<DiscoveredSensitiveContent> parseKnownNames(String content, List<PageContent> sentences, String analyzerName)
            throws IOException {
        JsonNode answers = readTreeWithoutEcho(objectMapper, content, "Invalid known names payload").path("answers");
        if (!answers.isArray()) throw new IOException("Invalid known names payload");
        java.util.Set<Integer> other = new java.util.HashSet<>();
        java.util.Set<Integer> person = new java.util.HashSet<>();
        for (JsonNode answer : answers) {
            String use = JsonNodeValues.asText(answer.path("is"), "");
            int number = JsonNodeValues.asInt(answer.path("number"), -1);
            if (!NAME_USES.contains(use)) continue;
            ("person".equals(use) ? person : other).add(number);
        }
        List<DiscoveredSensitiveContent> result = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            if (other.contains(i + 1) && !person.contains(i + 1)) continue;
            result.add(new DiscoveredSensitiveContent("PERSON_NAME", sentences.get(i).pageNumber(), "person", "", 0.7,
                    analyzerName, SensitiveContentPromptBuilder.KNOWN_NAMES_VERSION));
        }
        return result;
    }

    /** What the model is sent: the sentences, numbered from 1. */
    static String knownNames(List<PageContent> sentences) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) out.append(i + 1).append(". ").append(sentences.get(i).text()).append('\n');
        return out.toString();
    }

    /**
     * Parses AI provider JSON without letting the answer's text travel on in an exception.
     *
     * <p>Jackson's parse errors quote the offending token (for example
     * {@code Unrecognized token '123-45-6789'}) and, when source-in-location is on, part of the
     * input. The answer can hold found values, so the Jackson exception is dropped here and a
     * fixed message with no cause is thrown instead (task 0015). Source-in-location is also
     * switched off on the reader, in case a caller passes a mapper that has it on.
     */
    static JsonNode readTreeWithoutEcho(ObjectMapper objectMapper, String json, String fixedMessage)
            throws IOException {
        if (json == null) {
            throw new IOException(fixedMessage);
        }
        final JsonNode node;
        try {
            node = objectMapper.reader().without(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).readTree(json);
        } catch (RuntimeException parseFailure) {
            throw new IOException(fixedMessage);
        }
        // Jackson 3 gives a missing node, not null, for text with no JSON value in it.
        if (node == null || node.isMissingNode()) {
            throw new IOException(fixedMessage);
        }
        return node;
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
            case "PERSON_NAME", "PERSON" -> "PERSON_NAME";
            // Task 0103: the AI reads for every enabled and locked kind.
            case "BANK_ROUTING_NUMBER", "ROUTING_NUMBER", "ROUTING" -> "BANK_ROUTING_NUMBER";
            case "BANK_ACCOUNT_NUMBER", "BANK_ACCOUNT", "ACCOUNT_NUMBER" -> "BANK_ACCOUNT_NUMBER";
            case "ITIN" -> "ITIN";
            case "EIN" -> "EIN";
            case "DRIVERS_LICENSE_NUMBER", "DRIVERS_LICENSE" -> "DRIVERS_LICENSE_NUMBER";
            case "PASSPORT_NUMBER", "PASSPORT" -> "PASSPORT_NUMBER";
            case "CVV" -> "CVV";
            case "PIN" -> "PIN";
            default -> "";
        };
    }
}
