package com.securedoc.platform.application.exception;

/**
 * Task 0023 B5: a state refusal whose message LexaVault wrote for the person who acts, as a fixed text with no
 * document content, file name, person's name, host or secret in it. The API sends this message with
 * {@code INVALID_STATE} (409); every other {@link IllegalStateException} gets a fixed text instead.
 *
 * <p>It is an {@link IllegalStateException}, so code that catches those behaves exactly as before.
 */
public class PersonFacingStateException extends IllegalStateException {

    public PersonFacingStateException(String message) {
        super(message);
    }
}
