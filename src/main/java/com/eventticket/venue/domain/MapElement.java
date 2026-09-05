package com.eventticket.venue.domain;

/**
 * Furniture on the map - a stage, an entrance, an aisle, a bar, a text label. It exists so a
 * buyer can orient themselves and can never be ticketed (requirements/002 criterion 7).
 *
 * <p>Width and height are boxed because a point-like element has neither.
 */
public record MapElement(Kind kind, String label, double x, double y, Double width, Double height) {

    /** Mirrors the contract's {@code MapElement.kind} enum. */
    public enum Kind { STAGE, ENTRANCE, AISLE, BAR, LABEL }
}
