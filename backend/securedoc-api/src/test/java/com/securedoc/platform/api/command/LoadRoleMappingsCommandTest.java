package com.securedoc.platform.api.command;

import com.documentplatform.identity.mapping.Grant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.api.security.oidc.RoleMappingLoader;
import com.securedoc.platform.infrastructure.identity.RoleMappingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadRoleMappingsCommandTest {

    static final UUID A = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a");

    @TempDir Path dir;
    final Map<UUID, Set<Grant>> grants = new HashMap<>();
    final List<Object> audit = new ArrayList<>();
    final RoleMappingStore store = new RoleMappingStore() {
        @Override public Optional<Target> describe(com.documentplatform.identity.admission.EntityId e) {
            return e.value().equals(A) ? Optional.of(new Target("Acme Lending", true, true)) : Optional.empty();
        }
        @Override public void lock(com.documentplatform.identity.admission.EntityId t) { }
        @Override public Optional<String> activeIssuer(com.documentplatform.identity.admission.EntityId t) { return Optional.of("https://issuer"); }
        @Override public Set<Grant> grants(com.documentplatform.identity.admission.EntityId t, String i) { return new LinkedHashSet<>(grants.getOrDefault(t.value(), Set.of())); }
        @Override public void add(com.documentplatform.identity.admission.EntityId t, String i, Grant g) { grants.computeIfAbsent(t.value(), k -> new LinkedHashSet<>()).add(g); }
        @Override public void remove(com.documentplatform.identity.admission.EntityId t, String i, Grant g) { grants.get(t.value()).remove(g); }
    };
    final LoadRoleMappingsCommand command = new LoadRoleMappingsCommand(new RoleMappingLoader(store, audit::add, new ObjectMapper()));

    private record Run(int code, String out) { }

    private Run run(String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = command.run(new DefaultApplicationArguments(args), new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new Run(code, bytes.toString(StandardCharsets.UTF_8));
    }

    private Path file(String rows) throws IOException {
        return Files.writeString(dir.resolve("acme.csv"), "entity_id,entity_name,group,role\n" + rows, StandardCharsets.UTF_8);
    }

    @Test
    void loadThenLoadAgain() throws IOException {
        Path f = file(A + ",Acme Lending,Lending-Processors,DOCUMENT_PROCESSOR\n");
        Run first = run("--lexavault.command=load-role-mappings", "--file=" + f);
        assertEquals(ProductCommand.OK, first.code(), first.out());
        assertTrue(first.out().contains("Acme Lending (" + A + "): 1 to add, 0 to remove, 0 unchanged"), first.out());
        assertTrue(first.out().contains("Loaded. 1 entity changed."), first.out());
        Run second = run("--lexavault.command=load-role-mappings", "--file=" + f);
        assertEquals(ProductCommand.OK, second.code());
        assertTrue(second.out().contains("No changes."), second.out());
        assertEquals(1, audit.size());
    }

    @Test
    void dryRunWritesNothing() throws IOException {
        Run r = run("--file=" + file(A + ",Acme Lending,g,DOCUMENT_PROCESSOR\n"), "--dry-run");
        assertEquals(ProductCommand.OK, r.code());
        assertTrue(r.out().contains("Dry run: the file is valid. Nothing was written."), r.out());
        assertTrue(grants.isEmpty());
    }

    @Test
    void aRefusedFileExitsOneAndPrintsEveryError() throws IOException {
        Run r = run("--file=" + file(A + ",Acme Lending,g,DOCUMENT_PROCESOR\n" + A + ",Acme Ltd,h,REPORT_VIEWER\n"));
        assertEquals(ProductCommand.REFUSED, r.code());
        assertTrue(r.out().contains("line 2: unknown role \"DOCUMENT_PROCESOR\""), r.out());
        assertTrue(r.out().contains("line 3: entity_name \"Acme Ltd\" does not match the entity's name \"Acme Lending\""), r.out());
        assertTrue(grants.isEmpty());
    }

    @Test
    void commandLineMistakesExitTwo() {
        assertEquals(ProductCommand.USAGE, run().code());
        assertEquals(ProductCommand.USAGE, run("--file=" + dir.resolve("missing.csv")).code());
        assertEquals(ProductCommand.USAGE, run("--file=a.csv", "--file=b.csv").code());
        assertEquals(ProductCommand.USAGE, run("--file=" + dir, "--dryrun").code());
        assertEquals(ProductCommand.USAGE, run("--file=" + dir).code(), "a directory is not a file");
    }

    @Test
    void helpExplainsThatAnEntityAppearsOnlyThroughItsRows() {
        Run r = run("--help");
        assertEquals(ProductCommand.OK, r.code());
        assertTrue(r.out().contains("An entity appears only"), r.out());
        assertTrue(r.out().contains("--dry-run"), r.out());
    }
}
