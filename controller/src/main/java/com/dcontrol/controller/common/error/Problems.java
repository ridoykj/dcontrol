package com.dcontrol.controller.common.error;

import com.dcontrol.controller.common.web.RequestIdFilter;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the single problem-details shape every error response uses. */
public final class Problems {

    public record FieldError(String field, String message) {
    }

    private Problems() {
    }

    public static ProblemDetail of(HttpStatusCode status, ErrorCode code, String detail, String instance) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (instance != null) {
            problem.setInstance(URI.create(instance));
        }
        return decorate(problem, code);
    }

    public static ProblemDetail withErrors(ProblemDetail problem, List<FieldError> errors) {
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Adds {@code code} (derived from the status when absent) and {@code traceId}. */
    public static ProblemDetail decorate(ProblemDetail problem, ErrorCode code) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", (code != null ? code : codeFor(problem.getStatus())).name());
        }
        String traceId = MDC.get(RequestIdFilter.MDC_KEY);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    public static ErrorCode codeFor(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        if (resolved == null) {
            return ErrorCode.INTERNAL;
        }
        return switch (resolved) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case CONFLICT -> ErrorCode.CONFLICT;
            case TOO_MANY_REQUESTS -> ErrorCode.RATE_LIMITED;
            default -> resolved.is4xxClientError() ? ErrorCode.VALIDATION : ErrorCode.INTERNAL;
        };
    }
}
