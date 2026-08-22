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
 * CsrfFilter beantwortet einen fehlgeschlagenen Check selbst und ruft dafuer standardmaessig
 * AccessDeniedHandlerImpl auf, die response.sendError(403) nutzt. Das loest bei Spring Boot
 * einen internen Forward auf /error aus, der - da /error selbst nicht permitAll ist - den
 * SecurityFilterChain ein zweites Mal durchlaeuft und fuer den anonymen Request in eine
 * generische 401-Bearer-Antwort umgewandelt wird (verifiziert: dispatcherType=ERROR,
 * urspruengliche 403 geht verloren, Client sieht "unauthenticated" statt "CSRF ungueltig").
 * Dieser Handler schreibt die Antwort direkt und synchron, ohne sendError()/Error-Dispatch,
 * und haelt sich an den ProblemDetail-Contract aus CLAUDE.md 3.2.
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
