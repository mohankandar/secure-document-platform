package com.securedoc.platform.infrastructure.detection;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local deterministic sensitive-data detector using regex rules.
 * Detects SSN, email, phone number, and date-of-birth patterns.
 *
 * <p>Rules:
 * <ul>
 *   <li>Never stores or logs raw matched values</li>
 *   <li>Returns only masked previews (e.g., "***-**-1234")</li>
 *   <li>Confidence is rule-based (high for clear patterns)</li>
 * </ul>
 *
 * <p>This is a foundation detector for local/demo use.
 * Cloud AI/NLP detection can be added via a separate adapter in future units.
 */
@Component
public class LocalRuleBasedDetectionAdapter implements SensitiveDataDetectionPort {

    private static final String DETECTOR_NAME = "local-rule-based";
    private static final String DETECTOR_VERSION = "1.0.0";

    // SSN pattern: 3 digits, separator, 2 digits, separator, 4 digits
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b(\\d{3})[- ](\\d{2})[- ](\\d{4})\\b");

    // Email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    // Phone number pattern: various US formats
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\b(?:\\+1[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}\\b");

    // Date of birth pattern: MM/DD/YYYY or MM-DD-YYYY
    private static final Pattern DOB_PATTERN = Pattern.compile(
            "\\b(0[1-9]|1[0-2])[/-](0[1-9]|[12]\\d|3[01])[/-](19|20)\\d{2}\\b");

    @Override
    public DetectionOutput detect(List<PageInput> pages) {
        List<DetectedEntity> entities = new ArrayList<>();

        for (PageInput page : pages) {
            if (page.text().isBlank()) {
                continue;
            }
            detectInPage(page, entities);
        }

        return new DetectionOutput(entities);
    }

    private void detectInPage(PageInput page, List<DetectedEntity> results) {
        String text = page.text();

        // SSN detection
        detectPattern(text, SSN_PATTERN, "SSN", 0.95, page.pageNumber(), results,
                this::maskSsn);

        // Email detection
        detectPattern(text, EMAIL_PATTERN, "EMAIL_ADDRESS", 0.90, page.pageNumber(), results,
                this::maskEmail);

        // Phone detection
        detectPattern(text, PHONE_PATTERN, "PHONE_NUMBER", 0.85, page.pageNumber(), results,
                this::maskPhone);

        // Date of birth detection (lower confidence — could be any date)
        detectPattern(text, DOB_PATTERN, "DATE_OF_BIRTH", 0.70, page.pageNumber(), results,
                this::maskDob);
    }

    private void detectPattern(String text, Pattern pattern, String entityType, double confidence,
                                int pageNumber, List<DetectedEntity> results,
                                MaskFunction maskFunction) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String rawMatch = matcher.group();
            String masked = maskFunction.mask(rawMatch);

            results.add(new DetectedEntity(
                    entityType,
                    pageNumber,
                    matcher.start(),
                    matcher.end(),
                    confidence,
                    masked,
                    DETECTOR_NAME,
                    DETECTOR_VERSION
            ));
        }
    }

    // --- Masking functions (never expose raw values) ---

    private String maskSsn(String raw) {
        // "123-45-6789" → "***-**-6789"
        if (raw.length() >= 4) {
            String lastFour = raw.substring(raw.length() - 4);
            return "***-**-" + lastFour;
        }
        return "***-**-****";
    }

    private String maskEmail(String raw) {
        // "john.doe@example.com" → "j***@e***.com"
        int atIndex = raw.indexOf('@');
        if (atIndex > 0) {
            String localPart = raw.substring(0, atIndex);
            String domainPart = raw.substring(atIndex + 1);
            String maskedLocal = localPart.charAt(0) + "***";
            int dotIndex = domainPart.lastIndexOf('.');
            if (dotIndex > 0) {
                String maskedDomain = domainPart.charAt(0) + "***" + domainPart.substring(dotIndex);
                return maskedLocal + "@" + maskedDomain;
            }
            return maskedLocal + "@***";
        }
        return "***@***";
    }

    private String maskPhone(String raw) {
        // Show last 4 digits only
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() >= 4) {
            return "***-***-" + digits.substring(digits.length() - 4);
        }
        return "***-***-****";
    }

    private String maskDob(String raw) {
        // "01/15/1990" → "**/**/1990"
        if (raw.length() >= 4) {
            String year = raw.substring(raw.length() - 4);
            return "**/**/" + year;
        }
        return "**/**/****";
    }

    @FunctionalInterface
    private interface MaskFunction {
        String mask(String raw);
    }
}
