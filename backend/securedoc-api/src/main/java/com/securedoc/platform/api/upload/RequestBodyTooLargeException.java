package com.securedoc.platform.api.upload;

import java.io.IOException;

/** Task 0055: a request body (not a file) is larger than the limit. LV-SYS-015. */
public final class RequestBodyTooLargeException extends IOException {
    private final long limit;

    public RequestBodyTooLargeException(long limit) {
        super("The request is larger than the limit");
        this.limit = limit;
    }

    public long limit() {
        return limit;
    }
}
