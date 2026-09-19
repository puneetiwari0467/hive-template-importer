package com.hiveimporter.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {
    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/assets/app.js", "/assets/app.css", "/favicon.svg"})
    void staticResponsesPermitTheSameOriginReactApplication(String path) throws Exception {
        var response = filter(path, false);
        String policy = response.getHeader("Content-Security-Policy");
        assertThat(policy).startsWith("default-src 'self'")
                .contains("script-src 'self';", "style-src 'self' 'unsafe-inline';", "img-src 'self' data:;",
                        "connect-src 'self' https: http:;", "object-src 'none';", "frame-ancestors 'none';", "base-uri 'none';")
                .doesNotContain("script-src 'unsafe-inline'", "'unsafe-eval'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api", "/api/health", "/api/sample", "/api/templates", "/api/workspaces"})
    void apiResponsesKeepTheStrictNonExecutablePolicy(String path) throws Exception {
        var response = filter(path, false);
        assertThat(response.getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    }

    @Test
    void hstsUsesTheActualSecureConnectionRatherThanUntrustedForwardedHeaders() throws Exception {
        assertThat(filter("/", true).getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
        assertThat(filter("/", false).getHeader("Strict-Transport-Security")).isNull();
    }

    private MockHttpServletResponse filter(String path, boolean secure) throws Exception {
        var request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        request.setSecure(secure);
        request.addHeader("X-Forwarded-Proto", "https");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> { });
        return response;
    }
}
