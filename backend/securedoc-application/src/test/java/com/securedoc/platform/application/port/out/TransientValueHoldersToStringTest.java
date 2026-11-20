package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.DiscoveredSensitiveContent;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.PageContent;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.Candidate;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Task 0015, A4: records that carry found or page text in memory print no part of it. */
class TransientValueHoldersToStringTest {

    private static final String VALUE = "412-55-7531";
    private static final String PAGE = "Borrower SSN: " + VALUE;

    @Test
    void foundValueHoldersPrintTypeAndPageOnly() {
        DiscoveredSensitiveContent discovery = new DiscoveredSensitiveContent(
                "SSN", 3, VALUE, PAGE, 0.9, "ollama:test", "1");
        assertEquals("DiscoveredSensitiveContent[entityType=SSN, pageNumber=3]", discovery.toString());

        Candidate candidate = new Candidate("SSN", VALUE, PAGE);
        assertEquals("Candidate[entityType=SSN]", candidate.toString());
    }

    @Test
    void pageTextHoldersPrintNoText() {
        List<Object> holders = List.of(
                new PageContent(2, PAGE),
                new PageInput(2, PAGE),
                new PageText(2, 612, 792, PAGE),
                new OcrPort.OcrPageOutput(PAGE, List.of(), 0.9),
                new SensitiveContentAnalysisPort.AnalysisRequest(null, List.of(new PageContent(2, PAGE)),
                        SensitiveContentAnalysisPort.AnalysisPolicy.v1Defaults()));
        for (Object holder : holders) {
            String printed = holder.toString();
            assertFalse(printed.contains("7531"), printed);
            assertFalse(printed.contains("Borrower"), printed);
        }
    }

    @Test
    void valueEqualityIsUnchanged() {
        // Only toString changed; records still compare by their content.
        assertEquals(new Candidate("SSN", VALUE, PAGE), new Candidate("SSN", VALUE, PAGE));
        assertEquals(new PageContent(1, PAGE), new PageContent(1, PAGE));
    }
}
