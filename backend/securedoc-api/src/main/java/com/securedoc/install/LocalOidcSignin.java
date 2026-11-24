package com.securedoc.install;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sign-in file of the local OIDC start (task 0041): the demo sign-in file ({@code install/demo/signin-demo.json})
 * with the local login system put in, so setup loads the login connection itself.
 *
 * <p>Platform-core 0.2.0 lets setup take an {@code http} issuer on {@code localhost}, {@code 127.0.0.1} or {@code [::1]}
 * when the licence is signed by a key marked {@code "development": true} in the trusted-keys file (the demo licence and
 * {@code install/demo/development-trusted-keys.json}). Before that, the demo step wrote the connection itself
 * ({@code --oidc-issuer}, task 0037); that workaround is gone.
 *
 * <pre>
 * local-oidc-signin --from install/demo/signin-demo.json --out DIR --issuer URL --provider-id ID --audience AUD
 *                   --browser-client-id ID
 * </pre>
 * Writes {@code DIR/signin-local-oidc.json} and copies the role mapping file it names next to it. Only the four values
 * change; the customer, mode and role mapping stay the demo file's. It reads no database and holds no secret. Setup
 * itself checks the issuer (an http issuer on any other host is refused there, {@code SETUP_HTTP_ISSUER_NOT_ALLOWED}).
 * Exit codes: 0 written, 2 refused.
 */
public final class LocalOidcSignin {

    static final String FILE_NAME = "signin-local-oidc.json";

    private final PrintStream out;
    private final PrintStream err;

    LocalOidcSignin(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new LocalOidcSignin(System.out, System.err).run(args));
    }

    int run(String[] args) {
        try {
            Map<String, String> o = parse(args);
            Path written = write(Path.of(o.get("--from")), Path.of(o.get("--out")), issuer(o.get("--issuer")),
                    o.get("--provider-id").trim(), o.get("--audience").trim(), o.get("--browser-client-id").trim());
            out.println("Sign-in file for the local OIDC start: " + written);
            return 0;
        } catch (InstallException e) {
            err.println(e.code() + ": " + e.getMessage());
            return e.exitCode();
        }
    }

    static Map<String, String> parse(String[] args) {
        List<String> known = List.of("--from", "--out", "--issuer", "--provider-id", "--audience", "--browser-client-id");
        Map<String, String> o = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!known.contains(args[i]) || i + 1 >= args.length || o.containsKey(args[i]) || args[i + 1].isBlank()) {
                throw usage("unknown, repeated or empty option " + args[i]);
            }
            o.put(args[i], args[i + 1]);
        }
        for (String k : known) {
            if (!o.containsKey(k)) throw usage(k + " is required");
        }
        return o;
    }

    static URI issuer(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            throw usage("--issuer is not an address");
        }
        if (!uri.isAbsolute() || uri.getHost() == null) throw usage("--issuer must be an absolute address");
        return uri;
    }

    static Path write(Path from, Path outDir, URI issuer, String providerId, String audience, String browserClientId) {
        ObjectMapper json = JsonMapper.builderWithJackson2Defaults().enable(SerializationFeature.INDENT_OUTPUT).build();
        JsonNode root;
        try {
            root = json.readTree(Files.readString(from, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new InstallException(2, "SIGNIN_UNREADABLE", "the sign-in file " + from + " could not be read");
        }
        if (!(root instanceof ObjectNode file) || !(file.get("loginSystem") instanceof ObjectNode login)
                || !(file.path("products").get(LexaVaultProduct.CODE) instanceof ObjectNode product)
                || !product.path("roleMappingFile").isString()) {
            throw new InstallException(2, "SIGNIN_UNREADABLE", "the sign-in file " + from
                    + " has no loginSystem, or no " + LexaVaultProduct.CODE + " product with a roleMappingFile");
        }
        login.put("issuer", issuer.toString());
        login.put("providerId", providerId);
        product.put("browserClientId", browserClientId);
        product.put("apiAudience", audience);

        String mapping = product.get("roleMappingFile").asString();
        Path mappingFrom = from.toAbsolutePath().getParent().resolve(mapping).normalize();
        Path mappingName = Path.of(mapping).getFileName();
        try {
            Files.createDirectories(outDir);
            Files.copy(mappingFrom, outDir.resolve(mappingName), StandardCopyOption.REPLACE_EXISTING);
            product.put("roleMappingFile", mappingName.toString());
            Path target = outDir.resolve(FILE_NAME);
            // UTF-8 without a byte order mark.
            Files.writeString(target, json.writeValueAsString(file) + System.lineSeparator(), StandardCharsets.UTF_8);
            return target;
        } catch (IOException e) {
            throw new InstallException(2, "SIGNIN_NOT_WRITTEN", "the sign-in file could not be written to " + outDir
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static InstallException usage(String why) {
        return new InstallException(2, "BAD_OPTION", why + ". Usage: local-oidc-signin --from FILE --out DIR "
                + "--issuer URL --provider-id ID --audience AUD --browser-client-id ID");
    }
}
