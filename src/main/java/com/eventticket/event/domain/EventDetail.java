package com.eventticket.event.domain;

/**
 * An Event together with the prices a manager needs to see beside it.
 *
 * <p>{@code notifiedCount} is set only by the one operation that notifies anybody - moving a
 * published Event's start time - and is null everywhere else, which is how the contract's
 * {@code notifyCount} is meant to read: "how many people the request that produced this
 * response emailed", not a standing property of the Event.
 */
public record EventDetail(Event event, EventPricing pricing, Integer notifiedCount,
                          long soldCount, long refundRequiredCount) {

    public static EventDetail of(Event event, EventPricing pricing) {
        return new EventDetail(event, pricing, null, 0, 0);
    }

    public EventDetail withCounts(long soldCount, long refundRequiredCount) {
        return new EventDetail(event, pricing, notifiedCount, soldCount, refundRequiredCount);
    }

    /**
     * {@code Integer}, not {@code int}. Nothing is notified by most changes, and null is how
     * the contract's {@code notifyCount} says "this request emailed nobody" - unboxing it
     * turned every patch that does not move a start time into a 500.
     */
    public EventDetail notifying(Integer notified) {
        return new EventDetail(event, pricing, notified, soldCount, refundRequiredCount);
    }
}
