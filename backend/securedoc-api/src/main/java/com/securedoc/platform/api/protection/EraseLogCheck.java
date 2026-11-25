package com.securedoc.platform.api.protection;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.Verdict;
import com.securedoc.platform.api.error.LvPlatformCode;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.documentplatform.eraselog.EraseLogError;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Task 0060: the erase log on the Support status page: the platform's code, entry numbers and ids only. Never refuses a start. */
public final class EraseLogCheck implements DependencyCheck {
    private final EraseGuard guard;

    public EraseLogCheck(EraseGuard guard) {
        this.guard = Objects.requireNonNull(guard);
    }

    @Override
    public String name() {
        return "erase-log";
    }

    @Override
    public String group() {
        return "services";
    }

    @Override
    public boolean requiredAtStartup() {
        return false;
    }

    @Override
    public Verdict check() {
        EraseGuard.State state = guard.state();
        Map<String, String> details = new LinkedHashMap<>();
        if (state.code() != null) details.put("code", state.code().name());
        if (state.pending() > 0) details.put("pending", Long.toString(state.pending()));
        if (state.lastFailure() != null) {
            details.put("entry", Long.toString(state.lastFailure().seq()));
            details.put("kind", state.lastFailure().kind());
            if (state.lastFailure().handlerCode() != null) details.put("handlerCode", state.lastFailure().handlerCode());
        }
        if (!state.mayTakeWork()) {
            return Verdict.down(new LvPlatformCode(state.code() == EraseLogError.ERASE_LOG_RESTORE_DETECTED
                    ? LvErrorCode.SYS_013 : LvErrorCode.SYS_014), details);
        }
        return state.lastFailure() == null ? Verdict.working()
                : Verdict.problems(new LvPlatformCode(LvErrorCode.SYS_014), details);
    }
}
