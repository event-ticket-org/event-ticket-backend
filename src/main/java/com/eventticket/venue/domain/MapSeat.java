package com.eventticket.venue.domain;

/**
 * One seat as drawn on a Venue's Seat Map: a label, a position, and the name of the Pricing
 * Tier it belongs to. No price - prices belong to an Event, not to a room
 * (requirements/002 criterion 8).
 *
 * <p>No identifier either. A seat in a Venue's map is a mark on a drawing; it becomes a thing
 * with an identity only when an Event publishes and it can be held, sold and scanned.
 */
public record MapSeat(String label, double x, double y, String tierName) {
}
