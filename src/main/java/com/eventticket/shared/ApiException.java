package com.eventticket.shared;

import java.util.Map;

/**
 * A failure that the contract has a code for. Carries the code rather than relying on the
 * exception's Java type, so that the set of things a client can be told is the set the
 * contract publishes.
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> details;

    public ApiException(String code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(String code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCodes.NOT_FOUND, what + " was not found.");
    }

    public static ApiException notPermitted(String why) {
        return new ApiException(ErrorCodes.NOT_PERMITTED, why);
    }
}
