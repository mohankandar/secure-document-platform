package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.watched.IntakeObservation;
import java.util.List;

public interface ListIntakeObservationsQuery {
    List<IntakeObservation> listVisible(AuthenticatedPrincipal principal, int limit);
}
