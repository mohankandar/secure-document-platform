package com.securedoc.platform.infrastructure.intake;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.intake.watched.*;
import com.securedoc.platform.application.port.out.WatchedFolderSourcePort;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
public class LocalWatchedFolderSourceAdapter implements WatchedFolderSourcePort {
    static final String CLAIM_FILE = ".lexavault-claim.json";

    private final WatchedFolderProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LocalWatchedFolderSourceAdapter(WatchedFolderProperties properties) { this.properties = properties; }

    @Override
    public List<WatchedFolderCandidate> discover() {
        if (!properties.isEnabled()) return List.of();
        Path root = requireAccessibleRoot();
        try {
            try (var stream = Files.list(root)) {
                return stream.filter(Files::isDirectory)
                        .filter(p -> !isReserved(p.getFileName().toString()))
                        .sorted()
                        .map(this::readCandidate)
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to scan watched-folder intake root", e);
        }
    }

    @Override
    public Optional<WatchedFolderCandidate> claim(String sourceReference, WatchedFolderScanLease lease, Instant claimedAt) {
        if (!properties.isEnabled()) return Optional.empty();
        Objects.requireNonNull(claimedAt, "claimedAt");
        Path verifiedRoot = requireAccessibleRoot();
        Path source = resolveIncomingSource(sourceReference);
        if (!Files.isDirectory(source)) return Optional.empty();
        Path processingRoot = verifiedRoot.resolve(properties.getProcessingDirectory()).normalize();
        Path claimed = processingRoot.resolve(sourceReference).normalize();
        try {
            ensureDirectChildDirectory(processingRoot);
            if (Files.exists(claimed)) return Optional.empty();
            try {
                Files.move(source, claimed, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Same-filesystem rename remains the required production deployment model. This fallback keeps
                // LOCAL/dev filesystems usable while still relying on the destination-exists race as the claim.
                Files.move(source, claimed);
            } catch (NoSuchFileException | FileAlreadyExistsException lostRace) {
                return Optional.empty();
            }
            writeClaim(claimed, lease, claimedAt);
            return Optional.of(readCandidate(claimed));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to claim watched-folder intake candidate", e);
        }
    }

    @Override
    public int recoverStaleClaims(WatchedFolderScanLease lease, Duration staleAfter, Instant currentTime) {
        if (!properties.isEnabled() || lease.fencingToken() <= 0) return 0;
        Objects.requireNonNull(currentTime, "currentTime");
        Path verifiedRoot = requireAccessibleRoot();
        Path processingRoot = verifiedRoot.resolve(properties.getProcessingDirectory()).normalize();
        if (!Files.isDirectory(processingRoot)) return 0;
        long staleMillis = Math.max(1000L, staleAfter == null ? 300000L : staleAfter.toMillis());
        long now = currentTime.toEpochMilli();
        int recovered = 0;
        try (var stream = Files.list(processingRoot)) {
            for (Path claimed : stream.filter(Files::isDirectory).sorted().toList()) {
                Optional<ClaimMarker> marker = readClaim(claimed);
                if (marker.isEmpty()) {
                    // A crash or write failure can occur in the tiny window after the atomic directory move
                    // and before the claim marker is durable. Recover only after the stale timeout.
                    long modifiedAt = Files.getLastModifiedTime(claimed).toMillis();
                    if (!isStale(now, modifiedAt, staleMillis)) continue;
                } else {
                    ClaimMarker current = marker.get();
                    if (current.fencingToken() >= lease.fencingToken()) continue;
                    if (!isStale(now, current.claimedAtEpochMs(), staleMillis)) continue;
                }
                Path target = verifiedRoot.resolve(claimed.getFileName().toString()).normalize();
                if (Files.exists(target)) continue;
                try {
                    Files.move(claimed, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(claimed, target);
                } catch (NoSuchFileException | FileAlreadyExistsException lostRace) {
                    continue;
                }
                recovered++;
            }
            return recovered;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to recover stale watched-folder claims", e);
        }
    }

    @Override
    public void applyDisposition(String sourceReference, IntakeDisposition disposition, WatchedFolderScanLease lease) {
        if (!properties.isEnabled()) return;
        Path verifiedRoot = requireAccessibleRoot();
        Path source = resolveClaimedSource(sourceReference);
        if (!Files.exists(source)) return;

        Optional<ClaimMarker> marker = readClaim(source);
        if (source.startsWith(processingRoot())) {
            if (marker.isEmpty()) {
                // Fail closed for a claimed location with missing/corrupt fencing metadata. Stale recovery
                // will return it to incoming after the configured timeout.
                return;
            }
            if (!marker.get().matches(lease)) {
                // Fencing: a stale node must never move a candidate that has been reclaimed by a newer owner.
                return;
            }
        }

        try {
            Files.deleteIfExists(source.resolve(CLAIM_FILE));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to finalize watched-folder claim metadata", e);
        }

        boolean archive = disposition == IntakeDisposition.ACCEPTED || disposition == IntakeDisposition.DUPLICATE;
        String behavior = archive ? properties.getAcceptedBehavior() : properties.getRejectedBehavior();
        if (behavior == null || behavior.equalsIgnoreCase("LEAVE")) {
            try {
                restoreToIncoming(source, sourceReference);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to restore watched-folder source after LEAVE disposition", e);
            }
            return;
        }

        Path destinationRoot = verifiedRoot.resolve(archive ? properties.getArchiveDirectory() : properties.getProblemDirectory()).normalize();
        try {
            ensureDirectChildDirectory(destinationRoot);
            Path destination = uniqueDestination(destinationRoot, source.getFileName().toString());
            String normalized = behavior.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("MOVE_")) Files.move(source, destination);
            else if (normalized.startsWith("COPY_")) {
                copyRecursively(source, destination);
                restoreToIncoming(source, sourceReference);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to apply watched-folder disposition", e);
        }
    }

    private WatchedFolderCandidate readCandidate(Path folder) {
        String sourceReference = folder.getFileName().toString();
        Path trigger = folder.resolve(properties.getTriggerFileName());
        if (!Files.isRegularFile(trigger)) {
            return new WatchedFolderCandidate(sourceReference, false, false, null, Map.of(), Map.of(), List.of());
        }
        try {
            Map<String,Object> manifest = objectMapper.readValue(trigger.toFile(), new TypeReference<>(){});
            Map<String,String> identifiers = stringMap(manifest.get("businessIdentifiers"));
            Map<String,String> metadata = stringMap(manifest.get("metadata"));
            List<String> requestedFiles = stringList(manifest.get("files"));
            List<Path> pdfs;
            if (requestedFiles.isEmpty()) {
                try (var stream = Files.list(folder)) {
                    pdfs = stream.filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().equals(properties.getTriggerFileName()))
                            .filter(p -> !p.getFileName().toString().equals(CLAIM_FILE))
                            .sorted().toList();
                }
            } else {
                pdfs = new ArrayList<>();
                for (String name : requestedFiles) {
                    Path p = safeChild(folder, name);
                    if (!Files.isRegularFile(p)) return rejected(sourceReference, IntakeRejectionCode.MISSING_FILE);
                    pdfs.add(p);
                }
            }
            if (pdfs.isEmpty()) return rejected(sourceReference, IntakeRejectionCode.NO_DOCUMENTS);
            for (Path p : pdfs) {
                if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    return rejected(sourceReference, IntakeRejectionCode.UNSUPPORTED_FILE);
                }
            }
            List<WatchedFolderDocument> documents = pdfs.stream()
                    .map(p -> new WatchedFolderDocument(p.getFileName().toString(), "application/pdf", new PathUploadContent(p)))
                    .toList();
            return new WatchedFolderCandidate(sourceReference, true, true, null, identifiers, metadata, documents);
        } catch (Exception e) {
            return rejected(sourceReference, IntakeRejectionCode.MALFORMED_TRIGGER);
        }
    }

    private void writeClaim(Path folder, WatchedFolderScanLease lease, Instant claimedAt) throws IOException {
        Path marker = folder.resolve(CLAIM_FILE);
        Path temp = folder.resolve(CLAIM_FILE + ".tmp");
        ClaimMarker data = new ClaimMarker(lease.ownerId(), lease.fencingToken(), claimedAt.toEpochMilli());
        objectMapper.writeValue(temp.toFile(), data);
        try {
            Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Optional<ClaimMarker> readClaim(Path folder) {
        Path marker = folder.resolve(CLAIM_FILE);
        if (!Files.isRegularFile(marker)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(marker.toFile(), ClaimMarker.class));
        } catch (IOException invalid) {
            return Optional.empty();
        }
    }

    private void restoreToIncoming(Path source, String sourceReference) throws IOException {
        Path incoming = requireAccessibleRoot().resolve(sourceReference).normalize();
        if (source.equals(incoming)) return;
        if (Files.exists(incoming)) throw new FileAlreadyExistsException(incoming.toString());
        try {
            Files.move(source, incoming, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, incoming);
        }
    }

    private boolean isStale(long authoritativeNow, long claimedAt, long staleMillis) {
        if (claimedAt > authoritativeNow) {
            // R6 markers used a JVM wall clock. A future timestamp on an older fencing generation
            // must not strand the claim forever. Treat impossible future times as stale once the
            // current owner is evaluating an older generation. R7 markers use DB time.
            return true;
        }
        return authoritativeNow - claimedAt >= staleMillis;
    }

    private Path requireAccessibleRoot() {
        Path root = root();
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IllegalStateException("Watched-folder shared root is unavailable");
        }
        return root;
    }

    private void ensureDirectChildDirectory(Path child) throws IOException {
        Path parent = child.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new NoSuchFileException(child.toString());
        }
        if (Files.exists(child)) {
            if (!Files.isDirectory(child)) throw new NotDirectoryException(child.toString());
            return;
        }
        try {
            Files.createDirectory(child);
        } catch (FileAlreadyExistsException concurrentCreate) {
            if (!Files.isDirectory(child)) throw concurrentCreate;
        }
    }

