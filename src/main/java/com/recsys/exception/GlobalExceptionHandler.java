package com.recsys.exception;

import com.recsys.api.envelope.ApiError;
import com.recsys.exception.PipelineNotImplementedException;
import com.recsys.exception.RateLimitExceededException;
import com.recsys.exception.ServiceOverloadedException;
import com.recsys.exception.SubmitTokenException;
import com.recsys.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

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

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(ex.getRetryAfterSeconds()))
                .body(new ApiError(ex.getMessage(), List.of()));
    }

    @ExceptionHandler(ServiceOverloadedException.class)
    public ResponseEntity<ApiError> handleOverloaded(ServiceOverloadedException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, Integer.toString(ex.getRetryAfterSeconds()))
                .body(new ApiError(ex.getMessage(), List.of()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(new ApiError(ex.getMessage(), List.of()));
    }

    @ExceptionHandler(SubmitTokenException.class)
    public ResponseEntity<ApiError> handleSubmitToken(SubmitTokenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(ex.getMessage(), List.of()));
    }

    @ExceptionHandler(PipelineNotImplementedException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public ApiError handleNotImplemented(PipelineNotImplementedException ex) {
        return new ApiError(ex.getMessage(), List.of());
    }

    // Catch-all: unexpected inference or runtime errors
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unexpected error handling request {}", request.getDescription(false), ex);
        return new ApiError("internal server error", List.of());
    }
}
