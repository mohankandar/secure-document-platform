package com.securedoc.platform.api.identity;

import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.LocalTestIssuers;
import com.documentplatform.identity.signin.OidcTestSignIn;
import com.documentplatform.identity.signin.TestReport;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.store.ConnectionOutcome;
import com.documentplatform.identity.store.LoginConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The read-only test of the stored login connection (task 0054): platform-identity's test sign-in
 * ({@link OidcTestSignIn}) against the ACTIVE connection's issuer, discovery document and keys only. No token is taken, and
 * nothing is stored or changed: {@code LoginConnections.validate} (which tests and changes a draft) is not used.
 *
 * <p>Not a way to make the server call other addresses: the only address is the stored connection's issuer (and the key
 * address its own discovery document names), through the platform's outbound rules ({@link OutboundRulesConfig}). One test
 * runs at a time per connection, and its answer is given again for {@link #KEEP_FOR}: many clicks are one call.
 */
@Component
public class SignInConnectionTest {

    static final Duration KEEP_FOR = Duration.ofSeconds(10);

    /**
     * One test's answer. {@code code} is null when it passed, else the exact platform code ({@code IDN_TEST_…},
     * {@code IDN_HTTP_ISSUER_NOT_ALLOWED}, {@code IDN_VALUES_INVALID}); {@code message} its words; {@code reason} the
     * outbound rule's code when a call was refused ({@code OUT_…}, {@code HTTP_404}, …).
     */
    public record Result(boolean passed, String code, String message, String reason, Instant testedAt,
                         String whatToDo) {
    }

    private record Kept(long version, Result result) {
    }

    private final OidcTestSignIn tester;
    private final DevelopmentLicence licence;
    private final Clock clock;
    private final Map<UUID, Kept> last = new ConcurrentHashMap<>();

    @Autowired
    public SignInConnectionTest(IssuerFetch fetch, DevelopmentLicence licence) {
        this(fetch, licence, Clock.systemUTC());
    }

    SignInConnectionTest(IssuerFetch fetch, DevelopmentLicence licence, Clock clock) {
        this.tester = new OidcTestSignIn(fetch, clock);
        this.licence = licence;
        this.clock = clock;
    }

    /** Tests the stored connection, or gives the answer of at most {@link #KEEP_FOR} ago for the same version of it. */
    public Result run(LoginConnection connection) {
        // compute: one at a time per connection; a second click waits and gets the same answer.
        return last.compute(connection.connectionId(), (id, kept) -> kept != null
                && kept.version() == connection.configurationVersion()
                && kept.result().testedAt().plus(KEEP_FOR).isAfter(clock.instant())
                ? kept : new Kept(connection.configurationVersion(), test(connection))).result();
    }

    private Result test(LoginConnection connection) {
        Optional<LocalTestIssuers.Problem> problem =
                LocalTestIssuers.check(connection.issuer(), licence.signedByDevelopmentKey());
        if (problem.isPresent()) {
            return refused(problem.get() == LocalTestIssuers.Problem.HTTP_NOT_ALLOWED
                    ? ConnectionOutcome.Result.IDN_HTTP_ISSUER_NOT_ALLOWED : ConnectionOutcome.Result.IDN_VALUES_INVALID);
        }
        if (connection.providerType() != IdentityProviderType.OIDC) {
            return refused(ConnectionOutcome.Result.IDN_VALUES_INVALID);
        }
        TestReport report = tester.run(connection.issuer(), connection.apiAudience(), TestSignIn.issuerOnly(),
                LocalTestIssuers.isLocalHttp(connection.issuer()));
        return new Result(report.passed(), report.failureCode().map(Enum::name).orElse(null), report.message(),
                report.reason(), clock.instant(), report.failureCode().map(com.documentplatform.errors.PlatformCode::fix).orElse(null));
    }

    private Result refused(ConnectionOutcome.Result code) {
        return new Result(false, code.name(), code.cause(), null, clock.instant(), code.fix());
    }
}
