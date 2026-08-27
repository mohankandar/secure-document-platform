package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.command.AssignReviewTaskCommand;
import com.securedoc.platform.application.review.result.ReviewTaskResult;

public interface AssignReviewTaskUseCase {
    ReviewTaskResult execute(AssignReviewTaskCommand command);
}
