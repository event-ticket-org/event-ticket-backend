package com.eventticket.admission.domain;

/**
 * What happened at the door. Mirrors the contract's {@code ScanOutcome}.
 *
 * <p>Every refusal is distinct (requirements/007 criterion 4) because they call for different
 * actions from the person holding the scanner. "Already redeemed" sends someone to a supervisor;
 * "wrong event" sends them to the next hall; "unknown code" means the thing in their hand was
 * never a ticket. One generic failure would make all three the same conversation.
 */
public enum ScanOutcome {

    ADMITTED,
    ALREADY_REDEEMED,
    WRONG_EVENT,
    TICKET_VOID,
    EVENT_NOT_OPEN,
    EVENT_ENDED,
    UNKNOWN_CODE;

    public boolean admitted() {
        return this == ADMITTED;
    }
}
