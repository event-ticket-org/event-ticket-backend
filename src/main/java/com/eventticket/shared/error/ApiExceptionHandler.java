package com.eventticket.shared.error;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.ErrorCode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps failures onto the contract's single error envelope. One shape for every failure, so
 * that a client parses errors once.
 *
 * <p>{@link ErrorCode#fromValue} is what keeps {@link ErrorCodes} honest: a code that the
 * contract does not publish throws here rather than reaching a client as an unknown string.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Error> handle(ApiException e) {
        Error body = new Error(ErrorCode.fromValue(e.code()), e.getMessage());
        e.details().forEach(body::putDetailsItem);
        return ResponseEntity.status(statusFor(e.code())).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Error> handle(MethodArgumentNotValidException e) {
        Error body = new Error(ErrorCode.VALIDATION_FAILED, "The request failed validation.");
        e.getBindingResult().getFieldErrors()
                .forEach(f -> body.putDetailsItem(f.getField(), f.getDefaultMessage()));
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Anything unmapped is a defect, so it is logged in full and reported without detail. The
     * message a client receives must never leak an internal failure.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> handle(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(new Error(ErrorCode.VALIDATION_FAILED, "The request could not be completed."));
    }

    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case ErrorCodes.NOT_AUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case ErrorCodes.NOT_PERMITTED,
                 ErrorCodes.EMAIL_NOT_VERIFIED,
                 ErrorCodes.ORGANIZATION_NOT_APPROVED -> HttpStatus.FORBIDDEN;
            case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ErrorCodes.ALREADY_EXISTS, ErrorCodes.LAST_OWNER -> HttpStatus.CONFLICT;
            case ErrorCodes.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private static Map<String, Object> noDetails() {
        return Map.of();
    }
}
