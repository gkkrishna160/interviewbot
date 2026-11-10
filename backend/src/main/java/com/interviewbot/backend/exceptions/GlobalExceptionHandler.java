package com.interviewbot.backend.exceptions;

import com.interviewbot.backend.dtos.ApiError;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<ApiError> missingEndpoint(Exception ex) {
    return error(
        HttpStatus.NOT_FOUND, "Endpoint is unavailable in this application mode", List.of());
  }

  @ExceptionHandler(com.interviewbot.backend.security.ApiProtectionFilter.BodyLimitException.class)
  ResponseEntity<ApiError> tooLarge(Exception ex) {
    return error(HttpStatus.PAYLOAD_TOO_LARGE, "Request body is too large", List.of());
  }

  @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
  ResponseEntity<ApiError> handleBankNotFound(jakarta.persistence.EntityNotFoundException ex) {
    return error(HttpStatus.NOT_FOUND, ex.getMessage(), List.of());
  }

  @ExceptionHandler({
    org.springframework.dao.DataIntegrityViolationException.class,
    org.springframework.dao.OptimisticLockingFailureException.class
  })
  ResponseEntity<ApiError> handleBankConflict(Exception ex) {
    return error(
        HttpStatus.CONFLICT,
        "The record changed concurrently or conflicts with an existing record. Refresh and retry.",
        List.of());
  }

  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  ResponseEntity<ApiError> status(org.springframework.web.server.ResponseStatusException ex) {
    return error(HttpStatus.valueOf(ex.getStatusCode().value()), ex.getReason(), List.of());
  }

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<ApiError> handleBadRequest(Exception ex) {
    if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException unreadable
        && unreadable.getMostSpecificCause()
            instanceof com.interviewbot.backend.security.ApiProtectionFilter.BodyLimitException)
      return error(HttpStatus.PAYLOAD_TOO_LARGE, "Request body is too large", List.of());
    return error(
        HttpStatus.BAD_REQUEST,
        ex instanceof IllegalArgumentException
            ? ex.getMessage()
            : "Invalid request fields or enum values",
        List.of());
  }

  @ExceptionHandler(LLMException.class)
  ResponseEntity<ApiError> handleLlm(LLMException ex) {
    log.warn("LLM request failed: {}", ex.getMessage(), ex);
    return error(HttpStatus.BAD_GATEWAY, ex.getMessage(), List.of());
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
  ResponseEntity<ApiError> handleValidation(Exception ex) {
    List<String> details =
        ex instanceof MethodArgumentNotValidException invalid
            ? invalid.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .toList()
            : List.of();
    return error(HttpStatus.BAD_REQUEST, "Validation failed", details);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiError> handleDatabase(DataAccessException ex) {
    log.error("Database request failed", ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Database error", List.of());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> handleUnexpected(Exception ex) {
    log.error("Unexpected request failure", ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", List.of());
  }

  private ResponseEntity<ApiError> error(HttpStatus status, String message, List<String> details) {
    ApiError body =
        new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, details);
    return ResponseEntity.status(status).body(body);
  }
}
