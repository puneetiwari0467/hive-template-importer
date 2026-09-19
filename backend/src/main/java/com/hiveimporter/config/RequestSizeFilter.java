package com.hiveimporter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveimporter.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class RequestSizeFilter extends OncePerRequestFilter {
    private static final long MAX_JSON_BYTES = 16L * 1024 * 1024;
    private final ObjectMapper json;

    public RequestSizeFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getContentType() == null
                || !request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_JSON_BYTES) {
            var error = ApiException.tooLarge("The JSON request exceeds the 16 MiB limit.");
            response.setStatus(error.status().value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), error.body());
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            private ServletInputStream bounded;

            @Override
            public ServletInputStream getInputStream() throws IOException {
                if (bounded == null) {
                    bounded = new BoundedInput(super.getInputStream());
                }
                return bounded;
            }

            @Override
            public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
            }
        }, response);
    }

    private static final class BoundedInput extends ServletInputStream {
        private final ServletInputStream delegate;
        private long count;

        private BoundedInput(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next != -1) {
                check(1);
            }
            return next;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) {
                check(read);
            }
            return read;
        }

        private void check(int read) {
            count += read;
            if (count > MAX_JSON_BYTES) {
                throw ApiException.tooLarge("The JSON request exceeds the 16 MiB limit.");
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
