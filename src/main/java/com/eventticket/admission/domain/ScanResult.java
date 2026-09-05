package com.eventticket.admission.domain;

import java.time.Instant;

/**
 * What the door is told, and the reason each field exists.
 *
 * <p>{@code seatLabel} is here because criterion 3 wants it large enough to direct a person -
 * admitting someone is only half the job if nobody can tell them where to sit.
 *
 * <p>{@code firstRedeemedAt} and {@code firstRedeemedDeviceId} are here because of criterion 5,
 * and they are the most useful two fields in this record: they are how staff tell "you already
 * went in" from "somebody else used your ticket". Without the device, a duplicate scan is an
 * argument; with it, it is a fact.
 *
 * <p>Nothing about the buyer, the price or the sale. KB invariant 3 - Gate Staff see what they
 * need to open a door and no more.
 */
public record ScanResult(ScanOutcome outcome, String message, String seatLabel, String tierName,
                         Instant firstRedeemedAt, String firstRedeemedDeviceId) {

    public static ScanResult refused(ScanOutcome outcome, String message) {
        return new ScanResult(outcome, message, null, null, null, null);
    }

    public static ScanResult refused(ScanOutcome outcome, String message,
                                     String seatLabel, String tierName) {
        return new ScanResult(outcome, message, seatLabel, tierName, null, null);
    }
}
