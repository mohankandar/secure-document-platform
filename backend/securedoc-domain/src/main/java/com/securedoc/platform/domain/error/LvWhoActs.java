package com.securedoc.platform.domain.error;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Task 0082: who acts on a code, as the error code guide shows it: the user, the IT Admin or support. This table is
 * the only place that says which group a name from {@link LvErrorCode#whoActs()} belongs to; a name it doesn't know
 * is refused, so a new name fails the build (LvErrorCodeGuideTest). The names themselves are shown as they are.
 */
public enum LvWhoActs {

    USER("User"), IT_ADMIN("IT Admin"), SUPPORT("Support"), NOBODY("Nobody");

    /** A name, in lower case, and its groups. A sending system's own team is on the user's side, like the Business Admin. */
    private static final Map<String, List<LvWhoActs>> GROUPS = Map.ofEntries(
            Map.entry("person", List.of(USER)), Map.entry("person uploading", List.of(USER)),
            Map.entry("processor", List.of(USER)), Map.entry("operator", List.of(USER)),
            Map.entry("reviewer", List.of(USER)), Map.entry("another supervisor", List.of(USER)),
            Map.entry("business admin", List.of(USER)), Map.entry("source system", List.of(USER)),
            Map.entry("admin who saved it", List.of(USER, IT_ADMIN)), Map.entry("it admin", List.of(IT_ADMIN)),
            Map.entry("platform ops", List.of(SUPPORT)), Map.entry("nobody", List.of(NOBODY)));

    private final String label;

    LvWhoActs(String label) {
        this.label = label;
    }

    public String label() { return label; }

    /** The groups for {@code whoActs} ("Source system, IT Admin"), in the order user, IT Admin, support. */
    public static List<LvWhoActs> groups(String whoActs) {
        return Arrays.stream(whoActs.split(",")).map(name -> name.trim().toLowerCase(java.util.Locale.ROOT))
                .flatMap(name -> {
                    List<LvWhoActs> groups = GROUPS.get(name);
                    if (groups == null) throw new IllegalArgumentException("who acts has no group: " + name);
                    return groups.stream();
                }).distinct().sorted().toList();
    }
}
