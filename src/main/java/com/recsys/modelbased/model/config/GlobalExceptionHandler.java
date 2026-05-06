package com.recsys.modelbased.model.config;

import com.recsys.modelbased.model.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Bean Validation failures from @Valid — returns per-field violation list
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.Violation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.Violation(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return new ApiError("validation failed", violations);
    }

    // Malformed JSON or type mismatch in request body
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleUnreadable(HttpMessageNotReadableException ex) {
        return new ApiError("malformed request body", List.of());
    }

    // Wrong Content-Type (e.g. text/plain instead of application/json)
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ApiError handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return new ApiError("Content-Type must be application/json", List.of());
    }

    // Service-level guard (validation that bean constraints don't cover)
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleIllegalArgument(IllegalArgumentException ex) {
        return new ApiError(ex.getMessage(), List.of());
    }

    // Catch-all: unexpected inference or runtime errors
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error handling request {}", request.getRequestURI(), ex);
        return new ApiError("internal server error", List.of());
    }
}
