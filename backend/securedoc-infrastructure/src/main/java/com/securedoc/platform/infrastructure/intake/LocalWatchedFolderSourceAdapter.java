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
import java.util.*;

@Component
public class LocalWatchedFolderSourceAdapter implements WatchedFolderSourcePort {
    private final WatchedFolderProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LocalWatchedFolderSourceAdapter(WatchedFolderProperties properties) { this.properties = properties; }

    @Override
    public List<WatchedFolderCandidate> discover() {
        if (!properties.isEnabled()) return List.of();
        Path root = root();
        try {
            Files.createDirectories(root);
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
    public void applyDisposition(String sourceReference, IntakeDisposition disposition) {
        if (!properties.isEnabled() || disposition == IntakeDisposition.DUPLICATE) return;
        Path source = resolveSource(sourceReference);
        if (!Files.exists(source)) return;
        String behavior = disposition == IntakeDisposition.ACCEPTED
                ? properties.getAcceptedBehavior() : properties.getRejectedBehavior();
        if (behavior == null || behavior.equalsIgnoreCase("LEAVE")) return;
        boolean archive = disposition == IntakeDisposition.ACCEPTED;
        Path destinationRoot = root().resolve(archive ? properties.getArchiveDirectory() : properties.getProblemDirectory()).normalize();
        try {
            Files.createDirectories(destinationRoot);
            Path destination = uniqueDestination(destinationRoot, source.getFileName().toString());
            String normalized = behavior.trim().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("MOVE_")) Files.move(source, destination);
            else if (normalized.startsWith("COPY_")) copyRecursively(source, destination);
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
    private Path resolveSource(String ref) {
        if (ref == null || ref.isBlank() || ref.contains("/") || ref.contains("\\")) throw new IllegalArgumentException("Invalid watched source reference");
        Path p = root().resolve(ref).normalize();
        if (!p.startsWith(root())) throw new IllegalArgumentException("Invalid watched source reference");
        return p;
    }
    private boolean isReserved(String name) { return name.equals(properties.getArchiveDirectory()) || name.equals(properties.getProblemDirectory()); }
    private Path uniqueDestination(Path root, String name) {
        Path target = root.resolve(name); if (!Files.exists(target)) return target;
        return root.resolve(name + "-" + System.currentTimeMillis());
    }
    private void copyRecursively(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path p : paths.toList()) {
                Path relative = source.relativize(p); Path dest = destination.resolve(relative);
                if (Files.isDirectory(p)) Files.createDirectories(dest); else { Files.createDirectories(dest.getParent()); Files.copy(p,dest); }
            }
        }
    }
    private static final class PathUploadContent implements UploadContent {
        private final Path path; PathUploadContent(Path path){this.path=path;}
        @Override public InputStream openStream(){ try { return Files.newInputStream(path); } catch(IOException e){ throw new UncheckedIOException(e); } }
        @Override public long contentLength(){ try { return Files.size(path); } catch(IOException e){ throw new UncheckedIOException(e); } }
    }
}
