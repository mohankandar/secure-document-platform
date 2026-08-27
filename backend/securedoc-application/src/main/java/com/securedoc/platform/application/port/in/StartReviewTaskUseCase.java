package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.command.StartReviewTaskCommand;
import com.securedoc.platform.application.review.result.ReviewTaskResult;

public interface StartReviewTaskUseCase {
    ReviewTaskResult execute(StartReviewTaskCommand command);
}
