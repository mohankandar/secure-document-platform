package com.securedoc.platform.api.error;

import com.documentplatform.errors.PlatformCode;
import com.securedoc.platform.domain.error.LvErrorCode;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Task 0057: a LexaVault code (LV-...) in the shared error shape (platform-errors, docs/design/error-codes.md section 3,
 * "map to it"). Names and texts are LexaVault's own; nothing is renamed.
 */
public record LvPlatformCode(LvErrorCode lv) implements PlatformCode {

    public LvPlatformCode {
        Objects.requireNonNull(lv, "lv");
    }

    @Override
    public String name() {
        return lv.name();
    }

    @Override
    public String code() {
        return lv.code();
    }

    @Override
    public Kind kind() {
        return switch (lv.kind()) {
            case REFUSED, NOT_ALLOWED -> Kind.REFUSED;
            case WARNING, WAITING -> Kind.INFO;
            case STOPPED, EXPORT_FAILED, SET_UP, SYSTEM -> Kind.FAILED;
        };
    }

    @Override
    public String userMessage() {
        return lv.title();
    }

    @Override
    public String cause() {
        return lv.description();
    }

    @Override
    public String fix() {
        return lv.whatToDo();
    }

    @Override
    public String supportNote() {
        return "Who acts: " + lv.whoActs() + ".";
    }

    @Override
    public OptionalInt httpStatus() {
        return lv.httpStatus().map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    @Override
    public String family() {
        return "LvErrorCode";
    }
}
