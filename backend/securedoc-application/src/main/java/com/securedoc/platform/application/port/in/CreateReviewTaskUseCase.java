package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.command.CreateReviewTaskCommand;
import com.securedoc.platform.application.review.result.ReviewTaskResult;

/**
 * Inbound port for creating a review task for a package.
 */
public interface CreateReviewTaskUseCase {
    ReviewTaskResult execute(CreateReviewTaskCommand command);
}
