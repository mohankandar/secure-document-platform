package com.securedoc.platform.api.command;

import com.documentplatform.identity.mapping.MappingError;
import com.documentplatform.identity.mapping.MappingPlan;
import com.securedoc.platform.api.security.oidc.RoleMappingLoader;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@code --lexavault.command=load-role-mappings --file=<path> [--dry-run]}. */
@Component
public class LoadRoleMappingsCommand implements ProductCommand {

    public static final String NAME = "load-role-mappings";
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    static final String HELP = """
            Usage: java -jar securedoc-api.jar --lexavault.command=load-role-mappings --file=<path> [--dry-run]

            Loads a role mapping file (format lexavault-role-mapping/1): one row per grant,
              entity_id,entity_name,group,role
            The whole file is checked first. Any error refuses the whole file and nothing is written.

            For every entity the file names, that entity's grants become exactly the file's rows:
            missing grants are added, grants not in the file are removed. An entity appears only
            through its rows, so entities not in the file are never touched, and no file can remove
            every grant of an entity by leaving its rows out.

              --dry-run   check the file and print what would change; write nothing
            Exit codes: 0 loaded or nothing to change, 1 file refused, 2 wrong command line or
            unreadable file, 3 failed to start or unexpected failure.
            See docs/install/ROLE_MAPPING_FILE.md.
            """;

    private final RoleMappingLoader loader;

    public LoadRoleMappingsCommand(RoleMappingLoader loader) {
        this.loader = loader;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int run(ApplicationArguments args, PrintStream out) {
        if (args.containsOption("help")) {
            out.print(HELP);
            return OK;
        }
        List<String> files = args.getOptionValues("file");
        if (files == null || files.size() != 1 || files.get(0).isBlank()) {
            out.println("Give exactly one --file=<path>.");
            out.print(HELP);
            return USAGE;
        }
        for (String option : args.getOptionNames()) {
            if (!List.of("file", "dry-run", "help").contains(option) && !option.contains(".")) {
                out.println("Unknown option --" + option + ".");
                out.print(HELP);
                return USAGE;
            }
        }
        boolean dryRun = args.containsOption("dry-run");
        Path path = Path.of(files.get(0));
        byte[] content;
        try {
            if (!Files.isRegularFile(path)) {
                out.println("The file " + path + " does not exist or is not a file.");
                return USAGE;
            }
            if (Files.size(path) > MAX_FILE_BYTES) {
                out.println("The file " + path + " is larger than 5 MB; a role mapping file is never that large.");
                return USAGE;
            }
            content = Files.readAllBytes(path);
        } catch (IOException e) {
            out.println("The file " + path + " could not be read: " + e.getMessage());
            return USAGE;
        }

        String fileName = path.getFileName().toString();
        RoleMappingLoader.Outcome outcome = loader.load(content, fileName, System.getProperty("user.name", "unknown"), dryRun);
        out.println("Role mapping file " + fileName + " (SHA-256 " + outcome.sha256() + ")");
        if (outcome.refused()) {
            out.println("The file was refused. Nothing was written. Correct these lines and run the command again:");
            for (MappingError error : outcome.errors()) out.println("  " + error.display());
            return REFUSED;
        }
        for (MappingPlan.EntityChange change : outcome.plan().entities()) {
            out.printf("  %s (%s): %d to add, %d to remove, %d unchanged%n", change.name(), change.entity(),
                    change.toAdd().size(), change.toRemove().size(), change.unchanged());
        }
        if (dryRun) {
            out.println(outcome.plan().hasChanges() ? "Dry run: the file is valid. Nothing was written."
                    : "Dry run: the file is valid and matches the stored grants. Nothing was written.");
        } else if (outcome.written()) {
            long changed = outcome.plan().entities().stream().filter(MappingPlan.EntityChange::changes).count();
            out.println("Loaded. " + changed + " " + (changed == 1 ? "entity" : "entities") + " changed.");
        } else {
            out.println("No changes. The stored grants already match the file.");
        }
        return OK;
    }
}
