package com.securedoc.platform.api.config;

import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.domain.error.LvErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Task 0026: when LexaVault stops at start, one log line gives the LexaVault code, the failure's type and where it
 * came from (class, method and line of the nearest LexaVault frame). Codes and types only, never the message, which
 * can hold a setting's value.
 *
 * <ul>
 *   <li>{@code LV-SYS-003} the database (or its migration) failed;</li>
 *   <li>{@code LV-AI-007} the AI model could not be loaded ({@code OllamaModelPreloader});</li>
 *   <li>{@code LV-SYS-005} anything else: a start-up setting is wrong (#324, #326, #328, #349 to #351).</li>
 * </ul>
 */
public class StartupFailureLogger implements ApplicationListener<ApplicationFailedEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupFailureLogger.class);
    private static final String PRELOADER = OllamaModelPreloader.class.getName();
    private static final int MAX_DEPTH = 16;

    @Override
    public void onApplicationEvent(ApplicationFailedEvent event) {
        Throwable failure = event.getException();
        Throwable root = root(failure);
        // Task 0057: the shared platform's own codes of the refusal (several for start-up checks), never its message.
        log.error("LV_STARTUP_FAILED code={} type={} origin={} platformCode={}", codeFor(failure).code(),
                root == null ? "unknown" : root.getClass().getSimpleName(), origin(failure), platformCodes(failure));
    }

    static String platformCodes(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof com.documentplatform.monitoring.StartupChecks.Refused refused) {
                return refused.down().stream().map(r -> r.name() + ":" + r.code().code())
                        .collect(java.util.stream.Collectors.joining(","));
            }
            if (t.getCause() == t) break;
        }
        return com.securedoc.platform.api.error.PlatformRefusals.codeOf(failure)
                .map(com.documentplatform.errors.PlatformCode::code).orElse("none");
    }

    /** The code for a start-up failure, by type and origin only. */
    static LvErrorCode codeFor(Throwable failure) {
        int depth = 0;
        // Task 0057: a refused licence has LexaVault's own code; the platform's is beside it in the log line.
        for (Throwable current = failure; current != null && depth < MAX_DEPTH; current = current.getCause(), depth++) {
            if (current instanceof com.documentplatform.licensing.LicenceException licence) {
                return licence.code() == com.documentplatform.licensing.LicenceRefusal.LICENCE_MISSING
                        ? LvErrorCode.LIC_001 : LvErrorCode.LIC_003;
            }
            if (current.getCause() == current) break;
        }
        depth = 0;
        for (Throwable current = failure; current != null && depth < MAX_DEPTH; current = current.getCause(), depth++) {
            if (LvErrorCodes.forPlatformFailure(current) == LvErrorCode.SYS_003) return LvErrorCode.SYS_003;
            if (current.getCause() == current) break;
        }
        depth = 0;
        for (Throwable current = failure; current != null && depth < MAX_DEPTH; current = current.getCause(), depth++) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (PRELOADER.equals(frame.getClassName())) return LvErrorCode.AI_007;
            }
            if (current.getCause() == current) break;
        }
        return LvErrorCode.SYS_005;
    }

    private static Throwable root(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && current.getCause() != null && current.getCause() != current
                && depth < MAX_DEPTH; depth++) {
            current = current.getCause();
        }
        return current;
    }

    /** The nearest LexaVault frame in the cause chain, deepest cause first: code, never data. */
    static String origin(Throwable failure) {
        Throwable current = root(failure);
        while (current != null) {
            for (StackTraceElement frame : current.getStackTrace()) {
                if (frame.getClassName().startsWith("com.securedoc.")) {
                    String type = frame.getClassName();
                    return type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                }
            }
            current = current == failure ? null : parentOf(failure, current);
        }
        return "unknown";
    }

    private static Throwable parentOf(Throwable top, Throwable child) {
        for (Throwable current = top; current != null; current = current.getCause()) {
            if (current.getCause() == child) return current;
            if (current.getCause() == current) break;
        }
        return null;
    }
}
