package com.eventticket.shared.error;

import java.util.Map;

/**
 * A failure that the contract has a code for. Carries the code rather than relying on the
 * exception's Java type, so that the set of things a client can be told is the set the
 * contract publishes.
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final transient Map<String, Object> details;
    private boolean gone;

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

    /**
     * 410 rather than 404, for a link that was real and is now spent.
     *
     * <p>The one place the contract distinguishes two statuses for the same code, and it is
     * worth the exception: "there was never such a link" and "that link has been used" are the
     * difference between a person doubting they clicked the right thing and a person knowing to
     * ask for another. The contract has said 410 for {@code /auth/verify-email} since the
     * beginning and the server answered 404, which made the contract wrong about it.
     *
     * <p>{@code ErrorCode} is a closed set the contract publishes, so this carries no new code.
     * That is the right way round: a client branches on the code for domain outcomes, and this
     * is not one - the status is the whole of what there is to say.
     */
    public static ApiException gone(String message) {
        ApiException e = new ApiException(ErrorCodes.NOT_FOUND, message);
        e.gone = true;
        return e;
    }

    public boolean isGone() {
        return gone;
    }

    public static ApiException notPermitted(String why) {
        return new ApiException(ErrorCodes.NOT_PERMITTED, why);
    }
}
