package com.securedoc.platform.api.protection;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlers;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.crypto.KeyServiceChainKey;
import com.documentplatform.eraselog.file.FileEraseLogStore;
import com.documentplatform.monitoring.CheckState;
import com.securedoc.install.TestBuild;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.WorkGate;
import com.securedoc.platform.infrastructure.storage.FileEncryption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0060: the key service and the erase log are always there outside a local development start; the guard stops all work. */
class ProtectionRulesTest {
    private static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");

    @TempDir Path folder;

    private static MockEnvironment environment(String profile, String mode, String... more) {
        MockEnvironment e = new MockEnvironment().withProperty("securedoc.security.mode", mode)
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/securedoc?x=y");
        if (profile != null) e.setActiveProfiles(profile.split(","));
        for (int i = 0; i < more.length; i += 2) e.setProperty(more[i], more[i + 1]);
        new LocalProtectionDefaults().postProcessEnvironment(e, new SpringApplication());
        return e;
    }

    private static boolean plain(MockEnvironment e) {
        return "DEVELOPMENT_PLAIN".equals(e.getProperty("securedoc.storage.file-protection"));
    }

    @Test
    void onlyALocalStartWithoutAKeyServiceIsPlainAndNoSettingMakesAnotherOnePlain() {
        MockEnvironment local = environment("local", "LOCAL");
        assertTrue(plain(local));
        assertEquals("false", local.getProperty("platform.crypto.enabled"));
        assertFalse(plain(environment("local", "LOCAL", "platform.crypto.vault.address", "https://v")));
        assertFalse(plain(environment("local,oidc-local", "OIDC")));
        assertFalse(plain(environment(null, "LOCAL")), "the mode alone, without the local profile");
        assertFalse(plain(environment(null, "OIDC", "securedoc.storage.file-protection", "DEVELOPMENT_PLAIN")));
    }

