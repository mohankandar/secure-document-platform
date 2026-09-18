package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.intake.watched.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WatchedFolderSourcePort {
    List<WatchedFolderCandidate> discover();

    /**
     * Atomically claims a triggered candidate for one node/fencing token and returns a candidate
     * whose document streams resolve from the claimed location. Empty means another node/process
     * already owns the source reference. claimedAt must come from the shared coordination clock
     * when distributed coordination is enabled.
     */
    Optional<WatchedFolderCandidate> claim(String sourceReference, WatchedFolderScanLease lease, Instant claimedAt);

    /**
     * Returns stale claims from an older fencing generation to the incoming root. This is used only
     * by the current lease owner and never overwrites a newly staged candidate. currentTime must be
     * sourced from the shared coordination clock when distributed coordination is enabled.
     */
    int recoverStaleClaims(WatchedFolderScanLease lease, Duration staleAfter, Instant currentTime);

    /** Applies a terminal source disposition only when the current claim still matches the caller's fencing context. */
    void applyDisposition(String sourceReference, IntakeDisposition disposition, WatchedFolderScanLease lease);
}
