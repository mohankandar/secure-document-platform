package com.securedoc.platform.application.exception;

import com.securedoc.platform.domain.error.CodedStateException;
import com.securedoc.platform.domain.error.LvErrorCode;

/**
 * Task 0023 B5: a state refusal whose message LexaVault wrote for the person who acts, as a fixed text with no
 * document content, file name, person's name, host or secret in it.
 *
 * <p>Task 0025: it carries its LexaVault code; the API sends the code and its title ({@code INVALID_STATE}, 409 as
 * before) and the message stays in LexaVault for the log. It is an {@link IllegalStateException}, so code that
 * catches those behaves exactly as before.
 */
public class PersonFacingStateException extends CodedStateException {

    public PersonFacingStateException(LvErrorCode lvCode, String message) {
        super(lvCode, message);
    }
}
