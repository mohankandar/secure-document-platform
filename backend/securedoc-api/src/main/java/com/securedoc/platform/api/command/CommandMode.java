package com.securedoc.platform.api.command;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The backend jar's command mode: {@code java -jar securedoc-api.jar --lexavault.command=<name> ...}. It starts
 * Spring with the server's database settings but without the web server, without scheduled jobs, without start-up
 * runners and without schema migration, runs the one command and exits with its code. It never runs at server start.
 */
public final class CommandMode {

    public static final String OPTION = "lexavault.command";

    /** Settings a command always runs with. Giving any of them on the command line is refused. */
    static final Map<String, String> FORCED = forced();
    /** Settings a command runs with unless the command line says otherwise. */
    static final Map<String, String> DEFAULTS = Map.of(
            "logging.level.root", "WARN",
            "spring.main.log-startup-info", "false");

    private CommandMode() {
    }

    private static Map<String, String> forced() {
        Map<String, String> forced = new LinkedHashMap<>();
        forced.put("spring.main.web-application-type", "none");
        forced.put("spring.main.banner-mode", "off");
        forced.put("spring.liquibase.enabled", "false");
        forced.put("securedoc.ai.ollama-preload-enabled", "false");
        forced.put("securedoc.security.oidc.recovery.enabled", "false");
        forced.put("lexavault.command-mode", "true");
        return Map.copyOf(forced);
    }

    /** The command name, when the arguments ask for command mode. */
    public static Optional<String> requested(String[] args) {
        String prefix = "--" + OPTION + "=";
        return Arrays.stream(args).filter(a -> a.startsWith(prefix)).map(a -> a.substring(prefix.length())).findFirst();
    }

    /** The arguments the command's Spring context starts with, or empty when a forced setting was overridden. */
    static Optional<String[]> contextArguments(String[] args) {
        List<String> result = new ArrayList<>(Arrays.asList(args));
        for (String key : FORCED.keySet()) {
            if (Arrays.stream(args).anyMatch(a -> a.startsWith("--" + key + "="))) return Optional.empty();
            result.add("--" + key + "=" + FORCED.get(key));
        }
        DEFAULTS.forEach((key, value) -> {
            if (Arrays.stream(args).noneMatch(a -> a.startsWith("--" + key + "="))) result.add("--" + key + "=" + value);
        });
        return Optional.of(result.toArray(String[]::new));
    }

    public static int run(Class<?> application, String[] args, PrintStream out) {
        String name = requested(args).orElseThrow();
        long given = Arrays.stream(args).filter(a -> a.startsWith("--" + OPTION + "=")).count();
        if (given != 1) {
            out.println("Give --" + OPTION + " once.");
            return ProductCommand.USAGE;
        }
        Optional<String[]> contextArgs = contextArguments(args);
        if (contextArgs.isEmpty()) {
            out.println("These settings are fixed in command mode and cannot be given: " + String.join(", ", FORCED.keySet()));
            return ProductCommand.USAGE;
        }
        CommandModeBeanFilter filter = new CommandModeBeanFilter();
        ConfigurableApplicationContext context;
        try {
            context = new SpringApplicationBuilder(application)
                    .web(WebApplicationType.NONE)
                    // Registered as a bean definition, not added directly: it must run after component scanning.
                    .initializers(ctx -> ((BeanDefinitionRegistry) ctx).registerBeanDefinition(
                            CommandModeBeanFilter.class.getName(),
                            new RootBeanDefinition(CommandModeBeanFilter.class, () -> filter)))
                    .run(contextArgs.get());
        } catch (RuntimeException startFailure) {
            out.println("The command could not start. Nothing was written. Cause: " + rootMessage(startFailure));
            return ProductCommand.FAILED;
        }
        try (context) {
            Optional<ProductCommand> command = context.getBeansOfType(ProductCommand.class).values().stream()
                    .filter(c -> c.name().equals(name)).findFirst();
            if (command.isEmpty()) {
                out.println("Unknown command \"" + name + "\". Commands: " + String.join(", ",
                        context.getBeansOfType(ProductCommand.class).values().stream().map(ProductCommand::name).sorted().toList()));
                return ProductCommand.USAGE;
            }
            return command.get().run(new DefaultApplicationArguments(args), out);
        } catch (RuntimeException failure) {
            out.println("The command failed. Nothing was written. Cause: " + rootMessage(failure));
            return ProductCommand.FAILED;
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
