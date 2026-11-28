package com.securedoc.platform.infrastructure.delivery;

import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.Endpoint;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.Kind;
import com.documentplatform.integration.config.Limits;
import com.documentplatform.integration.config.SecretScope;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.profile.ProfileFolder;
import com.documentplatform.integration.profile.ProfileValidator;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.outbound.Installation;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.DestinationProfilePort;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.DestinationSettings;
import com.securedoc.platform.domain.integration.IntegrationConnection;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0087: a destination is its published profile (a file in the installation's profile folder, read at each use) plus
 * what IT saved with the connection. This makes platform-integration's configuration from the two. Nothing here knows
 * any particular destination.
 */
public final class DestinationProfiles implements DestinationProfilePort {

    public static final String PRODUCT = "LEXAVAULT";
    /** The per-document value and the app values a profile may declare; LexaVault fills them. */
    public static final String LEVEL_ATTRIBUTE = "sensitivityLevel";
    public static final String CASE_LEVEL_VALUE = "caseSensitivityLevel";
    public static final String REVISION_VALUE = "sourceRevision";
    /** The header of an API key when the profile names none. */
    private static final String API_KEY_HEADER = "X-API-Key";

    /** Why a destination can't be used: a code, never a value. */
    public static final class NotUsable extends RuntimeException {
        private final String code;

        NotUsable(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The profile, and the whole configuration for one delivery (or a test). */
    public record Resolved(IntegrationProfile profile, IntegrationConfig config) { }

    private final ProfileFolder folder;
    private final Installation installation;
    private final BiFunction<TenantId, String, SecretRef> entitySecret;
    private final Function<TenantId, Set<String>> caseFieldKeys;

    /**
     * @param folder        the profile folder; blank: no profiles
     * @param entitySecret  the app's rule for a secret reference an entity may name; throws for any other
     * @param caseFieldKeys the entity's active case fields
     */
    public DestinationProfiles(String folder, Installation installation,
                               BiFunction<TenantId, String, SecretRef> entitySecret,
                               Function<TenantId, Set<String>> caseFieldKeys) {
        this.folder = folder == null || folder.isBlank() ? null : new ProfileFolder(Path.of(folder.strip()));
        this.installation = Objects.requireNonNull(installation);
        this.entitySecret = Objects.requireNonNull(entitySecret);
        this.caseFieldKeys = Objects.requireNonNull(caseFieldKeys);
    }

    // ---------------------------------------------------------------- for IT's screens

    @Override
    public List<Profile> list() {
        if (folder == null) return List.of();
        List<Profile> out = new ArrayList<>();
        for (String file : folder.files()) {
            ProfileValidator.Result r = folder.check(file);
            String unsupported = r.valid() ? unsupported(r.profile()) : null;
            if (!r.valid() || unsupported != null) {
                List<String> problems = unsupported != null ? List.of(unsupported)
                        : r.problems().stream().map(p -> p.code().name() + (p.pointer().isEmpty() ? "" : " " + p.pointer()))
                        .distinct().limit(20).toList();
                out.add(new Profile(file, null, null, null, List.of(), List.of(), false, false, false, false, List.of(), problems));
                continue;
            }
            IntegrationProfile p = r.profile();
            out.add(new Profile(file, p.name(), p.version(), p.description(), p.signInTypes(),
                    p.fields() == null ? List.of() : p.fields().expected().stream()
                            .map(f -> f.required() ? f.name() + "*" : f.name()).toList(),
                    p.fields() != null, p.fields() != null && p.fields().others(), carriesLevel(p), carriesCaseLevel(p),
                    p.notes(), List.of()));
        }
        return out;
    }

    @Override
    public IntegrationConnection checked(IntegrationConnection c) {
        DestinationSettings d = c.destination();
        IntegrationProfile profile;
        try {
            profile = load(d);
        } catch (NotUsable e) {
            throw refused("The destination's profile can't be used: " + e.code());
        }
        Set<String> known = caseFieldKeys.apply(c.tenantId());
        if (!known.containsAll(d.fieldMapping().values())) {
            throw refused("A field is mapped from a case field this entity does not have");
        }
        if (!known.containsAll(d.sensitiveFields())) {
            throw refused("A sensitive field is allowed that this entity does not have");
        }
        if (profile.fields() == null && (!d.fieldMapping().isEmpty() || d.sendAllFields())) {
            throw refused("This destination's profile takes no case fields");
        }
        IntegrationConnection stored = c.withDestination(d.withProfile(profile.name(), profile.version()));
        // Without a credential a connection can only be stored switched off (IntegrationConnection checks that).
        if (!c.enabled() && (c.secretReference() == null || c.endpoint() == null)) return stored;
        try {
            resolve(stored, key -> true, known);
        } catch (NotUsable e) {
            throw refused(e.getMessage() + " (" + e.code() + ")");
        }
        return stored;
    }

    private static ValidationException refused(String what) {
        return new ValidationException(LvErrorCode.CFG_003, what);
    }

    // ---------------------------------------------------------------- for a delivery

    public static boolean carriesLevel(IntegrationProfile p) {
        return p.documentAttributes().stream().anyMatch(a -> a.name().equals(LEVEL_ATTRIBUTE));
    }

    public static boolean carriesCaseLevel(IntegrationProfile p) {
        return p.values().stream().anyMatch(v -> v.name().equals(CASE_LEVEL_VALUE));
    }

    /** The profile the connection was saved with: the same name and version, or nothing is sent. */
    public IntegrationProfile profile(IntegrationConnection c) {
        DestinationSettings d = c.destination();
        if (d == null) throw new NotUsable("DESTINATION_PROFILE_MISSING", "The destination's profile is not chosen");
        IntegrationProfile p = load(d);
        if (!p.name().equals(d.profileName()) || !p.version().equals(d.profileVersion())) {
            throw new NotUsable("DESTINATION_PROFILE_CHANGED", "The destination's profile file is not the one that was saved");
        }
        return p;
    }

    /** The whole configuration, with every case field of the entity: for a test or a check. */
    public Resolved resolve(IntegrationConnection c) {
        return resolve(c, key -> true, caseFieldKeys.apply(c.tenantId()));
    }

    /**
     * The configuration for one case.
     *
     * @param holds whether the case holds a value of this case field
     * @param keys  the entity's case fields
     */
    public Resolved resolve(IntegrationConnection c, Predicate<String> holds, Collection<String> keys) {
        IntegrationProfile p = profile(c);
        DestinationSettings d = c.destination();
        Map<String, String> mapping = mapping(p, d, holds, keys);
        Set<String> sensitive = new LinkedHashSet<>(d.sensitiveFields());
        sensitive.retainAll(mapping.values());
        // Hardening (task 0110): outbound addresses limited, checked again after the name is resolved.
        if (com.securedoc.platform.application.integration.OutboundAddressRule.check(c.endpoint(), c.allowedHosts())
                .isPresent()) {
            throw new NotUsable(IntegrationCode.INT_CONFIG_INVALID.name(), "The destination's address is not allowed");
        }
        IntegrationConfig made;
        try {
            made = p.complete(new IntegrationProfile.Completion(c.tenantId().value(), PRODUCT, c.name(),
                    new Endpoint(URI.create(c.endpoint()), true), signIn(c, p), new LinkedHashMap<>(mapping), sensitive,
                    d.settings()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new NotUsable(IntegrationCode.INT_CONFIG_INVALID.name(), "The destination's settings are not complete");
        }
        Limits l = made.limits();
        Limits limits = new Limits(l.connectTimeout(), Duration.ofSeconds(c.timeoutSeconds()), l.maxFileBytes(),
                l.maxDocuments(), l.maxResponseBytes(), l.maxPackageBytes());
        // The connection's own id, so it is the same configuration for every delivery and log line. CHECK_ONLY: a test
        // never sends a document, whatever the profile would allow.
        IntegrationConfig config = new IntegrationConfig(c.id().value(), made.entity(), made.product(), made.kind(),
                made.name(), true, made.endpoint(), made.signIn(), limits, made.steps(), made.errorCodePointer(),
                made.rules(), made.health(), IntegrationConfig.TestMode.CHECK_ONLY, made.sendCorrelationId(),
                made.profile(), made.allowedSensitiveFields(), made.settings());
        ConfigCheck.Report check = ConfigCheck.check(config, installation);
        if (!check.ok()) {
            throw new NotUsable(check.errors().get(0).code().name(), "The destination's settings are refused");
        }
        return new Resolved(p, config);
    }

    /** The destination's name to the case field's key, for the fields this case holds and the ones the destination needs. */
    private static Map<String, String> mapping(IntegrationProfile p, DestinationSettings d, Predicate<String> holds,
                                               Collection<String> keys) {
        if (p.fields() == null) return d.fieldMapping(); // refused by the profile when it is not empty
        Set<String> required = new LinkedHashSet<>();
        Set<String> listed = new LinkedHashSet<>();
        for (IntegrationProfile.ExpectedField f : p.fields().expected()) {
            listed.add(f.name());
            if (f.required()) required.add(f.name());
        }
        Map<String, String> out = new LinkedHashMap<>();
        d.fieldMapping().forEach((name, key) -> {
            if (required.contains(name) || holds.test(key)) out.put(name, key);
        });
        if (d.sendAllFields()) {
            for (String key : new TreeSet<>(keys)) {
                if (d.fieldMapping().containsValue(key) || d.fieldMapping().containsKey(key)) continue;
                if (required.contains(key) || ((p.fields().others() || listed.contains(key)) && holds.test(key))) out.put(key, key);
            }
        }
        return out;
    }

    private IntegrationProfile load(DestinationSettings d) {
        if (folder == null) {
            throw new NotUsable("DESTINATION_PROFILE_FOLDER_MISSING", "This installation has no profile folder");
        }
        ProfileValidator.Result r = folder.check(d.profileFile());
        if (!r.valid()) {
            throw new NotUsable(r.problems().get(0).code().name(), "The destination's profile file can't be used");
        }
        String unsupported = unsupported(r.profile());
        if (unsupported != null) throw new NotUsable(unsupported, "The destination's profile is of a kind LexaVault does not use");
        return r.profile();
    }

    /** Push only, and at most one run of per-document calls (its progress is kept document by document). */
    private static String unsupported(IntegrationProfile p) {
        if (p.kind() != Kind.API_PUSH) return IntegrationCode.INT_KIND_NOT_SUPPORTED.name();
        int blocks = 0;
        boolean inBlock = false;
        for (CallStep s : p.steps()) {
            boolean each = s.repeat() == CallStep.Repeat.EACH_DOCUMENT;
            if (each && !inBlock) blocks++;
            inBlock = each;
        }
        return blocks > 1 ? IntegrationCode.INT_PROFILE_STEPS.name() : null;
    }

    // ---------------------------------------------------------------- sign-in

    private SignIn signIn(IntegrationConnection c, IntegrationProfile p) {
        DestinationSettings d = c.destination();
        return switch (c.authenticationType()) {
            case NONE -> new SignIn.None();
            case BASIC -> new SignIn.Basic(d.clientId(), secret(c));
            case API_KEY -> new SignIn.ApiKey(p.apiKeyHeader() == null ? API_KEY_HEADER : p.apiKeyHeader(), secret(c));
            case JWT -> new SignIn.BearerFixed(secret(c));
            case OAUTH2_CLIENT_CREDENTIALS -> {
                SignIn.OAuth2ClientCredentials s = SignIn.OAuth2ClientCredentials.secret(
                        URI.create(d.tokenAddress()), d.clientId(), secret(c), d.scope());
                if (d.audience() != null) s = s.withAudience(d.audience());
                if (d.clientSecretBasic()) s = s.withClientAuth(SignIn.OAuth2ClientCredentials.ClientAuth.BASIC);
                yield s;
            }
            case MTLS -> throw new NotUsable(IntegrationCode.INT_CONFIG_INVALID.name(),
                    "Client certificates for a destination are not available");
        };
    }

    /** The reference as platform-integration wants it: under the entity. Only the app's own rule decides what it may name. */
    private SecretRef secret(IntegrationConnection c) {
        try {
            SecretRef own = entitySecret.apply(c.tenantId(), c.secretReference());
            return SecretScope.of(c.tenantId().value(), own.value());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new NotUsable(IntegrationCode.INT_CONFIG_SECRET_SCOPE.name(),
                    "The secret reference is not one this entity may use");
        }
    }

    private static final Pattern SCOPED = Pattern.compile(SecretScope.ROOT + "/([0-9a-f-]{36})/(.+)");

    /** Reads a scoped reference through the app's resolver, after the app's own rule for that entity. */
    public SecretResolver secretResolver(SecretResolver app) {
        return ref -> {
            Matcher m = SCOPED.matcher(ref.value());
            if (!m.matches()) return Optional.<Secret>empty();
            SecretRef own;
            try {
                own = entitySecret.apply(TenantId.of(UUID.fromString(m.group(1))), m.group(2));
            } catch (IllegalArgumentException e) {
                return Optional.<Secret>empty();
            }
            return app.resolve(own);
        };
    }
}
