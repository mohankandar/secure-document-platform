package com.securedoc.platform.api.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.api.controller.OperationsController;
import com.securedoc.platform.api.dto.OperationalReportsResponse;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0020 B3: the "timeSaved" block of GET /api/v1/operations/reports and who sees it. */
class TimeSavedResponseTest {

    @Test
    void theBlockIsOnTheReportsAnswerWhichNeedsTheHeadlineCapability() throws Exception {
        Method reports = Arrays.stream(OperationsController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("reports")).findFirst().orElseThrow();
        assertArrayEquals(new UserCapability[]{UserCapability.REPORT_EXECUTIVE_VIEW},
                reports.getAnnotation(RequireCapability.class).value());
        assertTrue(Arrays.stream(OperationalReportsResponse.class.getRecordComponents())
                .anyMatch(c -> c.getName().equals("timeSaved") && c.getType() == OperationalReportsResponse.TimeSaved.class));
    }

    @Test
    void theBusinessAdministratorAndTheReportViewerHoldThatCapabilityAProcessorDoesNot() {
        ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();
        assertTrue(policy.capabilitiesFor(java.util.Set.of("BUSINESS_ADMIN")).contains(UserCapability.REPORT_EXECUTIVE_VIEW));
        assertTrue(policy.capabilitiesFor(java.util.Set.of("REPORT_VIEWER")).contains(UserCapability.REPORT_EXECUTIVE_VIEW));
        assertTrue(!policy.capabilitiesFor(java.util.Set.of("DOCUMENT_PROCESSOR")).contains(UserCapability.REPORT_EXECUTIVE_VIEW));
    }

    @Test
    void everyInputIsInTheAnswerSoTheHowIsThisWorkedOutPanelShowsRealNumbers() throws Exception {
        OperationalReportsResponse.TimeSaved t = timeSaved(new OperationalReportSnapshot.TimeSaved(512, 348, 67, 14_220, 1_873,
                3_912, List.of("Social Security Number", "Date of birth"), 9_690, 1.0, 9_690));
        @SuppressWarnings("unchecked")
        Map<String, Object> json = new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(t), Map.class);
        assertEquals(List.of("casesDelivered", "casesNoPerson", "percentNoPerson", "pagesChecked", "documentsChecked",
                "itemsRedacted", "itemTypes", "pagesNoPerson", "minutesPerPage", "minutesSaved"), List.copyOf(json.keySet()));
        assertEquals(348, json.get("casesNoPerson"));
        assertEquals(67, json.get("percentNoPerson"));
        assertEquals(9_690, json.get("pagesNoPerson"));
        assertEquals(1.0, json.get("minutesPerPage"));
        assertEquals(9_690, json.get("minutesSaved"));
        assertEquals(List.of("Social Security Number", "Date of birth"), json.get("itemTypes"));
    }

    @Test
    void noDeliveredCasesGivesZerosAndANullPercent() throws Exception {
        OperationalReportsResponse.TimeSaved t = timeSaved(new OperationalReportSnapshot.TimeSaved(0, 0, null, 0, 0, 0,
                List.of(), 0, 1.0, 0));
        String json = new ObjectMapper().writeValueAsString(t);
        assertTrue(json.contains("\"percentNoPerson\":null"), json);
        assertTrue(json.contains("\"casesDelivered\":0") && json.contains("\"minutesSaved\":0"), json);
    }

    private static OperationalReportsResponse.TimeSaved timeSaved(OperationalReportSnapshot.TimeSaved t) throws Exception {
        Method m = OperationsController.class.getDeclaredMethod("timeSaved", OperationalReportSnapshot.TimeSaved.class);
        m.setAccessible(true);
        return (OperationalReportsResponse.TimeSaved) m.invoke(null, t);
    }
}
