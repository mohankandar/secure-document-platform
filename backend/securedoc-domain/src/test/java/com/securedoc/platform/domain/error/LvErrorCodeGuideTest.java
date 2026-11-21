package com.securedoc.platform.domain.error;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0025 step 4: the customer error code guide ({@code docs/api/error-codes.md}) and the registry can never
 * drift. Every code has exactly one entry, in registry order, with the registry's title and fields, and every entry
 * has two to four numbered steps under "What to do".
 */
class LvErrorCodeGuideTest {

    private static final String REVIEWED_LINE = "Reviewed by the design chat, 2026-11-21.";
    private static final Pattern ENTRY_HEADING = Pattern.compile("^### (LV-[A-Z]+-\\d{3}) (.+)$");
    private static final Pattern STEP = Pattern.compile("^(\\d+)\\. \\S.*");
    private static final Map<LvErrorCode.Kind, String> KIND_LABELS = Map.of(
            LvErrorCode.Kind.REFUSED, "Refused", LvErrorCode.Kind.WARNING, "Warning",
            LvErrorCode.Kind.WAITING, "Waiting", LvErrorCode.Kind.STOPPED, "Stopped",
            LvErrorCode.Kind.EXPORT_FAILED, "Export failed", LvErrorCode.Kind.NOT_ALLOWED, "Not allowed",
            LvErrorCode.Kind.SET_UP, "Set-up", LvErrorCode.Kind.SYSTEM, "System");

    record Entry(String code, String title, List<String> body) {
    }

    static Path guide() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/api/error-codes.md");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new AssertionError("docs/api/error-codes.md not found above " + Path.of("").toAbsolutePath());
    }

    static List<String> lines() throws IOException {
        return Files.readAllLines(guide(), StandardCharsets.UTF_8);
    }

    /** Entries in guide order; an entry runs from its heading to the next "##" or "###" heading. */
    static List<Entry> entries() throws IOException {
        List<Entry> entries = new ArrayList<>();
        Entry current = null;
        for (String line : lines()) {
            if (line.startsWith("### LV-")) {
                Matcher m = ENTRY_HEADING.matcher(line);
                assertTrue(m.matches(), "entry heading not in the form '### LV-XXX-NNN Title': " + line);
                current = new Entry(m.group(1), m.group(2), new ArrayList<>());
                entries.add(current);
            } else if (line.startsWith("## ") || line.startsWith("### ")) {
                current = null;
            } else if (current != null) {
                current.body().add(line);
            }
        }
        return entries;
    }

    static String field(Entry entry, String name) {
        String prefix = "- **" + name + ":** ";
        List<String> found = entry.body().stream().filter(l -> l.startsWith(prefix))
                .map(l -> l.substring(prefix.length()).trim()).toList();
        assertEquals(1, found.size(), entry.code() + " must have exactly one '" + name + "' line");
        return found.get(0);
    }

    @Test
    void reviewedLineIsPresentAndTheDraftLineGone() throws IOException {
        assertTrue(lines().stream().anyMatch(l -> l.contains(REVIEWED_LINE)), "the guide must say: " + REVIEWED_LINE);
        assertTrue(lines().stream().noneMatch(l -> l.contains("Draft: steps to be reviewed")), "the draft line must be gone");
    }

    @Test
    void guideHasEveryRegistryCodeExactlyOnce() throws IOException {
        List<String> guideCodes = entries().stream().map(Entry::code).toList();
        Set<String> unique = new LinkedHashSet<>(guideCodes);
        assertEquals(guideCodes.size(), unique.size(), "a code is listed twice in the guide: " + guideCodes);
        Set<String> registry = new LinkedHashSet<>(Arrays.stream(LvErrorCode.values()).map(LvErrorCode::code).toList());
        Set<String> missing = new LinkedHashSet<>(registry);
        missing.removeAll(unique);
        Set<String> unknown = new LinkedHashSet<>(unique);
        unknown.removeAll(registry);
        assertTrue(missing.isEmpty(), "registry codes missing from the guide: " + missing);
        assertTrue(unknown.isEmpty(), "guide codes not in the registry: " + unknown);
    }

    @Test
    void guideIsInRegistryOrder() throws IOException {
        List<String> guideCodes = entries().stream().map(Entry::code).toList();
        List<String> registry = Arrays.stream(LvErrorCode.values()).map(LvErrorCode::code).toList();
        assertEquals(registry, guideCodes);
    }

    @Test
    void titlesAndFieldsMatchTheRegistry() throws IOException {
        Map<String, Entry> byCode = new LinkedHashMap<>();
        for (Entry entry : entries()) byCode.put(entry.code(), entry);
        for (LvErrorCode code : LvErrorCode.values()) {
            Entry entry = byCode.get(code.code());
            assertTrue(entry != null, code.code() + " missing from the guide");
            assertEquals(code.title(), entry.title(), code.code() + " title");
            assertEquals(code.description(), field(entry, "Means"), code.code() + " means");
            assertEquals(KIND_LABELS.get(code.kind()), field(entry, "Kind"), code.code() + " kind");
            assertEquals(code.whoActs(), field(entry, "Who acts"), code.code() + " who acts");
            assertEquals(code.httpStatusLabel(), field(entry, "HTTP status"),
                    code.code() + " HTTP status");
            boolean saysReserved = entry.body().stream().anyMatch(l -> l.trim().equals("Reserved: not used yet."));
            assertEquals(code.reserved(), saysReserved, code.code() + " reserved note");
        }
    }

    @Test
    void everyEntryHasTwoToFourNumberedSteps() throws IOException {
        for (Entry entry : entries()) {
            List<String> body = entry.body();
            int start = body.indexOf("**What to do:**");
            assertTrue(start >= 0, entry.code() + " has no '**What to do:**' line");
            List<Integer> numbers = new ArrayList<>();
            for (String line : body.subList(start + 1, body.size())) {
                Matcher m = STEP.matcher(line);
                if (m.matches()) numbers.add(Integer.valueOf(m.group(1)));
            }
            assertTrue(numbers.size() >= 2 && numbers.size() <= 4,
                    entry.code() + " must have 2 to 4 steps, has " + numbers.size());
            for (int i = 0; i < numbers.size(); i++) {
                assertEquals(i + 1, numbers.get(i), entry.code() + " steps must be numbered 1, 2, 3 ...");
            }
        }
    }
}
