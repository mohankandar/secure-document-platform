package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.securedoc.platform.domain.identity.TenantId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0059: reads the secrets notifications and the outbound proxy need, by reference, at each use. Nothing is kept, stored,
 * returned or logged.
 *
 * <p>A reference is {@code env:NAME} (an environment variable of the app) or {@code file:NAME} (a file of that name in
 * {@code securedoc.secrets.directory}, as container platforms mount secrets). The name decides who may use it:
 * <ul>
 *   <li>{@code LEXAVAULT_SECRET_INSTALL_...}: the installation's (the link key, the proxy password), named in the install
 *       settings only;</li>
 *   <li>{@code LEXAVAULT_SECRET_<entity id, 32 characters>_...}: one entity's, the only ones its IT Admin can name on the
 *       screen.</li>
 * </ul>
 * Every other name is refused, so no screen can make the app send its database password, or another entity's secret, to a
 * server of someone's choice.
 */
public final class AppSecretResolver implements SecretResolver {

    static final String PREFIX = "LEXAVAULT_SECRET_";
    private static final Pattern SHAPE =
            Pattern.compile("(env|file):(LEXAVAULT_SECRET_(?:INSTALL|[0-9A-F]{32})_[A-Z0-9_]{1,80})");
    private static final int MAX_BYTES = 64 * 1024;

    private final UnaryOperator<String> environment;
    private final Path directory;

    /** @param directory the folder of {@code file:} secrets; blank for none */
    public AppSecretResolver(UnaryOperator<String> environment, String directory) {
        this.environment = Objects.requireNonNull(environment);
        this.directory = directory == null || directory.isBlank() ? null : Path.of(directory.strip());
    }

    /** What every name of one entity's secrets starts with. */
    public static String entityPrefix(TenantId entity) {
        return PREFIX + entity.value().toString().replace("-", "").toUpperCase(Locale.ROOT) + "_";
    }

    /** @throws IllegalArgumentException when it is not a reference of this entity; the value is never repeated */
    public static SecretRef entityRef(TenantId entity, String reference) {
        return named(reference, entityPrefix(entity));
    }

    /** @throws IllegalArgumentException when it is not a reference of the installation; the value is never repeated */
    public static SecretRef installRef(String reference) {
        return named(reference, PREFIX + "INSTALL_");
    }

    private static SecretRef named(String reference, String prefix) {
        Matcher m = reference == null ? null : SHAPE.matcher(reference);
        if (m == null || !m.matches() || !m.group(2).startsWith(prefix)) {
            throw new IllegalArgumentException("Not a secret reference that may be used here");
        }
        return SecretRef.of(reference);
    }

    @Override
    public Optional<Secret> resolve(SecretRef ref) {
        Matcher m = SHAPE.matcher(ref.value());
        if (!m.matches()) return Optional.empty();
        String value = m.group(1).equals("env") ? environment.apply(m.group(2)) : file(m.group(2));
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Secret.of(value.strip()));
    }

    /** A plain file of that name in the folder; the name's shape allows no path. */
    private String file(String name) {
        if (directory == null) return null;
        Path path = directory.resolve(name);
        try {
            // Container platforms link a secret to its current version, so links are followed; a folder is not a secret.
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Secret file could not be read");
        }
    }
}
