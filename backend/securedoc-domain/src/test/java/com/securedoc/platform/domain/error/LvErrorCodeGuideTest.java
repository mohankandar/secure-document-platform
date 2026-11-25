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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0025 step 4: the customer error code guide ({@code docs/api/error-codes.md}) and the registry can never
 * drift. Every code has exactly one entry, in registry order, with the registry's title and fields, and every entry
 * has two to four numbered steps under "What to do".
 */
class LvErrorCodeGuideTest {

    private static final String REVIEWED_LINE = "Reviewed by the design chat, 2026-11-21.";
    private static final Pattern ENTRY_HEADING = Pattern.compile("^### (LV-[A-Z]+-\\d{3}) (.+)$");
    private static final String SUPPORT = "Support tells the customer";
    private static final Pattern AREA_HEADING = Pattern.compile("^## ([A-Z]+): (.+)$");
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

    /** Task 0057: what the app says to do is exactly the guide's steps (-Dlv.codes.write=true writes the file again). */
    @Test
    void whatToDoInTheAppIsTheGuidesSteps() throws IOException {
        StringBuilder expected = new StringBuilder("# GENERATED by LvErrorCodeGuideTest from docs/api/error-codes.md. Do not edit.\n");
        for (Entry entry : entries()) {
            expected.append(entry.code()).append('=').append(String.join(" ", steps(entry)).replace("\\", "\\\\")).append('\n');
        }
        Path file = guide().getParent().getParent().getParent()
                .resolve("backend/securedoc-domain/src/main/resources/lv-what-to-do.properties");
        if (Boolean.getBoolean("lv.codes.write")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, expected, StandardCharsets.UTF_8);
        }
        assertEquals(expected.toString(), Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "lv-what-to-do.properties is out of date; write it again with -Dlv.codes.write=true");
        for (LvErrorCode code : LvErrorCode.values()) {
            if (!Boolean.getBoolean("lv.codes.write")) assertTrue(!code.whatToDo().isBlank(), code.code());
        }
    }

    private static List<String> steps(Entry entry) {
        return entry.body().stream().filter(l -> STEP.matcher(l).matches())
                .map(l -> l.substring(l.indexOf(' ') + 1).trim().replace("**", "").replace("`", "")).toList();
    }

    /** Task 0082: steps, support texts and area names for the in-app guide and the PDF (-Dlv.codes.write=true writes it). */
    @Test
    void theGuideInTheAppIsTheGuidesTexts() throws IOException {
        StringBuilder expected = new StringBuilder("# GENERATED by LvErrorCodeGuideTest from docs/api/error-codes.md. Do not edit.\n");
        Map<String, String> areas = new LinkedHashMap<>();
        for (String line : lines()) {
            Matcher m = AREA_HEADING.matcher(line);
            if (m.matches()) areas.put(m.group(1), m.group(2).substring(0, 1).toUpperCase(java.util.Locale.ROOT) + m.group(2).substring(1));
        }
        assertEquals(Arrays.stream(LvErrorCode.Area.values()).map(Enum::name).toList(), List.copyOf(areas.keySet()), "areas");
        areas.forEach((area, name) -> expected.append("area.").append(area).append('=').append(name).append('\n'));
        for (Entry entry : entries()) {
            expected.append(entry.code()).append(".support=").append(field(entry, SUPPORT).replace("\\", "\\\\")).append('\n');
            int number = 0;
            for (String step : steps(entry)) {
                expected.append(entry.code()).append(".step.").append(++number).append('=').append(step.replace("\\", "\\\\")).append('\n');
            }
        }
        Path file = guide().getParent().getParent().getParent()
                .resolve("backend/securedoc-domain/src/main/resources/lv-error-guide.properties");
        if (Boolean.getBoolean("lv.codes.write")) Files.writeString(file, expected, StandardCharsets.UTF_8);
        assertEquals(expected.toString(), Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                "lv-error-guide.properties is out of date; write it again with -Dlv.codes.write=true");
    }

    /** Task 0082: the build fails when a code of the catalogue has no meaning, who acts, what to do or support text. */
    @Test
    void everyCodeHasAllItsGuideTexts() throws IOException {
        Map<String, Entry> byCode = new LinkedHashMap<>();
        for (Entry entry : entries()) byCode.put(entry.code(), entry);
        List<String> missing = new ArrayList<>();
        for (LvErrorCode code : LvErrorCode.values()) {
            Entry entry = byCode.get(code.code());
            if (entry == null) {
                missing.add(code.code() + ": no entry");
                continue;
            }
            for (String name : List.of("Means", "Who acts", SUPPORT)) {
                String prefix = "- **" + name + ":**";
                if (entry.body().stream().noneMatch(l -> l.startsWith(prefix) && !l.substring(prefix.length()).isBlank())) {
                    missing.add(code.code() + ": " + name);
                }
            }
            if (steps(entry).isEmpty()) missing.add(code.code() + ": What to do");
            if (!Boolean.getBoolean("lv.codes.write")) {
                if (code.description().isBlank() || code.whoActs().isBlank() || code.supportTells().isBlank()
                        || code.whatToDo().isBlank() || code.whatToDoSteps().isEmpty()) missing.add(code.code() + ": not in the app");
                else if (!String.join(" ", code.whatToDoSteps()).equals(code.whatToDo())) missing.add(code.code() + ": steps differ from what to do");
            }
        }
        assertTrue(missing.isEmpty(), "codes without a guide text: " + missing);
    }

    @Test
    void supportTextsAreShortAndHoldNoInternals() throws IOException {
        for (Entry entry : entries()) {
            String text = field(entry, SUPPORT);
            assertTrue(text.endsWith(".") && text.length() <= 260, entry.code() + ": " + text.length());
            assertTrue(!text.contains("`") && !text.contains("**") && !text.contains("http") && !text.contains("LV-"), entry.code());
        }
    }

    /** Task 0082: every "who acts" has its group; a name the table doesn't know is refused. */
    @Test
    void everyWhoActsHasAGroup() {
        for (LvErrorCode code : LvErrorCode.values()) {
            assertTrue(!code.whoActsGroups().isEmpty(), code.code());
            if (code.whoActsGroups().contains(LvWhoActs.NOBODY)) assertEquals(1, code.whoActsGroups().size(), code.code());
        }
        assertEquals(List.of(LvWhoActs.USER, LvWhoActs.IT_ADMIN), LvWhoActs.groups("IT Admin, source system"));
        assertEquals(List.of(LvWhoActs.SUPPORT), LvWhoActs.groups("Platform Ops"));
        assertThrows(IllegalArgumentException.class, () -> LvWhoActs.groups("Somebody new"));
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
