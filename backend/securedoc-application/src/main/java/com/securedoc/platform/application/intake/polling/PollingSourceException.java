package com.securedoc.platform.application.intake.polling;

/** Connector-neutral polling failure with a safe operational category. */
public class PollingSourceException extends RuntimeException {
    private final String category;
    public PollingSourceException(String category, String message){super(message);this.category=category;}
    public PollingSourceException(String category, String message, Throwable cause){super(message,cause);this.category=category;}
    public String category(){return category;}
}
