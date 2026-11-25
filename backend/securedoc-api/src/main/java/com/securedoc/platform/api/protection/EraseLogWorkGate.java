package com.securedoc.platform.api.protection;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseLogError;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.WorkGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/** Task 0060: closed while platform-core's erase guard is, and in a process started only for the replay or the seal. */
@Component
public class EraseLogWorkGate implements WorkGate, SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(EraseLogWorkGate.class);
    private static final long SEAL_RETRY_NANOS = 15_000_000_000L;

    private final EraseGuard guard;
    private final EraseLog eraseLog;
    private final boolean commandRun;
    private final AtomicLong nextSealTry = new AtomicLong();

    public EraseLogWorkGate(EraseGuard guard, EraseLog eraseLog, ApplicationArguments arguments) {
        this.guard = guard;
        this.eraseLog = eraseLog;
        this.commandRun = arguments.containsOption("erase-replay") || arguments.containsOption("erase-log-seal")
                // Task 0075: a process started only to activate the first entity takes no work either.
                || arguments.containsOption(com.securedoc.platform.api.identity.FirstEntityCommand.OPTION);
    }

    @Override
    public boolean mayTakeWork() {
        if (commandRun) return false;
        if (guard.mayTakeWork()) return true;
        sealNewLog();
        return guard.mayTakeWork();
    }

    /**
     * A new, empty keyed log has no seal yet: platform-core seals it with its first entry, but its guard takes no work
     * before that. It is sealed here, as the first entry would. A log with entries is never sealed here (that is the
     * explicit --erase-log-seal), and one that lost its entries is still found by the database's position.
     */
    private void sealNewLog() {
        if (guard.state().code() != EraseLogError.ERASE_LOG_NOT_SEALED) return;
        long now = System.nanoTime();
        long next = nextSealTry.get();
        if (next != 0 && now - next < 0 || !nextSealTry.compareAndSet(next, now + SEAL_RETRY_NANOS)) return;
        try {
            if (!eraseLog.keyed() || eraseLog.head().seq() != 0 || eraseLog.sealRecord().isPresent()) return;
            eraseLog.seal();
            guard.checkAtStartup();
            log.info("ERASE_LOG_NEW_LOG_SEALED");
        } catch (RuntimeException notYet) {
            log.warn("ERASE_LOG_NEW_LOG_NOT_SEALED type={}", notYet.getClass().getSimpleName());
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        mayTakeWork();
    }

    @Override
    public void requireWork() {
        if (mayTakeWork()) return;
        boolean restored = commandRun || guard.state().code() == EraseLogError.ERASE_LOG_RESTORE_DETECTED;
        throw new TechnicalDependencyException(TechnicalDependency.ERASE_LOG,
                restored ? TechnicalFailureCode.ERASE_REPLAY_REQUIRED : TechnicalFailureCode.ERASE_LOG_UNAVAILABLE, true,
                restored ? "Restored from a backup: run the erase replay before starting work" : "The erase log is not available");
    }
}
