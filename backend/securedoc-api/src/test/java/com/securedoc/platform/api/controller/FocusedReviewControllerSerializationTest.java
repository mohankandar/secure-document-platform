package com.securedoc.platform.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.review.model.ReviewReason;
import com.securedoc.platform.application.review.result.ReviewBoundingBoxSummary;
import com.securedoc.platform.application.review.result.ReviewItemSummary;
import com.securedoc.platform.application.review.result.ReviewQueueResult;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FocusedReviewControllerSerializationTest {

    @Test
    void reviewQueueSerializesIdentityValuesAsPlainUuidStrings() throws Exception {
        UUID findingUuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID packageUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID documentUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");

        ReviewItemSummary summary = new ReviewItemSummary(
                FindingId.of(findingUuid),
                PackageId.of(packageUuid),
                DocumentId.of(documentUuid),
                "contract.pdf",
                EntityType.CREDIT_CARD_NUMBER,
                0.50,
                24,
                true,
                new ReviewBoundingBoxSummary(0.10, 0.20, 0.30, 0.04),
                ReviewReason.LOW_CONFIDENCE);

        FocusedReviewController.ReviewQueueResponse response =
                FocusedReviewController.ReviewQueueResponse.from(new ReviewQueueResult(List.of(summary), 1));

        String json = new ObjectMapper().writeValueAsString(response);

        assertThat(json).contains("\"findingId\":\"" + findingUuid + "\"");
        assertThat(json).contains("\"packageId\":\"" + packageUuid + "\"");
        assertThat(json).contains("\"documentId\":\"" + documentUuid + "\"");
        assertThat(json).doesNotContain("\"findingId\":{\"value\"");
        assertThat(json).doesNotContain("\"packageId\":{\"value\"");
        assertThat(json).doesNotContain("\"documentId\":{\"value\"");
    }
}
