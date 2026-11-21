package com.securedoc.platform.infrastructure.pdf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Task 0023 B9 (#370, #372, #373): deletes temporary files and folders that may hold document
 * content (a copy of the original, a page image, OCR text, a redacted copy) without ever hiding the
 * real processing result. A failed delete is logged as {@code TEMP_FILE_DELETE_FAILED} with a
 * component code, a temp-file id and the exception type, so IT can find and remove the leftover.
 *
 * <p>The temp-file id is the random number the JVM put into the temp name (for example
 * {@code 4711} for {@code lexavault-redaction-output-4711.pdf}); IT can match it in the server's
 * temp folder. The log never carries a full path, an original file name or an exception message.
 */
public final class TempFileCleanup {

    private static final Logger log = LoggerFactory.getLogger(TempFileCleanup.class);
    private static final Pattern DIGITS = Pattern.compile("(\\d{4,})");

    private TempFileCleanup() {}

    /** Deletes one file. Returns true when it is gone (or never existed). Never throws. */
    public static boolean deleteQuietly(Path path, String component) {
        if (path == null) return true;
        try {
            Files.deleteIfExists(path);
            return true;
        } catch (IOException | RuntimeException e) {
            logFailure(component, path, e);
            return false;
        }
    }

    /** Deletes a folder and everything in it. Returns true when it is gone. Never throws. */
    public static boolean deleteTreeQuietly(Path directory, String component) {
        if (directory == null || !Files.exists(directory)) return true;
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        } catch (IOException | RuntimeException e) {
            logFailure(component, directory, e);
            return false;
        }
        String firstFailureType = null;
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException | RuntimeException e) {
                if (firstFailureType == null) firstFailureType = e.getClass().getSimpleName();
            }
        }
        if (firstFailureType != null || Files.exists(directory)) {
            // One line per folder, not per file: the folder is what IT removes.
            log.warn("TEMP_FILE_DELETE_FAILED component={} tempId={} reasonCode={}",
                    safeComponent(component), tempId(directory),
                    firstFailureType == null ? "TEMP_FOLDER_NOT_EMPTY" : firstFailureType);
            return false;
        }
        return true;
    }

    /**
     * A content-free id for a temp path: the JVM's random digits in the temp name, or (when the
     * name has none) the first 12 hex characters of the SHA-256 of the name.
     */
    public static String tempId(Path path) {
        if (path == null || path.getFileName() == null) return "unknown";
        String name = path.getFileName().toString();
        Matcher matcher = DIGITS.matcher(name);
        String digits = null;
        while (matcher.find()) digits = matcher.group(1);
        if (digits != null) return digits;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            return "h" + HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "unknown";
        }
    }

    private static void logFailure(String component, Path path, Exception e) {
        log.warn("TEMP_FILE_DELETE_FAILED component={} tempId={} reasonCode={}",
                safeComponent(component), tempId(path), e.getClass().getSimpleName());
    }

    private static String safeComponent(String component) {
        return component == null || component.isBlank() ? "UNKNOWN" : component;
    }
}
