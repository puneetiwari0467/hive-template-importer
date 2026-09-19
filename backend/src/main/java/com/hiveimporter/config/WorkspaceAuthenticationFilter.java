package com.hiveimporter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hiveimporter.api.ApiException;
import com.hiveimporter.api.ErrorHandler;
import com.hiveimporter.service.TemplateService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class WorkspaceAuthenticationFilter extends OncePerRequestFilter {
    public static final String WORKSPACE_ATTRIBUTE = WorkspaceAuthenticationFilter.class.getName() + ".workspace";
    private final TemplateService service;
    private final ObjectMapper json;

    public WorkspaceAuthenticationFilter(TemplateService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return "OPTIONS".equals(request.getMethod())
                || !(path.equals("/api/templates") || path.startsWith("/api/templates/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            var headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.size() != 1 || !headers.getFirst().regionMatches(true, 0, "Bearer ", 0, 7)) {
                throw ApiException.unauthorized();
            }
            request.setAttribute(WORKSPACE_ATTRIBUTE, service.authenticate(headers.getFirst().substring(7)));
        } catch (ApiException exception) {
            response.setStatus(exception.status().value());
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), exception.body());
            return;
        } catch (RuntimeException exception) {
            response.setStatus(500);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), ErrorHandler.internalError(exception));
            return;
        }
        chain.doFilter(request, response);
    }
}
