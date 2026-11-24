package com.securedoc.platform.api.error;

import com.documentplatform.errors.FailureLog;
import com.securedoc.platform.api.dto.ApiErrorResponse;
import com.securedoc.platform.domain.error.LvErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Task 0057: a failure an API answer reports is also one line in the log with its code and reference
 * ({@code code=LV-... ref=R-... cause="..."}) and one of the recent failures on Support and in the diagnostic bundle: the
 * reference the person sees is the one IT finds. Codes and fixed texts only. Refused requests (a wrong value, a missing
 * right) are not failures; they keep their own log lines, which carry the same reference.
 */
@RestControllerAdvice
public class FailureReferences implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ApiErrorResponse error) {
            LvErrorCode.fromCode(error.code()).filter(FailureReferences::isFailure)
                    .ifPresent(code -> FailureLog.record(new LvPlatformCode(code)));
        }
        return body;
    }

    static boolean isFailure(LvErrorCode code) {
        return switch (code.kind()) {
            case SYSTEM, SET_UP, STOPPED, EXPORT_FAILED -> code != LvErrorCode.SYS_008 && code != LvErrorCode.SYS_009;
            default -> false;
        };
    }
}
