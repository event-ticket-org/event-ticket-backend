package com.eventticket.shared.error;

/**
 * The closed set of error codes from the API contract. Mirrors the {@code ErrorCode} enum in
 * {@code contracts/openapi.yaml}; {@link ApiExceptionHandler} maps these onto the generated
 * type, so a code used here that the contract does not define fails at that boundary rather
 * than reaching a client.
 */
public final class ErrorCodes {

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String NOT_AUTHENTICATED = "NOT_AUTHENTICATED";
    public static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";
    public static final String NOT_PERMITTED = "NOT_PERMITTED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ORGANIZATION_NOT_APPROVED = "ORGANIZATION_NOT_APPROVED";
    public static final String LAST_OWNER = "LAST_OWNER";
    public static final String ALREADY_EXISTS = "ALREADY_EXISTS";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String DUPLICATE_SEAT_LABEL = "DUPLICATE_SEAT_LABEL";
    public static final String VENUE_IN_USE = "VENUE_IN_USE";
    public static final String EVENT_FIELD_FROZEN = "EVENT_FIELD_FROZEN";
    public static final String PUBLISH_PRECONDITION_FAILED = "PUBLISH_PRECONDITION_FAILED";
    public static final String CAPACITY_BELOW_SOLD = "CAPACITY_BELOW_SOLD";

    private ErrorCodes() {}
}
