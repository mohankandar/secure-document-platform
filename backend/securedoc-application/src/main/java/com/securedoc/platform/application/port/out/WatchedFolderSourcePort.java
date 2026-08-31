package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.intake.watched.*;
import java.util.List;

public interface WatchedFolderSourcePort {
    List<WatchedFolderCandidate> discover();
    void applyDisposition(String sourceReference, IntakeDisposition disposition);
}
