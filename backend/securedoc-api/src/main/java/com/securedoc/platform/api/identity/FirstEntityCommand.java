package com.securedoc.platform.api.identity;

import com.securedoc.platform.domain.identity.CorrelationId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Task 0075: {@code --activate-first-entity --entity-slot=... --entity-name=...}: activates and names the customer's first
 * entity, then ends. Exit code 0 done or unchanged, 2 refused, 1 failed. See docs/install/SETUP.md.
 */
@Component
public class FirstEntityCommand implements ApplicationRunner {
    public static final String OPTION = "activate-first-entity";

    private final EntitySlotService slots;
    private final ConfigurableApplicationContext context;

    public FirstEntityCommand(EntitySlotService slots, ConfigurableApplicationContext context) {
        this.slots = slots;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!arguments.containsOption(OPTION)) return;
        int exit;
        try {
            exit = activate(arguments);
        } catch (RuntimeException failed) {
            // The cause can hold database details; only its kind is shown.
            System.err.println("FAILED: " + failed.getClass().getSimpleName());
            exit = 1;
        }
        int code = exit;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    int activate(ApplicationArguments arguments) {
        String slot = one(arguments, "entity-slot");
        String name = one(arguments, "entity-name");
        if (slot == null || name == null) {
            System.err.println("BAD_OPTION: give --entity-slot and --entity-name once each.");
            return 2;
        }
        EntitySlotService.Answer answer = slots.activateFirst(slot, name, CorrelationId.generate());
        if (answer.done()) {
            System.out.println(answer.code() + ": " + slot + (answer.code().equals("UNCHANGED")
                    ? " is already active under this name." : " is active. Its people can sign in."));
            return 0;
        }
        System.err.println(answer.code() + ": " + answer.message() + " " + answer.whatToDo());
        return 2;
    }

    private static String one(ApplicationArguments arguments, String option) {
        List<String> values = arguments.getOptionValues(option);
        return values == null || values.size() != 1 || values.get(0).isBlank() ? null : values.get(0).strip();
    }
}
