package com.securedoc.platform.api.upload;

import com.securedoc.platform.api.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0055: every body that is not a file has a limit, announced or not; files keep their own limits. */
class RequestBodyLimitFilterTest {
    private final AtomicReference<Exception> refused = new AtomicReference<>();
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(DataSize.ofBytes(100),
            (request, response, handler, ex) -> {
                refused.set(ex);
                return null;
            });

    private long read(String type, int bytes, boolean announced, boolean asText) throws Exception {
        refused.set(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/packages") {
            @Override
            public long getContentLengthLong() {
                return announced ? bytes : -1;
            }
        };
        if (type != null) request.setContentType(type);
        request.setContent(new byte[bytes]);
        long[] got = {-1};
        filter.doFilter(request, new MockHttpServletResponse(), (r, s) -> got[0] = asText
                ? r.getReader().readLine().length() : r.getInputStream().readAllBytes().length);
        return got[0];
    }

    @Test
    void aBodyUpToTheLimitPassesAndALargerOneIsRefusedBeforeTheEndpoint() throws Exception {
        assertEquals(100, read("application/json", 100, true, false));
        assertNull(refused.get());
        assertEquals(-1, read("application/json", 101, true, false), "the endpoint is not called");
        assertEquals(100, assertInstanceOf(RequestBodyTooLargeException.class, refused.get()).limit());
        assertEquals(-1, read(null, 101, true, false));
    }

    @Test
    void aBodyWithoutAnAnnouncedLengthIsStoppedWhileItIsRead() throws Exception {
        assertEquals(100, read("application/json", 100, false, false));
        assertThrows(RequestBodyTooLargeException.class, () -> read("application/json", 101, false, false));
        assertThrows(RequestBodyTooLargeException.class, () -> read("application/vnd.x+json", 5000, false, true));
        assertThrows(RequestBodyTooLargeException.class, () -> read("application/pdfx", 101, false, false));
    }

    @Test
    void filesKeepTheirOwnLimits() throws Exception {
        assertEquals(5000, read("multipart/form-data; boundary=x", 5000, true, false));
        assertEquals(5000, read("application/pdf", 5000, false, false));
        assertNull(refused.get());
    }

    @Test
    void theAnswerIsItsOwnCodeAlsoWhenTheReaderFoundIt() {
        var direct = new GlobalExceptionHandler().handleRequestTooLarge(new RequestBodyTooLargeException(100),
                new MockHttpServletRequest());
        assertEquals(413, direct.getStatusCode().value());
        assertEquals("LV-SYS-015", direct.getBody().code());
        var found = new GlobalExceptionHandler().handleMalformedJson(new HttpMessageNotReadableException("x",
                new RequestBodyTooLargeException(100), new MockHttpInputMessage(new byte[0])), new MockHttpServletRequest());
        assertEquals(413, found.getStatusCode().value());
        assertEquals("LV-SYS-015", found.getBody().code());
    }
}
