package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.command.UpdateFindingBoundingBoxCommand;

/**
 * Inbound port for updating a finding's bounding box during review.
 */
public interface UpdateFindingBoundingBoxUseCase {
    void execute(UpdateFindingBoundingBoxCommand command);
}
