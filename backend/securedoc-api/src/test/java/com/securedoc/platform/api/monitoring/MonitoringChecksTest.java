package com.securedoc.platform.api.monitoring;

import com.documentplatform.errors.FailureLog;
import com.documentplatform.errors.PlatformCode;
import com.documentplatform.errors.ReferenceId;
import com.documentplatform.monitoring.CheckResult;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.StartupChecks;
import com.documentplatform.monitoring.Verdict;
import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.api.config.StartupFailureLogger;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.api.error.FailureReferences;
import com.securedoc.platform.api.error.LvPlatformCode;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0057: LexaVault's own dependency checks, the LV codes in the shared shape, and the references of failures. */
class MonitoringChecksTest {

    private static ModelServerCheck check(String model, URI... addresses) {
        return new ModelServerCheck(java.util.Arrays.stream(addresses)
                .map(a -> new ModelServerCheck.Server(a, model, "key-that-is-never-shown")).toList(), false);
    }

    @Test
    void theModelServerIsWorkingDownOrHavingProblems() throws Exception {
        AtomicReference<String> seenKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", x -> {
            seenKey.set(x.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"models\":[{\"name\":\"qwen:4b\"}]}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.createContext("/v1/models", x -> {
            byte[] body = "{\"data\":[{\"id\":\"qwen:4b\"}]}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        server.start();
        try {
            URI up = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/tags");
            URI missing = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/nothing-here");
            assertEquals(CheckState.WORKING, check("qwen:4b", up).check().state());
            assertEquals("Bearer key-that-is-never-shown", seenKey.get());
            assertEquals(CheckState.WORKING, check("qwen:4b", URI.create(up.toString().replace("/api/tags", "/v1/models"))).check().state());
            Verdict noModel = check("other:1b", up).check();
            assertEquals(CheckState.DOWN, noModel.state());
            assertEquals("LV-AI-004", noModel.code().code());
            assertEquals(MonitoringCode.MON_HTTP_UNEXPECTED_ANSWER, check("", missing).check().code());
            assertEquals(CheckState.HAVING_PROBLEMS, check("qwen:4b", up, missing).check().state());
        } finally {
            server.stop(0);
        }
        URI gone = URI.create("http://user:secret@127.0.0.1:" + server.getAddress().getPort() + "/api/tags?key=secret");
        Verdict down = check("qwen:4b", gone).check();
        assertEquals(CheckState.DOWN, down.state());
        assertFalse((down.details() + down.toString()).contains("secret"), "no user part, query or key");
    }

    @Test
    void theModelServersAreTheConfiguredOnes() {
        AiRuntimeProperties ai = new AiRuntimeProperties();
        ai.setProvider("ollama");
        ai.setModel("m");
        ai.setBaseUrl("http://ai:11434/");
        assertEquals("http://ai:11434/api/tags", MonitoringConfig.servers(ai).get(0).models().toString());
        ai.setProvider("openai-compatible");
        ai.setBaseUrl("http://ai:8000/v1");
        assertEquals("http://ai:8000/v1/models", MonitoringConfig.servers(ai).get(0).models().toString());
    }

    @Test
    void theStorageIsDownWhenMissingAndNeverDownWhileNotSetUp(@TempDir Path folder) {
        assertEquals(CheckState.WORKING, state(folder.toString()));
        assertEquals(CheckState.DOWN, state(folder.resolve("gone").toString()));
        assertEquals(CheckState.DOWN, state(folder.toString(), folder.resolve("gone").toString()));
        Verdict none = new ProcessingStorageCheck(List::of, true).check();
        assertEquals(CheckState.HAVING_PROBLEMS, none.state());
        assertEquals("LV-SYS-002", none.code().code());
    }

    private static CheckState state(String... folders) {
        CheckState state = new ProcessingStorageCheck(() -> List.of(folders), true).check().state();
        // A nearly full disk of the machine the test runs on is a problem, never down.
        return state == CheckState.HAVING_PROBLEMS ? CheckState.WORKING : state;
    }

    @Test
    void aRequiredCheckThatIsDownRefusesTheStartWithItsCode(@TempDir Path folder) {
        DependencyStatus status = new DependencyStatus(List.of(
                new ProcessingStorageCheck(() -> List.of(folder.resolve("gone").toString()), true)));
        RuntimeException refused = assertThrows(StartupChecks.Refused.class, () -> StartupChecks.require(status));
        assertTrue(refused.getMessage().contains("storage: MON_PATH_MISSING") && refused.getMessage().contains("Fix:"), refused.getMessage());
        assertEquals("storage:MON_PATH_MISSING", StartupFailureLoggerAccess.platformCodes(refused));
        StartupChecks.require(new DependencyStatus(List.of(
                new ProcessingStorageCheck(() -> List.of(folder.resolve("gone").toString()), false))));
    }

    @Test
    void theOverallStateIsTheWorst() {
        CheckResult working = result(Verdict.working());
        CheckResult problems = result(Verdict.problems(MonitoringCode.MON_DISK_ALMOST_FULL));
        CheckResult down = result(Verdict.down(MonitoringCode.MON_DB_UNREACHABLE));
        assertEquals(CheckState.WORKING, SupportStatusController.overall(List.of(working)));
        assertEquals(CheckState.HAVING_PROBLEMS, SupportStatusController.overall(List.of(working, problems)));
        assertEquals(CheckState.DOWN, SupportStatusController.overall(List.of(problems, down, working)));
        assertEquals(null, SupportStatusController.CheckView.of(working).whatToDo());
        assertFalse(SupportStatusController.CheckView.of(down).whatToDo().isBlank());
    }

    private static CheckResult result(Verdict v) {
        return CheckResult.of(new ProcessingStorageCheck(List::of, true), v, java.time.Instant.now(), java.time.Duration.ZERO);
    }

    @Test
    void everyLvCodeFitsTheSharedShapeUnderItsOwnName() {
        for (LvErrorCode lv : LvErrorCode.values()) {
            PlatformCode code = new LvPlatformCode(lv);
            assertEquals(lv.code(), code.code());
            assertEquals(lv.title(), code.userMessage());
            for (String text : List.of(code.cause(), code.fix(), code.supportNote(), code.kind().name(), code.family())) {
                assertFalse(text.isBlank(), lv.code());
            }
            assertEquals(lv.httpStatus().isPresent(), code.httpStatus().isPresent());
        }
    }

    @Test
    void anErrorAnswerHasTheSharedShapeAndAFailureIsLoggedUnderTheSameReference() {
        ReferenceId.setCurrent("R-0J5K2Q7M9X4D");
        try {
            ApiErrorResponse body = ApiErrorResponse.of("INTERNAL_ERROR", LvErrorCode.SYS_002, "corr");
            assertEquals("R-0J5K2Q7M9X4D", body.reference());
            assertEquals("LV-SYS-002", body.code());
            assertEquals(LvErrorCode.SYS_002.whatToDo(), body.whatToDo());
            assertTrue(body.details().isEmpty());
            new FailureReferences().beforeBodyWrite(body, null, null, null, null, null);
            assertEquals("R-0J5K2Q7M9X4D", FailureLog.recent(1).get(0).reference());
            assertEquals("LV-SYS-002", FailureLog.recent(1).get(0).code().code());
            new FailureReferences().beforeBodyWrite(ApiErrorResponse.of("X", LvErrorCode.INT_004, "corr"), null, null, null, null, null);
            assertEquals("LV-SYS-002", FailureLog.recent(1).get(0).code().code(), "a refused request is not a failure");
            ApiErrorResponse platform = body.withPlatformCode("LICENCE_TAMPERED", "Store the licence again.");
            assertEquals("LICENCE_TAMPERED", platform.details().get("platformCode"));
            assertEquals("Store the licence again.", platform.whatToDo());
        } finally {
            ReferenceId.setCurrent(null);
        }
        assertTrue(ReferenceId.isReference(ApiErrorResponse.of("X", LvErrorCode.SYS_001, "corr").reference()));
    }

    /** The logger's package-private reader of the platform codes. */
    private static final class StartupFailureLoggerAccess {
        static String platformCodes(Throwable failure) {
            try {
                var method = StartupFailureLogger.class.getDeclaredMethod("platformCodes", Throwable.class);
                method.setAccessible(true);
                return (String) method.invoke(null, failure);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }
}
