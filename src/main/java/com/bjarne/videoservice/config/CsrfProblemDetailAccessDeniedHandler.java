package com.bjarne.videoservice.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * CsrfFilter handles a failed check itself and by default calls AccessDeniedHandlerImpl for it,
 * which uses response.sendError(403). In Spring Boot this triggers an internal forward to /error,
 * which - since /error itself isn't permitAll - runs through the SecurityFilterChain a second
 * time and gets converted into a generic 401 bearer response for the anonymous request (verified:
 * dispatcherType=ERROR, the original 403 is lost, the client sees "unauthenticated" instead of
 * "invalid CSRF"). This handler writes the response directly and synchronously, without
 * sendError()/error dispatch, and follows the ProblemDetail contract from CLAUDE.md 3.2.
 */
public class CsrfProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public CsrfProblemDetailAccessDeniedHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "Invalid or missing CSRF token");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
