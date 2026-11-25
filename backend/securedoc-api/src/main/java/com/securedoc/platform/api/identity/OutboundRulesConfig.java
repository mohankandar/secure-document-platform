package com.securedoc.platform.api.identity;

import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.spring.OutboundIssuerFetch;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.outbound.ProxySettings;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.outbound.spring.OutboundProperties;
import com.securedoc.platform.api.notifications.AppSecretResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * The platform's safe outbound rules for LexaVault's calls: the test sign-in's read of the login system's discovery
 * document and keys (task 0054), mail, and delivery to a destination (task 0087). https only, the address guard (no cloud-metadata or link-local address ever, no
 * internal address unless the installation allows it), connection pinning, no redirects, timeouts and size limits.
 * Settings: {@code platform.outbound.installation}, {@code .allow-internal-addresses} (off by default), {@code
 * .private-ca-file} and {@code .proxy.host/.port/.bypass}. A setting that is wrong stops the app ({@code
 * OUTBOUND_SETTINGS_INVALID}), never with a value in the message.
 *
 * <p>A proxy with sign-in is allowed since task 0059 ({@code .proxy.user}, {@code .proxy.password-secret}): the password is
 * a reference that {@link AppSecretResolver} reads at each call, never the password.
 *
 * <p>An {@code http} login system on this machine needs no setting here: the fetch takes it only when the test sign-in has
 * found it allowed, that is under a licence signed by a development key, which only a development build trusts.
 */
@Configuration
@EnableConfigurationProperties(OutboundProperties.class)
public class OutboundRulesConfig {

    @Bean
    Installation outboundInstallation(OutboundProperties settings) {
        return installation(settings);
    }

    @Bean
    AppSecretResolver appSecretResolver(@Value("${securedoc.secrets.directory:}") String directory) {
        return new AppSecretResolver(System::getenv, directory);
    }

    @Bean(destroyMethod = "close")
    Outbound outbound(Installation installation, AppSecretResolver secrets) {
        return new Outbound(installation, secrets);
    }

    @Bean
    IssuerFetch signInTestIssuerFetch(Outbound outbound) {
        return new OutboundIssuerFetch(outbound);
    }

    static Installation installation(OutboundProperties settings) {
        if (settings.getInstallation() == null) throw invalid("installation is required: ON_PREMISES or HOSTED");
        boolean hosted = settings.getInstallation() == OutboundProperties.InstallationMode.HOSTED;
        if (hosted && settings.isAllowInternalAddresses()) {
            throw invalid("allow-internal-addresses is never allowed on the hosted service");
        }
        if (hosted && settings.isAllowSignInNone()) throw invalid("allow-sign-in-none is never allowed on the hosted service");
        return new Installation(hosted, settings.isAllowInternalAddresses(), settings.isAllowSignInNone(), proxy(settings.getProxy()),
                privateCa(settings.getPrivateCaFile()));
    }

    private static ProxySettings proxy(OutboundProperties.Proxy proxy) {
        boolean user = present(proxy.getUser());
        boolean password = present(proxy.getPasswordSecret());
        if (proxy.getHost() == null && proxy.getPort() == null && proxy.getBypass().isEmpty() && !user && !password) return null;
        if (proxy.getHost() == null || proxy.getPort() == null) throw invalid("proxy.host and proxy.port go together");
        if (user != password) throw invalid("proxy.user and proxy.password-secret go together");
        SecretRef secret = null;
        if (password) {
            try {
                secret = AppSecretResolver.installRef(proxy.getPasswordSecret().strip());
            } catch (IllegalArgumentException e) {
                throw invalid("proxy.password-secret is not a secret reference of this installation (docs/install/NOTIFICATIONS.md)");
            }
        }
        try {
            ProxySettings plain = new ProxySettings(proxy.getHost().strip(), proxy.getPort(), proxy.getBypass());
            return user ? plain.withSignIn(proxy.getUser().strip(), secret) : plain;
        } catch (IllegalArgumentException e) {
            throw invalid("proxy is not valid");
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String privateCa(String file) {
        if (file == null || file.isBlank()) return null;
        try {
            Path path = Path.of(file.strip());
            if (!path.isAbsolute()) throw invalid("private-ca-file must be an absolute path");
            String pem = Files.readString(path, StandardCharsets.US_ASCII);
            Installation.onPremises().withPrivateCa(pem); // refuses a bundle that is not certificates
            return pem;
        } catch (IOException | InvalidPathException e) {
            throw invalid("private-ca-file cannot be read");
        } catch (IllegalArgumentException e) {
            throw invalid("private-ca-file is not a PEM bundle of certificates");
        }
    }

    private static IllegalStateException invalid(String problem) {
        return new IllegalStateException("OUTBOUND_SETTINGS_INVALID: " + OutboundProperties.PREFIX + "." + problem);
    }
}
