package com.securedoc.platform.api.intake;

import com.securedoc.platform.api.exception.GlobalExceptionHandler;
import com.securedoc.platform.application.intake.IntakeGate;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 0067: the safety stop (below 10 % free, again above 15 %) and the pacing. */
class IntakeSpaceGateTest {
    private final Map<String, Double> disks = new HashMap<>(Map.of("a", 50.0, "b", 60.0));
    private long databaseBytes = 100;
    private int queue;
    private long now;
    private boolean databaseDown;
    private final IntakeSpaceGate gate = new IntakeSpaceGate(new IntakeSpaceGate.Settings(10, 15, 300, 1000, 200),
            () -> List.of("a", "b"), () -> {
        if (databaseDown) throw new IllegalStateException("down");
        return databaseBytes;
    }, () -> queue, folder -> {
        Double percent = disks.get(folder.toString());
        if (percent == null) throw new IOException("gone");
        return percent;
    }, () -> now);

    @Test
    void stopsBelowTenAndStartsAgainOnlyAboveFifteen() {
        assertFalse(full());
        disks.put("b", 10.0);
        assertFalse(full(), "ten is not below ten");
        disks.put("b", 9.9);
        assertTrue(full());
        disks.put("b", 12.0);
        assertTrue(full(), "between the two it stays stopped");
        disks.put("b", 15.0);
        assertTrue(full());
        disks.put("b", 15.1);
        assertFalse(full());
        disks.put("b", 12.0);
        assertFalse(full(), "between the two it stays open");
    }

    @Test
    void theDatabaseCountsToo() {
        databaseBytes = 901;
        assertTrue(full());
        databaseBytes = 849;
        assertFalse(full());
        assertFalse(new IntakeSpaceGate(new IntakeSpaceGate.Settings(10, 15, 300, 0, 200), List::of,
                () -> Long.MAX_VALUE, () -> 0).full(), "no size set: not checked");
    }

    @Test
    void whatCannotBeMeasuredChangesNothing() {
        disks.remove("a");
        assertFalse(full());
        disks.put("b", 5.0);
        assertTrue(full(), "the other disk is nearly full");
        disks.put("b", 50.0);
        assertTrue(full(), "a is not known");
        disks.put("a", 50.0);
        databaseDown = true;
        assertTrue(full());
        databaseDown = false;
        assertFalse(full());
    }

    @Test
    void measuredAtMostEveryFiveSeconds() {
        assertFalse(full());
        disks.put("b", 1.0);
        now += 1_000_000_000L;
        assertFalse(gate.full());
        now += 5_000_000_000L;
        assertTrue(gate.full());
    }

    @Test
    void intakeTakesLessTheLongerTheQueue() {
        int[][] expected = {{0, 1, 100}, {199, 1, 100}, {200, 2, 50}, {399, 2, 50}, {400, 4, 25}, {800, 8, 12},
                {10_000, 8, 12}};
        for (int[] e : expected) {
            queue = e[0];
            assertEquals(e[1], gate.slowdown(), "queue " + e[0]);
            assertEquals(e[2], gate.share(100));
        }
        assertEquals(1, gate.share(3), "at least one");
    }

    @Test
    void wrongSettingsStopTheStart() {
        assertThrows(IllegalStateException.class, () -> new IntakeSpaceGate.Settings(10, 10, 300, 0, 200));
        assertThrows(IllegalStateException.class, () -> new IntakeSpaceGate.Settings(0, 15, 300, 0, 200));
        assertThrows(IllegalStateException.class, () -> new IntakeSpaceGate.Settings(10, 15, 0, 0, 200));
    }

    @RestController
    static class Intakes {
        @PostMapping("/api/v1/external-intakes")
        String create() {
            return "{}";
        }

        @GetMapping("/api/v1/external-intakes/{id}")
        String status(@PathVariable String id) {
            return "{}";
        }

        @PostMapping("/api/v1/packages/{id}/documents")
        String document(@PathVariable String id) {
            return "{}";
        }

        @PostMapping("/api/v1/packages/{id}/submit")
        String submit(@PathVariable String id) {
            return "{}";
        }
    }

    @Test
    void theApiAnswersBusyWithRetryAfterAndStillAnswersWhatIsHereAlready() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Intakes()).setControllerAdvice(new GlobalExceptionHandler())
                .addMappedInterceptors(IntakeSpaceInterceptor.PATHS.toArray(String[]::new),
                        new IntakeSpaceInterceptor(gate)).build();
        mvc.perform(post("/api/v1/external-intakes")).andExpect(status().isOk());
        disks.put("a", 3.0);
        now += 6_000_000_000L;
        for (String path : List.of("/api/v1/external-intakes", "/api/v1/packages/1/documents")) {
            mvc.perform(post(path)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "300"))
                    .andExpect(jsonPath("$.code").value("LV-INT-027"))
                    .andExpect(jsonPath("$.errorCode").value("INTAKE_BUSY"));
        }
        mvc.perform(get("/api/v1/external-intakes/1")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/packages/1/submit")).andExpect(status().isOk());
        disks.put("a", 40.0);
        now += 6_000_000_000L;
        mvc.perform(post("/api/v1/external-intakes")).andExpect(status().isOk());
    }

    @Test
    void theOpenGateTakesEverything() {
        assertEquals(50, IntakeGate.OPEN.share(50));
        assertFalse(IntakeGate.OPEN.full());
    }

    private boolean full() {
        now += 6_000_000_000L;
        return gate.full();
    }
}
