package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.command.CompleteReviewTaskCommand;
import com.securedoc.platform.application.review.result.ReviewTaskResult;

public interface CompleteReviewTaskUseCase {
    ReviewTaskResult execute(CompleteReviewTaskCommand command);
}
