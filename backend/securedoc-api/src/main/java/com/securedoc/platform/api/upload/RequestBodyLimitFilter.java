package com.securedoc.platform.api.upload;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Task 0055: every request body that is not a file has a size limit, whatever endpoint it is for and whether or not its
 * length was announced. Files (multipart and PDF bodies) keep their own limits. Runs after sign-in.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RequestBodyLimitFilter extends OncePerRequestFilter {
    private final long limit;
    private final HandlerExceptionResolver errors;

    public RequestBodyLimitFilter(@Value("${securedoc.api.max-request-body:2MB}") DataSize limit,
                                  @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        if (limit.toBytes() < 1) throw new IllegalArgumentException("securedoc.api.max-request-body must be positive");
        this.limit = limit.toBytes();
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (file(request.getContentType())) {
            chain.doFilter(request, response);
        } else if (request.getContentLengthLong() > limit) {
            errors.resolveException(request, response, null, new RequestBodyTooLargeException(limit));
        } else {
            chain.doFilter(new Limited(request, limit), response);
        }
    }

    /** A JSON endpoint does not read these types, so calling a JSON body a file gains nothing. */
    private static boolean file(String contentType) {
        if (contentType == null) return false;
        String type = contentType.strip().toLowerCase(Locale.ROOT);
        return type.startsWith("multipart/") || type.equals("application/pdf") || type.startsWith("application/pdf;");
    }

    private static final class Limited extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream stream;
        private BufferedReader reader;

        Limited(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new Counted(super.getInputStream(), limit);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String charset = getCharacterEncoding();
                reader = new BufferedReader(new InputStreamReader(getInputStream(),
                        charset == null ? StandardCharsets.UTF_8.name() : charset));
            }
            return reader;
        }
    }

    private static final class Counted extends ServletInputStream {
        private final ServletInputStream in;
        private final long limit;
        private long read;

        Counted(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        private int counted(int bytes) throws IOException {
            if (bytes > 0) read += bytes;
            if (read > limit) throw new RequestBodyTooLargeException(limit);
            return bytes;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) counted(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return counted(in.read(buffer, offset, length));
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
