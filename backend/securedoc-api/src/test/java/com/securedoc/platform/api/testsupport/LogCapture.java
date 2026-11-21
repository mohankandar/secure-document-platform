package com.securedoc.platform.api.testsupport;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/** Task 0023: captures what a class logs, to prove a log line holds a code and never content. */
public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture(String name) {
        this.logger = (Logger) LoggerFactory.getLogger(name);
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture of(Class<?> type) {
        return new LogCapture(type.getName());
    }

    public static LogCapture of(String name) {
        return new LogCapture(name);
    }

    /** Every formatted message, with its MDC, one per line. */
    public String text() {
        return appender.list.stream().map(e -> e.getFormattedMessage() + " " + e.getMDCPropertyMap())
                .collect(Collectors.joining("\n"));
    }

    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
