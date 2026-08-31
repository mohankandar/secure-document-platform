package com.securedoc.platform.api.policy;

import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.PageInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DobContextPrecisionTest {

    private final LocalRuleBasedDetectionAdapter detector = new LocalRuleBasedDetectionAdapter();

    @Test
    void ordinaryTransactionDatesAreNotDiscoveredAsDob() {
        var output = detector.detect(List.of(new PageInput(1,
                "Date of Sale: 04-16-2026 Payment Date: 05-31-2026 Date Signed: 04-16-2026")));

        assertThat(output.entities())
                .noneMatch(entity -> entity.entityType().equals("DATE_OF_BIRTH"));
    }

    @Test
    void explicitlyLabeledDobIsDiscoveredAndOnlyValueSpanIsReturned() {
        String text = "Customer Date of Birth: 01/05/1980";
        var output = detector.detect(List.of(new PageInput(1, text)));

        var dob = output.entities().stream()
                .filter(entity -> entity.entityType().equals("DATE_OF_BIRTH"))
                .findFirst()
                .orElseThrow();

        assertThat(text.substring(dob.startOffset(), dob.endOffset())).isEqualTo("01/05/1980");
    }
}
