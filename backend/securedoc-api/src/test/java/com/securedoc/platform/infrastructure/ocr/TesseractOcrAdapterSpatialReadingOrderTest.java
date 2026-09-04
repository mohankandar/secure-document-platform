package com.securedoc.platform.infrastructure.ocr;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TesseractOcrAdapterSpatialReadingOrderTest {

    @Test
    void reconstructsFormRowsAcrossTesseractBlocksSoSensitiveLabelsStayWithValues() {
        TesseractOcrAdapter adapter = new TesseractOcrAdapter(new TesseractOcrProperties());

        List<String> tsv = new ArrayList<>();
        tsv.add("level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext");

        // Tesseract commonly places all left-column labels in one block...
        addWords(tsv, 1, 1, 100, 90, "Applicant", "Name:");
        addWords(tsv, 1, 2, 130, 90, "Contract", "Number:");
        addWords(tsv, 1, 3, 160, 90, "Social", "Security", "Number:");
        addWords(tsv, 1, 4, 190, 90, "Date", "of", "Birth:");
        addWords(tsv, 1, 5, 220, 90, "Credit", "Card", "Number:");
        addWords(tsv, 1, 6, 250, 90, "Credit", "Score:");
        addWords(tsv, 1, 7, 280, 90, "Issue", "Date:");
        addWords(tsv, 1, 8, 310, 90, "Reference", "Number:");

        // ...and all right-column values in a later block even though they share visual rows.
        addWords(tsv, 2, 1, 100, 430, "Casey", "Rowan");
        addWords(tsv, 2, 2, 130, 430, "00042-2601234");
        addWords(tsv, 2, 3, 160, 430, "987-65-4323");
        addWords(tsv, 2, 4, 190, 430, "02/21/1974");
        addWords(tsv, 2, 5, 220, 430, "6011", "111111111117");
        addWords(tsv, 2, 6, 250, 430, "768");
        addWords(tsv, 2, 7, 280, 430, "04/19/2026");
        addWords(tsv, 2, 8, 310, 430, "402199001177");

        var output = adapter.parseTsv(tsv, 1000, 1400);

        assertTrue(output.text().contains("Social Security Number: 987-65-4323"));
        assertTrue(output.text().contains("Date of Birth: 02/21/1974"));
        assertTrue(output.text().contains("Credit Card Number: 6011 111111111117"));
        assertTrue(output.text().contains("Credit Score: 768"));
        assertTrue(output.text().contains("Reference Number: 402199001177"));

        var detected = new LocalRuleBasedDetectionAdapter()
                .detect(List.of(new PageInput(15, output.text())));
        Set<String> types = detected.entities().stream()
                .map(entity -> entity.entityType())
                .collect(Collectors.toSet());

        assertEquals(Set.of("SSN", "DATE_OF_BIRTH", "CREDIT_CARD_NUMBER", "CREDIT_SCORE"), types);
    }

    private static void addWords(List<String> tsv,
                                 int block,
                                 int line,
                                 int top,
                                 int startLeft,
                                 String... words) {
        int left = startLeft;
        for (int i = 0; i < words.length; i++) {
            String text = words[i];
            int width = Math.max(30, text.length() * 9);
            tsv.add(String.join("\t",
                    "5", "1", Integer.toString(block), "1", Integer.toString(line), Integer.toString(i + 1),
                    Integer.toString(left), Integer.toString(top), Integer.toString(width), "18", "96.0", text));
            left += width + 10;
        }
    }
}
