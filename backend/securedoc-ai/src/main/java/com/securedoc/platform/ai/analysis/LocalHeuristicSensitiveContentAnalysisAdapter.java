package com.securedoc.platform.ai.analysis;

import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;

import com.securedoc.platform.domain.finding.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Development fallback that exercises the AI-first application path without pretending
 * to be a language model. It independently scans every page and emits semantic discoveries
 * from full-page context. Production/V1 AI acceptance should use a real local model adapter
 * such as {@link OllamaSensitiveContentAnalysisAdapter}.
 */
public final class LocalHeuristicSensitiveContentAnalysisAdapter implements SensitiveContentAnalysisPort {

    private static final String NAME = "local-semantic-heuristic";
    private static final String VERSION = "1.0.0";

    private static final Pattern LABELED_SSN = Pattern.compile(
            "(?i)(?:social\\s+security(?:\\s+number)?|ssn)\\s*[:#-]?\\s*(\\d{3}[- ]?\\d{2}[- ]?\\d{4})");
    private static final Pattern LABELED_DOB = Pattern.compile(
            "(?i)(?:date\\s+of\\s+birth|dob|born)\\s*[:#-]?\\s*((?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12]\\d|3[01])[/-](?:19|20)\\d{2})");
    private static final Pattern LABELED_CARD = Pattern.compile(
            "(?i)(?:credit\\s+card(?:\\s+number)?|card\\s+number)\\s*[:#-]?\\s*((?:\\d[ -]?){13,19})");
    private static final Pattern LABELED_SCORE = Pattern.compile(
            "(?i)(?:credit\\s+score|fico(?:\\s+score)?|vantage(?:score)?(?:\\s+score)?)\\s*[:#-]?\\s*(\\d{3})");

    @Override
    public AnalysisOutput analyze(AnalysisRequest request) {
        List<DiscoveredSensitiveContent> findings = new ArrayList<>();
        Set<EntityType> enabled = request.policy().enabledEntityTypes();
        for (PageContent page : request.pages()) {
            String text = page.text();
            if (text.isBlank()) {
                continue;
            }
            if (enabled.contains(EntityType.SSN)) {
                collect(text, page.pageNumber(), LABELED_SSN, "SSN", 0.97, findings, false);
            }
            if (enabled.contains(EntityType.DATE_OF_BIRTH)) {
                collect(text, page.pageNumber(), LABELED_DOB, "DATE_OF_BIRTH", 0.94, findings, false);
            }
            if (enabled.contains(EntityType.CREDIT_CARD_NUMBER)) {
                collect(text, page.pageNumber(), LABELED_CARD, "CREDIT_CARD_NUMBER", 0.94, findings, true);
            }
            if (enabled.contains(EntityType.CREDIT_SCORE)) {
                collectScores(text, page.pageNumber(), findings);
            }
        }
        return new AnalysisOutput(findings);
    }

    private void collect(String text, int pageNumber, Pattern pattern, String type, double confidence,
                         List<DiscoveredSensitiveContent> findings, boolean card) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String matched = matcher.group(1).trim();
            if (card) {
                String digits = matched.replaceAll("[^0-9]", "");
                if (digits.length() < 13 || digits.length() > 19) {
                    continue;
                }
            }
            findings.add(new DiscoveredSensitiveContent(
                    type, pageNumber, matched, matcher.group(), confidence, NAME, VERSION));
        }
    }

    private void collectScores(String text, int pageNumber, List<DiscoveredSensitiveContent> findings) {
        Matcher matcher = LABELED_SCORE.matcher(text);
        while (matcher.find()) {
            int score;
            try {
                score = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (score >= 300 && score <= 850) {
                findings.add(new DiscoveredSensitiveContent(
                        "CREDIT_SCORE", pageNumber, matcher.group(1), matcher.group(), 0.96, NAME, VERSION));
            }
        }
    }
}
