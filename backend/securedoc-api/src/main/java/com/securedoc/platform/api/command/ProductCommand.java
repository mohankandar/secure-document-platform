package com.securedoc.platform.api.command;

import org.springframework.boot.ApplicationArguments;

import java.io.PrintStream;

/** A command run by {@code --lexavault.command=<name>}: no web server, run once, exit with a code. */
public interface ProductCommand {

    int OK = 0;
    /** The input was refused (for example a role mapping file with errors). Nothing was written. */
    int REFUSED = 1;
    /** The command line was wrong or a file could not be read. Nothing was written. */
    int USAGE = 2;
    /** The command could not start or failed unexpectedly. Nothing was written. */
    int FAILED = 3;

    String name();

    int run(ApplicationArguments args, PrintStream out);
}
