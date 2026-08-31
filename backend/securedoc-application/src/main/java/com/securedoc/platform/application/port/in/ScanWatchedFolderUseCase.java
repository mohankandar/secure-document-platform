package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.watched.ScanWatchedFolderResult;
import com.securedoc.platform.domain.identity.CorrelationId;

public interface ScanWatchedFolderUseCase {
    ScanWatchedFolderResult scan(AuthenticatedPrincipal principal, CorrelationId correlationId);
}