    @Test
    void theLocalEraseLogIsOnePerDatabaseAndNeverFilledInElsewhere() {
        Path local = Path.of(environment("local", "LOCAL").getProperty("platform.erase-log.location"));
        assertTrue(local.isAbsolute());
        assertTrue(local.endsWith(Path.of(".lexavault", "erase-log", "localhost-5432-securedoc")), local.toString());
        assertFalse(LocalProtectionDefaults.localEraseLog("h", "jdbc:postgresql://localhost:5432/other")
                .equals(LocalProtectionDefaults.localEraseLog("h", "jdbc:postgresql://localhost:5432/securedoc")));
        assertEquals("C:/set", environment("local", "LOCAL", "platform.erase-log.location", "C:/set")
                .getProperty("platform.erase-log.location"));
        assertEquals(null, environment(null, "OIDC").getProperty("platform.erase-log.location"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> given(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }

    @Test
    void withoutAUsableKeyServiceTheAppDoesNotStartOutsideALocalDevelopmentBuild() {
        FileProtectionConfig config = new FileProtectionConfig();
        MockEnvironment oidc = environment(null, "OIDC");
        StreamCipher cipher = new StreamCipher(new InMemoryKeyService());
        assertThrows(IllegalStateException.class, () -> config.fileEncryption(oidc, given(null), given(CUSTOMER)));
        assertThrows(IllegalStateException.class, () -> config.fileEncryption(oidc, given(cipher), given(null)));
        assertTrue(config.fileEncryption(oidc, given(cipher), given(CUSTOMER)).encrypts());

        MockEnvironment local = environment("local", "LOCAL");
        FileEncryption plain = config.fileEncryption(local, given(null), given(null));
        assertFalse(plain.encrypts());
        assertDoesNotThrow(() -> config.plainFilesOnlyInDevelopment(local, given(TestBuild.development())).afterSingletonsInstantiated());
        assertThrows(IllegalStateException.class, () -> config.plainFilesOnlyInDevelopment(local,
                given(TestBuild.production())).afterSingletonsInstantiated(), "a production build never stores plain files");
    }

    @Test
    void theEraseLogCanNotBeSwitchedOffOrLeftUnkeyed() {
        EraseLogRulesConfig rules = new EraseLogRulesConfig();
        assertThrows(IllegalStateException.class, () -> rules.eraseLogRules(environment("local", "LOCAL",
                "platform.erase-log.enabled", "false")).afterPropertiesSet());
        assertThrows(IllegalStateException.class, () -> rules.eraseLogRules(environment(null, "OIDC",
                "platform.erase-log.keyed", "false")).afterPropertiesSet());
        assertDoesNotThrow(() -> rules.eraseLogRules(environment(null, "OIDC")).afterPropertiesSet());
    }

    // ------------------------------------------------------------------------------------- the gate

    private static EraseHandler handler() {
        return new com.documentplatform.eraselog.testkit.RecordingEraseHandler("test-kind");
    }

    private static EraseRequest request() {
        return new EraseRequest(UUID.randomUUID(), "LEXAVAULT", "case", UUID.randomUUID().toString(), "test-kind", Instant.now(),
                ErasedBy.rule("test"), UUID.randomUUID().toString());
    }

    @Test
    void aNewKeyedLogIsSealedOnItsOwnAndARestoreOrALostLogStopsWork() {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(CUSTOMER);
        var chain = new KeyServiceChainKey(keys, CUSTOMER);
        var position = new com.documentplatform.eraselog.testkit.InMemoryErasePosition();
        EraseLog eraseLog = new EraseLog(new FileEraseLogStore(folder.resolve("log")), Clock.systemUTC(), chain);
        EraseGuard guard = new EraseGuard(eraseLog, position, Clock.systemUTC());
        guard.checkAtStartup();
        // Task 0113: platform-core now seals a new keyed log by itself (the 0060 report). The app's own sealing stays harmless.
        assertTrue(guard.mayTakeWork(), "platform-core's guard: sealed on its own");
        EraseLogWorkGate gate = new EraseLogWorkGate(guard, eraseLog, new DefaultApplicationArguments());
        assertTrue(gate.mayTakeWork(), "sealed by the app");
        assertEquals(CheckState.WORKING, new EraseLogCheck(guard).check().state());
        assertFalse(new EraseLogWorkGate(guard, eraseLog, new DefaultApplicationArguments("--erase-replay")).mayTakeWork());

        var before = position.snapshot();
        new Eraser(eraseLog, position, EraseHandlers.of(handler()), guard).erase(request());
        position.restore(before); // the database of before
        guard.checkAtStartup();
        assertFalse(gate.mayTakeWork());
        assertEquals(TechnicalFailureCode.ERASE_REPLAY_REQUIRED,
                assertThrows(TechnicalDependencyException.class, gate::requireWork).failureCode());
        assertEquals("LV-SYS-013", new EraseLogCheck(guard).check().code().code());
        assertThrows(TechnicalDependencyException.class, () -> new WorkGateInterceptor(gate)
                .preHandle(new MockHttpServletRequest("POST", "/api/v1/packages"), new MockHttpServletResponse(), this));
        for (String reading : List.of("GET", "HEAD", "OPTIONS")) {
            assertTrue(new WorkGateInterceptor(gate).preHandle(new MockHttpServletRequest(reading, "/x"),
                    new MockHttpServletResponse(), this));
        }
    }

    @Test
    void aLogWithEntriesIsNeverSealedByTheApp() {
        InMemoryKeyService keys = new InMemoryKeyService();
        keys.createKeys(CUSTOMER);
        var position = new com.documentplatform.eraselog.testkit.InMemoryErasePosition();
        Path location = folder.resolve("unkeyed");
        new Eraser(new EraseLog(new FileEraseLogStore(location), Clock.systemUTC()), position, EraseHandlers.of(handler()))
                .erase(request());
        EraseLog keyed = new EraseLog(new FileEraseLogStore(location), Clock.systemUTC(), new KeyServiceChainKey(keys, CUSTOMER));
        EraseGuard guard = new EraseGuard(keyed, position, Clock.systemUTC());
        guard.checkAtStartup();
        EraseLogWorkGate gate = new EraseLogWorkGate(guard, keyed, new DefaultApplicationArguments());
        assertFalse(gate.mayTakeWork());
        assertTrue(keyed.sealRecord().isEmpty());
        assertEquals(TechnicalFailureCode.ERASE_LOG_UNAVAILABLE,
                assertThrows(TechnicalDependencyException.class, gate::requireWork).failureCode());
        assertEquals("LV-SYS-014", new EraseLogCheck(guard).check().code().code());
    }

    // -------------------------------------------------------------------------------- scheduled work

    /** Every scheduled task of the app is stopped by the gate, but exactly the three that take no work. */
    @Test
    void everyScheduledTaskIsGatedButTheSignsOfLife() throws Exception {
        Set<String> found = new TreeSet<>();
        Path classes = Path.of("target/classes");
        try (Stream<Path> all = Files.walk(classes.resolve("com/securedoc/platform"))) {
            for (Path file : all.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(file).toString().replace('\\', '.').replace('/', '.').replaceFirst("\\.class$", "");
                Class<?> type = Class.forName(name, false, getClass().getClassLoader());
                for (var method : type.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Scheduled.class)) found.add(name + "." + method.getName());
                }
            }
        }
        assertTrue(found.containsAll(ScheduledWorkGate.ALWAYS), "named as they are: " + found);
        // Task 0111: EmptyCaseExpiryScheduler (12th) changes cases, so it is gated like the others.
        assertEquals(12, found.size(), "a new scheduled task is gated by default; decide here if it must run always: " + found);

        AtomicInteger ran = new AtomicInteger();
        AtomicBoolean open = new AtomicBoolean();
        WorkGate gate = new WorkGate() {
            @Override
            public boolean mayTakeWork() {
                return open.get();
            }

            @Override
            public void requireWork() {
            }
        };
        // Real @Scheduled methods, as Spring registers them: the app's own class, and one that always runs.
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(WorkGate.class, () -> gate);
            context.registerBean(org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder.class,
                    () -> new org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder().poolSize(2));
            context.register(ScheduledWorkGate.class, SchedulingOn.class);
            context.refresh();
            Set<String> names = new TreeSet<>();
            context.getBean(org.springframework.scheduling.config.ScheduledTaskHolder.class).getScheduledTasks()
                    .forEach(task -> names.add(task.getTask().getRunnable().toString()));
            assertEquals(Set.of(SchedulingOn.class.getName() + ".somethingScheduled"), names, "as ALWAYS names them");
            Thread.sleep(300);
            assertEquals(0, SchedulingOn.RAN.get(), "nothing runs while work is stopped");
            open.set(true);
            for (int i = 0; i < 100 && SchedulingOn.RAN.get() < 2; i++) Thread.sleep(20);
            assertTrue(SchedulingOn.RAN.get() >= 2, "it kept its schedule");
            open.set(false);
        }
        Runnable always = new Runnable() {
            @Override
            public void run() {
                ran.incrementAndGet();
            }

            @Override
            public String toString() {
                return ScheduledWorkGate.ALWAYS.iterator().next();
            }
        };
        ThreadPoolTaskScheduler scheduler = new ScheduledWorkGate.Gated(gate);
        scheduler.initialize();
        try {
            scheduler.scheduleWithFixedDelay(always, Duration.ofMillis(20));
            for (int i = 0; i < 100 && ran.get() == 0; i++) Thread.sleep(20);
            assertTrue(ran.get() > 0, "a sign of life runs also while work is stopped");
        } finally {
            scheduler.shutdown();
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.scheduling.annotation.EnableScheduling
    static class SchedulingOn {
        static final AtomicInteger RAN = new AtomicInteger();

        @Scheduled(fixedDelay = 20)
        void somethingScheduled() {
            RAN.incrementAndGet();
        }
    }
}
