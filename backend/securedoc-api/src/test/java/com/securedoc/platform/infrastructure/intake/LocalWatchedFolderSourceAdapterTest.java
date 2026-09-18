package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.intake.watched.IntakeDisposition;
import com.securedoc.platform.application.intake.watched.WatchedFolderScanLease;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LocalWatchedFolderSourceAdapterTest {

    @TempDir
    Path tempDir;

    private final WatchedFolderScanLease lease = new WatchedFolderScanLease("watched-folder-intake", "APP-01/test", 1L);
    private final Instant dbNow = Instant.parse("2026-09-09T16:00:00Z");

    @Test
    void acceptedFolderMovesToArchiveWhenConfigured() throws Exception {
        var adapter = adapter("MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        Path source = candidate("accepted-case");

        adapter.applyDisposition("accepted-case", IntakeDisposition.ACCEPTED, lease);

        assertFalse(Files.exists(source));
        assertTrue(Files.isDirectory(tempDir.resolve("_archive/accepted-case")));
    }

    @Test
    void rejectedFolderMovesToProblemWhenConfigured() throws Exception {
        var adapter = adapter("MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        Path source = candidate("rejected-case");

        adapter.applyDisposition("rejected-case", IntakeDisposition.REJECTED, lease);

        assertFalse(Files.exists(source));
        assertTrue(Files.isDirectory(tempDir.resolve("_problem/rejected-case")));
    }

    @Test
    void duplicateFolderUsesAcceptedDispositionSoSchedulerDoesNotRediscoverItForever() throws Exception {
        var adapter = adapter("MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        Path source = candidate("duplicate-case");

        adapter.applyDisposition("duplicate-case", IntakeDisposition.DUPLICATE, lease);

        assertFalse(Files.exists(source));
        assertTrue(Files.isDirectory(tempDir.resolve("_archive/duplicate-case")));
        assertFalse(Files.exists(tempDir.resolve("_problem/duplicate-case")));
    }

    @Test
    void leaveBehaviorStillLeavesDuplicateInIncomingRootWhenExplicitlyConfigured() throws Exception {
        var adapter = adapter("LEAVE", "LEAVE");
        candidate("duplicate-leave");
        var claimed = adapter.claim("duplicate-leave", lease, dbNow);
        assertTrue(claimed.isPresent());
        assertTrue(Files.isDirectory(tempDir.resolve("_processing/duplicate-leave")));

        adapter.applyDisposition("duplicate-leave", IntakeDisposition.DUPLICATE, lease);

        assertTrue(Files.isDirectory(tempDir.resolve("duplicate-leave")));
        assertFalse(Files.exists(tempDir.resolve("_processing/duplicate-leave")));
        assertFalse(Files.exists(tempDir.resolve("duplicate-leave/" + LocalWatchedFolderSourceAdapter.CLAIM_FILE)));
    }

    @Test
    void atomicClaimMovesCandidateIntoProcessingAndReservedDirectoryIsNotDiscovered() throws Exception {
        var adapter = adapter("MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        readyCandidate("claim-me");

        var claimed = adapter.claim("claim-me", lease, dbNow);

        assertTrue(claimed.isPresent());
        assertFalse(Files.exists(tempDir.resolve("claim-me")));
        assertTrue(Files.isDirectory(tempDir.resolve("_processing/claim-me")));
        assertTrue(Files.isRegularFile(tempDir.resolve("_processing/claim-me/" + LocalWatchedFolderSourceAdapter.CLAIM_FILE)));
        assertTrue(adapter.discover().isEmpty());
    }

    @Test
    void secondClaimLosesRaceAfterFirstClaimMovedSource() throws Exception {
        var adapter = adapter("MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        readyCandidate("race-case");

        assertTrue(adapter.claim("race-case", lease, dbNow).isPresent());
        assertTrue(adapter.claim("race-case", new WatchedFolderScanLease("watched-folder-intake", "APP-02/test", 2L), dbNow.plusSeconds(1)).isEmpty());
    }

    @Test
    void staleFencedOwnerCannotMoveCandidateAfterNewerClaimGenerationOwnsIt() throws Exception {
        var adapter = adapter("MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        readyCandidate("fenced-case");
        WatchedFolderScanLease newer = new WatchedFolderScanLease("watched-folder-intake", "APP-02/test", 2L);
        assertTrue(adapter.claim("fenced-case", newer, dbNow).isPresent());

        adapter.applyDisposition("fenced-case", IntakeDisposition.ACCEPTED, lease);

        assertTrue(Files.isDirectory(tempDir.resolve("_processing/fenced-case")));
        assertFalse(Files.exists(tempDir.resolve("_archive/fenced-case")));
    }

    @Test
    void olderStaleClaimCanBeRecoveredOnlyByNewerFencingGeneration() throws Exception {
        var adapter = adapter("MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        readyCandidate("recover-case");
        assertTrue(adapter.claim("recover-case", lease, dbNow).isPresent());

        int sameGeneration = adapter.recoverStaleClaims(lease, Duration.ofSeconds(1), dbNow.plusSeconds(2));
        int newerGeneration = adapter.recoverStaleClaims(
                new WatchedFolderScanLease("watched-folder-intake", "APP-02/test", 2L),
                Duration.ofSeconds(1), dbNow.plusSeconds(2));

        assertEquals(0, sameGeneration);
        assertEquals(1, newerGeneration);
        assertTrue(Files.isDirectory(tempDir.resolve("recover-case")));
        assertFalse(Files.exists(tempDir.resolve("_processing/recover-case")));
    }


    @Test
    void futureDatedOlderGenerationClaimDoesNotRemainStranded() throws Exception {
        var adapter = adapter("MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        readyCandidate("future-skew");
        assertTrue(adapter.claim("future-skew", lease, dbNow.plusSeconds(3600)).isPresent());

        int recovered = adapter.recoverStaleClaims(
                new WatchedFolderScanLease("watched-folder-intake", "APP-02/test", 2L),
                Duration.ofSeconds(10), dbNow);

        assertEquals(1, recovered);
        assertTrue(Files.isDirectory(tempDir.resolve("future-skew")));
        assertFalse(Files.exists(tempDir.resolve("_processing/future-skew")));
    }

    @Test
    void discoverFailsClosedWhenConfiguredSharedRootIsMissingAndDoesNotRecreateIt() throws Exception {
        Path configured = tempDir.resolve("shared-mount");
        Files.createDirectory(configured);
        WatchedFolderProperties properties = properties(configured, "MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        var adapter = new LocalWatchedFolderSourceAdapter(properties);
        Files.delete(configured);

        IllegalStateException failure = assertThrows(IllegalStateException.class, adapter::discover);

        assertTrue(failure.getMessage().contains("shared root"));
        assertFalse(Files.exists(configured));
    }

    @Test
    void claimFailsClosedWhenSharedRootDisappearsAndDoesNotRecreateIt() throws Exception {
        Path configured = tempDir.resolve("shared-mount-claim");
        Files.createDirectory(configured);
        WatchedFolderProperties properties = properties(configured, "MOVE_TO_ARCHIVE", "MOVE_TO_PROBLEM");
        var adapter = new LocalWatchedFolderSourceAdapter(properties);
        Files.delete(configured);

        assertThrows(IllegalStateException.class, () -> adapter.claim("candidate", lease, dbNow));

        assertFalse(Files.exists(configured));
    }

    private LocalWatchedFolderSourceAdapter adapter(String acceptedBehavior, String rejectedBehavior) {
        return new LocalWatchedFolderSourceAdapter(properties(tempDir, acceptedBehavior, rejectedBehavior));
    }

    private WatchedFolderProperties properties(Path root, String acceptedBehavior, String rejectedBehavior) {
        WatchedFolderProperties properties = new WatchedFolderProperties();
        properties.setEnabled(true);
        properties.setRootPath(root.toString());
        properties.setAcceptedBehavior(acceptedBehavior);
        properties.setRejectedBehavior(rejectedBehavior);
        properties.setProcessingDirectory("_processing");
        properties.setArchiveDirectory("_archive");
        properties.setProblemDirectory("_problem");
        return properties;
    }

    private Path candidate(String name) throws Exception {
        Path source = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(source.resolve("document.pdf"), "synthetic");
        return source;
    }

    private Path readyCandidate(String name) throws Exception {
        Path source = candidate(name);
        Files.writeString(source.resolve("manifest.json"), """
                {"businessIdentifiers":{"Contract Number":"000900000001"},"files":["document.pdf"]}
                """);
        return source;
    }
}
