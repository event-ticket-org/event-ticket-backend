package com.eventticket.shared.error;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.ErrorCode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
        // Refusals are logged where they are decided, with the identifiers that explain them.
        // Here only the outcome is needed, at debug, so a request's log ends with its result.
        log.debug("Request refused code={}", e.code());
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
     * The same answer, for the constraint violations Spring reports a different way.
     *
     * <p>A request body that is a top-level array is validated by the method interceptor rather
     * than by the body resolver - `@Valid @RequestBody List<@Valid PricingTierInput>` becomes an
     * AOP check around the controller method - and that path throws
     * {@link ConstraintViolationException} instead of {@link MethodArgumentNotValidException}.
     * Nothing was mapping it, so it fell to the catch-all below: a caller sending a price the
     * contract forbids got 500 and "the request could not be completed", and the log got an
     * ERROR with a stack trace. Validation was working the whole time and only the answer was
     * wrong, which is the sort of bug that reads as a missing feature.
     *
     * <p>Deliberately identical to the handler above - same code, same message, same 400, same
     * map of field to reason. Whether an endpoint takes an object or an array is our
     * implementation detail, and it was leaking as the difference between an answer a caller
     * could act on and one they could not.
     *
     * <p>The property path is trimmed to the part that names a field. Hibernate reports
     * {@code eventsEventIdPricingTiersPut.pricingTierInput[0].price.amount}: the first two nodes
     * are the generated method and its parameter, which are ours and mean nothing to whoever
     * sent the request.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Error> handle(ConstraintViolationException e) {
        Error body = new Error(ErrorCode.VALIDATION_FAILED, "The request failed validation.");
        e.getConstraintViolations()
                .forEach(v -> body.putDetailsItem(fieldOf(v.getPropertyPath().toString()),
                        v.getMessage()));
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * The part of the path that names something the caller sent.
     *
     * <p>{@code eventsEventIdPricingTiersPut.pricingTierInput[0].price.amount} becomes
     * {@code [0].price.amount}. The method and the parameter name are generated and mean
     * nothing outside this codebase; the index is kept, because a table of prices needs to say
     * which row is wrong.
     */
    private static String fieldOf(String propertyPath) {
        String[] nodes = propertyPath.split("\\.", 3);
        if (nodes.length < 3) {
            return propertyPath;
        }
        int bracket = nodes[1].indexOf('[');
        String index = bracket < 0 ? "" : nodes[1].substring(bracket);
        return index + (index.isEmpty() ? "" : ".") + nodes[2];
    }

    /**
     * A body that is not JSON, and a path variable that is not the type it has to be.
     *
     * <p>Named individually because these two are the exceptions to the rule below: unlike the
     * rest of Spring's web exceptions they do not implement {@link ErrorResponse}, so nothing
     * about them says 400 and they fall through as defects. Both were 500s.
     *
     * <p>Neither message is the framework's. A parse error carries the offending fragment of
     * the body and a conversion failure carries the value, and an error envelope is the one
     * place a request's own content should not be echoed back - it is the shape of a reflection
     * bug. The parameter name is enough to act on and is ours to publish.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class,
                       MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Error> handleMalformedRequest(Exception e) {
        log.debug("Request could not be read: {}", e.getClass().getSimpleName());
        String message = e instanceof MethodArgumentTypeMismatchException mismatch
                ? "'" + mismatch.getName() + "' is not a valid value for that parameter."
                : "The request body could not be read as JSON.";
        return ResponseEntity.badRequest().body(new Error(ErrorCode.VALIDATION_FAILED, message));
    }

    /**
     * Anything unmapped is a defect, so it is logged in full and reported without detail. The
     * message a client receives must never leak an internal failure.
     *
     * <p>Except that Spring's own web exceptions arrive here too, and they are not defects: a
     * method the path does not have, a content type nothing here reads. Every one of them used
     * to come back as 500, "The request could not be completed" - telling a caller the server
     * broke when the server understood perfectly and is refusing. The second cost was the worse
     * one: each wrote an ERROR with a stack trace, so a client looping on a wrong URL buried the
     * real defects in the log at precisely the moment the log mattered.
     *
     * <p>They are recognised by {@link ErrorResponse}, the interface most of them implement,
     * rather than by naming exception classes - a list would miss the next one. It cannot be an
     * {@code @ExceptionHandler} type of its own because the annotation takes a {@code Throwable}
     * and this is an interface, so the check is here. The two that do not implement it are
     * handled above, by name, because there is no other way to catch them.
     *
     * <p>Taking the framework's status and headers rather than inventing them is what makes a
     * 405 useful: the {@code Allow} header tells the caller what to use instead. A 5xx wearing
     * the same interface stays a defect and keeps the treatment below, or Spring's message
     * would be handed straight to whoever provoked it.
     *
     * <p>No new error code. {@code VALIDATION_FAILED} is the contract's "your request was
     * wrong", and a client branches on the code to tell <em>domain</em> outcomes apart -
     * SEATS_UNAVAILABLE from ORDER_ALREADY_PAID. None of these are outcomes; the status carries
     * everything there is to say.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Error> handle(Exception e) {
        if (e instanceof ErrorResponse refusal && refusal.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = refusal.getStatusCode();
            log.debug("Request refused before dispatch status={}", status);
            String detail = refusal.getBody().getDetail();
            return ResponseEntity.status(status).headers(refusal.getHeaders())
                    .body(new Error(ErrorCode.VALIDATION_FAILED,
                            detail == null || detail.isBlank()
                                    ? "The request could not be understood." : detail));
        }

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
            case ErrorCodes.ALREADY_EXISTS,
                 ErrorCodes.LAST_OWNER,
                 ErrorCodes.VENUE_IN_USE,
                 ErrorCodes.EVENT_FIELD_FROZEN,
                 ErrorCodes.PUBLISH_PRECONDITION_FAILED,
                 ErrorCodes.CAPACITY_BELOW_SOLD,
                 ErrorCodes.SEATS_UNAVAILABLE,
                 ErrorCodes.HOLD_EXPIRED,
                 ErrorCodes.ORDER_ALREADY_PAID,
                 ErrorCodes.ORDER_NOT_REFUNDABLE,
                 ErrorCodes.EVENT_NOT_CANCELLABLE,
                 ErrorCodes.COVER_NOT_UPLOADED,
                 ErrorCodes.COVER_NOT_AN_IMAGE -> HttpStatus.CONFLICT;
            case ErrorCodes.RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private static Map<String, Object> noDetails() {
        return Map.of();
    }
}
