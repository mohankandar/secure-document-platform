package com.securedoc.platform.api.observability;

import com.securedoc.platform.application.observability.StructuredLogContext;
import org.springframework.core.task.TaskDecorator;

/** Propagates only the privacy-safe MDC allow-list to Spring-managed background tasks. */
public final class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        return StructuredLogContext.wrap(runnable);
    }
}