    private WatchedFolderCandidate rejected(String sourceReference, IntakeRejectionCode code) {
        return new WatchedFolderCandidate(sourceReference, true, false, code, Map.of(), Map.of(), List.of());
    }

    private Map<String,String> stringMap(Object value) {
        if (!(value instanceof Map<?,?> map)) return Map.of();
        Map<String,String> result = new LinkedHashMap<>();
        map.forEach((k,v) -> { if (k != null && v != null) result.put(k.toString(), v.toString()); });
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> c)) return List.of();
        return c.stream().filter(Objects::nonNull).map(Object::toString).filter(s -> !s.isBlank()).toList();
    }

    private Path safeChild(Path folder, String name) {
        Path p = folder.resolve(name).normalize();
        if (!p.startsWith(folder.normalize())) throw new IllegalArgumentException("Invalid manifest file path");
        return p;
    }

    private Path root() { return Paths.get(properties.getRootPath()).toAbsolutePath().normalize(); }
    private Path processingRoot() { return root().resolve(properties.getProcessingDirectory()).normalize(); }

    private Path resolveIncomingSource(String ref) {
        validateSourceReference(ref);
        Path p = root().resolve(ref).normalize();
        if (!p.startsWith(root())) throw new IllegalArgumentException("Invalid watched source reference");
        return p;
    }

    private Path resolveClaimedSource(String ref) {
        validateSourceReference(ref);
        Path processing = processingRoot().resolve(ref).normalize();
        if (processing.startsWith(processingRoot()) && Files.exists(processing)) return processing;
        return resolveIncomingSource(ref);
    }

    private void validateSourceReference(String ref) {
        if (ref == null || ref.isBlank() || ref.contains("/") || ref.contains("\\")) {
            throw new IllegalArgumentException("Invalid watched source reference");
        }
    }

    private boolean isReserved(String name) {
        return name.equals(properties.getProcessingDirectory())
                || name.equals(properties.getArchiveDirectory())
                || name.equals(properties.getProblemDirectory());
    }

    private Path uniqueDestination(Path root, String name) {
        Path target = root.resolve(name);
        if (!Files.exists(target)) return target;
        return root.resolve(name + "-" + System.currentTimeMillis());
    }

    private void copyRecursively(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path p : paths.toList()) {
                Path relative = source.relativize(p);
                Path dest = destination.resolve(relative);
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest);
                }
            }
        }
    }

    public static final class ClaimMarker {
        private String ownerId;
        private long fencingToken;
        private long claimedAtEpochMs;

        public ClaimMarker() {}
        public ClaimMarker(String ownerId, long fencingToken, long claimedAtEpochMs) {
            this.ownerId = ownerId;
            this.fencingToken = fencingToken;
            this.claimedAtEpochMs = claimedAtEpochMs;
        }
        public String ownerId() { return ownerId; }
        public long fencingToken() { return fencingToken; }
        public long claimedAtEpochMs() { return claimedAtEpochMs; }
        public String getOwnerId() { return ownerId; }
        public long getFencingToken() { return fencingToken; }
        public long getClaimedAtEpochMs() { return claimedAtEpochMs; }
        public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
        public void setFencingToken(long fencingToken) { this.fencingToken = fencingToken; }
        public void setClaimedAtEpochMs(long claimedAtEpochMs) { this.claimedAtEpochMs = claimedAtEpochMs; }
        boolean matches(WatchedFolderScanLease lease) {
            return Objects.equals(ownerId, lease.ownerId()) && fencingToken == lease.fencingToken();
        }
    }

    private static final class PathUploadContent implements UploadContent {
        private final Path path;
        PathUploadContent(Path path){this.path=path;}
        @Override public InputStream openStream(){
            try { return Files.newInputStream(path); }
            catch(IOException e){ throw new UncheckedIOException(e); }
        }
        @Override public long contentLength(){
            try { return Files.size(path); }
            catch(IOException e){ throw new UncheckedIOException(e); }
        }
    }
}
