package com.bankflow.common.response;

import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> fieldErrors
) {

    public ApiErrorResponse {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ApiErrorResponse of(HttpStatus status, String message, String path) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                List.of()
        );
    }

    public record FieldViolation(String field, String message) {
    }
}
