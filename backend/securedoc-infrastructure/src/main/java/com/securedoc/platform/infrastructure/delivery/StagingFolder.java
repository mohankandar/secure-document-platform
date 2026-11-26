package com.securedoc.platform.infrastructure.delivery;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0097: one delivery's staging folder, {@code <root>/<entity>/.staging/<case>-cycle-<n>-<delivery>}: ids only. A
 * folder that is being erased is first renamed to the same name with {@code .erasing}: a delivery that still ran would lose
 * its folder at once and be tried again, and can never publish a part. No link or junction is followed below the root.
 */
public record StagingFolder(UUID packageId, int exportCycle, UUID deliveryId) {
    public static final String DIRECTORY = ".staging";
    static final String ERASING = ".erasing";
    private static final String UUID_FORM = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern NAME =
            Pattern.compile("(" + UUID_FORM + ")-cycle-([1-9][0-9]{0,8})-(" + UUID_FORM + ")(" + Pattern.quote(ERASING) + ")?");
    private static final Pattern ID = Pattern.compile(UUID_FORM);

    /** A place that should be a folder and is a link, a junction or a file. Its message holds no path. */
    public static final class NotAFolderException extends IOException {
        NotAFolderException() {
            super("not a folder");
        }
    }

    public String name() {
        return packageId + "-cycle-" + exportCycle + "-" + deliveryId;
    }

    static Optional<StagingFolder> parse(String name) {
        Matcher m = NAME.matcher(name);
        return m.matches() ? Optional.of(new StagingFolder(UUID.fromString(m.group(1)), Integer.parseInt(m.group(2)),
                UUID.fromString(m.group(3)))) : Optional.empty();
    }

    static boolean isId(String name) {
        return ID.matcher(name).matches();
    }

    /** The entity's staging place; empty when there is none. */
    public static Optional<Path> staging(Path root, UUID entity) throws IOException {
        Path place = root;
        for (String next : List.of(entity.toString(), DIRECTORY)) {
            place = place.resolve(next);
            Optional<BasicFileAttributes> found = look(place);
            if (found.isEmpty()) return Optional.empty();
            if (!isRealFolder(found.get())) throw new NotAFolderException();
        }
        return Optional.of(place);
    }

    /** Whether something of this delivery is there. */
    public boolean isIn(Path staging) throws IOException {
        return look(staging.resolve(name())).isPresent() || look(staging.resolve(name() + ERASING)).isPresent();
    }

    /** The rename before the erasing; the files stay as they are. On Windows it fails while a file in it is open. */
    void setAside(Path staging) throws IOException {
        Path folder = staging.resolve(name());
        if (look(folder).isPresent()) Files.move(folder, staging.resolve(name() + ERASING));
    }

    /** Erases it; true when something was there. Doing it again is harmless. */
    public boolean erase(Path staging) throws IOException {
        if (!isIn(staging)) return false;
        Path aside = staging.resolve(name() + ERASING);
        deleteTree(aside);
        setAside(staging);
        deleteTree(aside);
        return true;
    }

    /** A link in it is removed itself, never followed. */
    private static void deleteTree(Path path) throws IOException {
        Optional<BasicFileAttributes> found = look(path);
        if (found.isEmpty()) return;
        if (isRealFolder(found.get())) {
            for (Path child : children(path)) deleteTree(child);
        }
        Files.deleteIfExists(path);
    }

    static List<Path> children(Path folder) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            entries.forEach(found::add);
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        return found;
    }

    /** Empty only when nothing is there; anything that can't be told is an exception, never "gone". */
    static Optional<BasicFileAttributes> look(Path path) throws IOException {
        try {
            return Optional.of(Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
        } catch (NoSuchFileException notThere) {
            return Optional.empty();
        }
    }

    /** A Windows junction counts as a folder of another kind ({@code isOther}). */
    static boolean isRealFolder(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther();
    }
}
