package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.domain.finding.AiModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0065: the names and short forms a document's beginning (and the case's instructions) say its people are known by,
 * and the places where one of them stands on a page. Document text: never in a log, an audit record or a report.
 */
record KnownNames(List<String> forms, List<AiModel> models) {
    static final KnownNames NONE = new KnownNames(List.of(), List.of());
    private static final int MAX_FORMS = 200;
    private static final int MAX_LENGTH = 80;
    private static final int AROUND = 160;
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?;]\\s+|\\n\\s*\\n");
    /** Words for a person's part in a matter: each stands for a role, not a name. */
    private static final Set<String> ROLES = Set.of("plaintiff", "plaintiffs", "defendant", "defendants", "petitioner",
            "respondent", "appellant", "appellee", "claimant", "counsel", "attorney", "judge", "court", "witness", "guardian",
            "minor", "child", "children", "son", "daughter", "mother", "father", "wife", "husband", "estate", "decedent",
            "trustee", "executor", "borrower", "lender", "buyer", "seller", "landlord", "tenant", "owner", "debtor",
            "creditor", "insured", "patient", "employee", "employer", "customer", "client", "party", "parties", "grantor",
            "grantee", "he", "she", "they", "him", "her");

    KnownNames {
        forms = List.copyOf(forms);
        models = List.copyOf(models);
    }

    /** Counts only. */
    @Override
    public String toString() {
        return "KnownNames[forms=" + forms.size() + "]";
    }

    /**
     * @param full  persons' full names; the last name of each counts as a form too
     * @param other their other names
     */
    static List<String> forms(Collection<String> full, Collection<String> other) {
        Set<String> forms = new LinkedHashSet<>();
        for (String name : full) {
            add(forms, name);
            String[] words = name.strip().split("\\s+");
            if (words.length > 1 && !words[words.length - 1].matches("(?i)(jr|sr|ii|iii|iv|md|esq|phd)\\.?")) {
                add(forms, words[words.length - 1]);
            }
        }
        other.forEach(name -> add(forms, name));
        return forms.stream().limit(MAX_FORMS).toList();
    }

    private static void add(Set<String> forms, String text) {
        String form = text.replaceAll("\\s+", " ").replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}&&[^.]]+$", "").strip();
        String word = form.toLowerCase(Locale.ROOT).replaceFirst("^(the|a|an) ", "");
        if (form.length() < 2 || form.length() > MAX_LENGTH || ROLES.contains(word)) return;
        // A name is written with a capital; a form without one is an ordinary word.
        if (form.chars().noneMatch(Character::isUpperCase)) return;
        forms.add(form);
    }

    /** @param sentence the word asked about and the sentence it stands in */
    record Place(int start, int end, String sentence) { }

    /** Where a form stands as a whole word, as written or in capitals, outside the places already {@code taken}. */
    List<Place> places(String text, List<int[]> taken) {
        List<int[]> used = new ArrayList<>(taken);
        List<Place> places = new ArrayList<>();
        // The longest first: "Hope Banks" is one place, not also one of "Banks".
        for (String form : forms.stream().sorted((a, b) -> b.length() - a.length()).toList()) {
            for (String written : new LinkedHashSet<>(List.of(form, form.toUpperCase(Locale.ROOT)))) {
                String words = java.util.Arrays.stream(written.split(" ")).map(Pattern::quote)
                        .collect(java.util.stream.Collectors.joining("\\s+"));
                Matcher found = Pattern.compile("(?<![\\p{L}\\p{N}])" + words + "(?![\\p{L}\\p{N}])").matcher(text);
                while (found.find()) {
                    int start = found.start();
                    int end = found.end();
                    if (used.stream().anyMatch(u -> start < u[1] && u[0] < end)) continue;
                    used.add(new int[]{start, end});
                    places.add(new Place(start, end, "\"" + found.group().replaceAll("\\s+", " ") + "\" in: "
                            + sentence(text, start, end)));
                }
            }
        }
        return places;
    }

    private static String sentence(String text, int start, int end) {
        int from = Math.max(0, start - AROUND);
        int to = Math.min(text.length(), end + AROUND);
        String before = text.substring(from, start);
        Matcher m = SENTENCE_END.matcher(before);
        int cut = 0;
        while (m.find()) cut = m.end();
        String after = text.substring(end, to);
        Matcher next = SENTENCE_END.matcher(after);
        int stop = next.find() ? next.start() + 1 : after.length();
        return (before.substring(cut) + text.substring(start, end) + after.substring(0, Math.min(stop, after.length())))
                .replaceAll("\\s+", " ").strip();
    }
}
