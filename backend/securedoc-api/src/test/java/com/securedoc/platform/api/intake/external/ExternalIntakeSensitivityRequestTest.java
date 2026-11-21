package com.securedoc.platform.api.intake.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.intake.external.ExternalIntakeSnapshot;
import com.securedoc.platform.application.intake.external.IntakeWarning;
import com.securedoc.platform.application.intake.external.PushIntakeCreateInput;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.intake.ExternalIntakeStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0021: the push and scanner create request take optional levels as sent, and never fail on them. */
class ExternalIntakeSensitivityRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private PushIntakeCreateInput parse(String caseLevel, String documentLevel) throws Exception {
        String json = "{\"externalSourcePackageId\":\"S-1\",\"sourceRevision\":\"1\","
                + (caseLevel == null ? "" : "\"caseSensitivityLevel\":" + caseLevel + ",")
                + "\"documents\":[{\"externalSourceDocumentId\":\"D-1\",\"fileName\":\"a.pdf\""
                + (documentLevel == null ? "" : ",\"sensitivityLevel\":" + documentLevel) + "}]}";
        return mapper.readValue(json, ExternalIntakeCreateRequest.class).toInput();
    }

    @Test
    void levelsArePassedOnAsSent() throws Exception {
        PushIntakeCreateInput input = parse("\"CONFIDENTIAL\"", "\"HIGHLY_CONFIDENTIAL\"");
        assertEquals("CONFIDENTIAL", input.caseSensitivityLevel());
        assertEquals("HIGHLY_CONFIDENTIAL", input.documents().get(0).sensitivityLevel());
    }

    @Test
    void absentOrNullIsNothingSent() throws Exception {
        PushIntakeCreateInput absent = parse(null, null);
        assertNull(absent.caseSensitivityLevel());
        assertNull(absent.documents().get(0).sensitivityLevel());
        PushIntakeCreateInput nulls = parse("null", "null");
        assertNull(nulls.caseSensitivityLevel());
        assertNull(nulls.documents().get(0).sensitivityLevel());
    }

    @Test
    void nonTextValuesDoNotFailTheRequest() throws Exception {
        PushIntakeCreateInput input = parse("1", "{\"x\":true}");
        assertEquals("1", input.caseSensitivityLevel());
        assertEquals("{\"x\":true}", input.documents().get(0).sensitivityLevel());
    }

    @Test
    void theResponseListsTheWarnings() {
        Instant now = Instant.now();
        ExternalIntakeSnapshot snapshot = new ExternalIntakeSnapshot(UUID.randomUUID(), "S-1", "1", 1,
                ExternalIntakeStatus.AWAITING_DOCUMENTS, PackageStatus.CREATED, 1, 0, List.of("D-1"), now, now, null, null,
                List.of(new IntakeWarning("LV-INT-005", "Unknown sensitivity level, taken as Standard", "DOCUMENT", "D-1")));
        ExternalIntakeResponse response = ExternalIntakeResponse.from(snapshot);
        assertEquals(1, response.warnings().size());
        assertEquals("LV-INT-005", response.warnings().get(0).code());
        assertEquals("D-1", response.warnings().get(0).externalSourceDocumentId());
        assertTrue(ExternalIntakeResponse.from(new ExternalIntakeSnapshot(UUID.randomUUID(), "S-1", "1", 1,
                ExternalIntakeStatus.AWAITING_DOCUMENTS, PackageStatus.CREATED, 1, 0, List.of(), now, now, null, null))
                .warnings().isEmpty());
    }
}
