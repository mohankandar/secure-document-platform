package com.securedoc.platform.infrastructure.delivery;

import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Task 0087: how far a delivery through a profile got, as stored with the delivery submission: the calls made once that
 * worked, the destination's ids they answered with, and the reference of work it accepted for later. Documents that are
 * done are the submission's own acknowledgements. No value of a case, no secret, no answer text.
 *
 * @param key the connection, its address (a hash) and the profile's name: progress of another one is never used
 */
record DeliveryProgress(String key, Set<String> steps, Map<String, String> values, String pendingStep,
                        String pendingReference, String correlationId, boolean already) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    DeliveryProgress {
        steps = Set.copyOf(steps == null ? Set.of() : steps);
        values = Map.copyOf(values == null ? Map.of() : values);
    }

    static DeliveryProgress begun(String key) {
        return new DeliveryProgress(key, Set.of(), Map.of(), null, null, null, false);
    }

    DeliveryProgress after(String step, Map<String, String> read) {
        Set<String> s = new LinkedHashSet<>(steps);
        s.add(step);
        return new DeliveryProgress(key, s, merged(read), null, null, null, already);
    }

    DeliveryProgress accepted(String step, String reference, String correlation, Map<String, String> read) {
        return new DeliveryProgress(key, steps, merged(read), step, reference, correlation, already);
    }

    DeliveryProgress alreadyReceived() {
        return new DeliveryProgress(key, steps, values, pendingStep, pendingReference, correlationId, true);
    }

    private Map<String, String> merged(Map<String, String> read) {
        Map<String, String> v = new LinkedHashMap<>(values);
        v.putAll(read);
        return v;
    }

    String write() {
        return JSON.writeValueAsString(this);
    }

    /** Null when there is none. Text that can't be read is refused (fail closed), never taken as "nothing sent yet". */
    static DeliveryProgress parse(String stored) {
        if (stored == null) return null;
        DeliveryProgress p = JSON.readValue(stored, DeliveryProgress.class);
        if (p.key() == null) throw new IllegalStateException("The stored delivery progress can't be read");
        return p;
    }
}
