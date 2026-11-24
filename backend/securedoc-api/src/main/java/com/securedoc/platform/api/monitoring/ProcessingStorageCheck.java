package com.securedoc.platform.api.monitoring;

import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;
import com.documentplatform.monitoring.checks.FileLocationCheck;
import com.securedoc.platform.api.error.LvPlatformCode;
import com.securedoc.platform.domain.error.LvErrorCode;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Task 0057: the document storage in use (the default one and every customer's own). Down when a folder is missing or the
 * app may not read or write it; having problems when a disk is nearly full (also above 95 %, so that a server can still be
 * started to free space) and while no storage is set up yet (a new installation starts without one; Platform Operations
 * sets it up in the app).
 */
public final class ProcessingStorageCheck implements DependencyCheck {

    static final String NAME = "storage";

    private final Supplier<List<String>> roots;
    private final boolean required;

    /** @param roots the folders in use now; an error of it is reported as such by the check */
    public ProcessingStorageCheck(Supplier<List<String>> roots, boolean required) {
        this.roots = Objects.requireNonNull(roots);
        this.required = required;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String group() {
        return "services";
    }

    @Override
    public boolean requiredAtStartup() {
        return required;
    }

    @Override
    public Verdict check() {
        List<String> folders = roots.get();
        if (folders.isEmpty()) return Verdict.problems(new LvPlatformCode(LvErrorCode.SYS_002));
        Verdict problem = null;
        for (String folder : folders) {
            Verdict verdict;
            try {
                verdict = new FileLocationCheck("processing-storage", Path.of(folder), true, 90, 95, required).check();
            } catch (InvalidPathException e) {
                verdict = Verdict.down(MonitoringCode.MON_PATH_MISSING, Map.of("path", "not a valid path",
                        "location", "processing-storage"));
            }
            if (verdict.state() == CheckState.DOWN && verdict.code() != MonitoringCode.MON_DISK_FULL) return verdict;
            if (verdict.state() != CheckState.WORKING) problem = Verdict.problems(verdict.code(), verdict.details());
        }
        return problem == null ? Verdict.working() : problem;
    }
}
