package com.securedoc.platform.domain.error;

/**
 * Task 0025: a failure that knows its LexaVault error code. The mapper ({@code LvErrorCodes} in the application
 * module) uses this code first; a failure that returns {@code null} is mapped by its type and internal identifier.
 */
public interface LvCoded {

    /** The LexaVault code for this failure, or {@code null} when the failure did not say. */
    LvErrorCode lvCode();
}
