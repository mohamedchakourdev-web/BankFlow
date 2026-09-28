package com.bankflow.common.exception;

import com.bankflow.common.response.ApiErrorResponse;
import com.bankflow.common.response.ApiErrorResponse.FieldViolation;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import com.bankflow.common.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BankFlowException.class)
    public ResponseEntity<ApiErrorResponse> handleBankFlowException(BankFlowException ex, HttpServletRequest request) {
        return response(ex.getStatus(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "Validation failed", request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldViolation> fieldErrors = ex.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "Validation failed", request, fieldErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String message = isInvalidUuid(ex) ? "Request contains an invalid UUID" : "Malformed JSON request";
        return response(HttpStatus.BAD_REQUEST, message, request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = ex.getRequiredType() == UUID.class
                ? uuidMessage(request.getRequestURI())
                : "Invalid request parameter";
        return response(HttpStatus.BAD_REQUEST, message, request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "Resource not found", request, List.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        String message = "Idempotency-Key".equals(ex.getHeaderName())
                ? "Idempotency-Key is required"
                : "A required header is missing";
        return response(HttpStatus.BAD_REQUEST, message, request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request
    ) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "Request method is not supported", request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}", request.getRequestURI());
        return response(
                HttpStatus.CONFLICT,
                "The request conflicts with existing data",
                request,
                List.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error(
                "Unhandled error correlationId={} path={}",
                MDC.get(CorrelationIdFilter.MDC_KEY),
                request.getRequestURI(),
                ex
        );
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private String uuidMessage(String path) {
        if (path.startsWith("/api/accounts")) {
            return "Account id must be a valid UUID";
        }
        if (path.startsWith("/api/transactions")) {
            return "Transaction id must be a valid UUID";
        }
        if (path.startsWith("/api/notifications")) {
            return "Notification id must be a valid UUID";
        }
        return "The id must be a valid UUID";
    }

    private boolean isInvalidUuid(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InvalidFormatException invalid && invalid.getTargetType() == UUID.class) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<FieldViolation> fieldErrors
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }
}
