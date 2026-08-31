package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.command.ResolveReviewItemCommand;
import com.securedoc.platform.application.review.result.ResolveReviewItemResult;

public interface ResolveReviewItemUseCase {
    ResolveReviewItemResult execute(ResolveReviewItemCommand command);
}
