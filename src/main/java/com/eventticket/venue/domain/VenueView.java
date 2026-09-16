package com.eventticket.venue.domain;

/**
 * A Venue together with the name of the City it is in.
 *
 * <p>{@code Venue} holds {@code citySlug} and not a mapped {@code City}, so the name it is
 * printed under is a second read rather than a lazy association. Doing that read in the use
 * case and carrying the answer here keeps the controller from having to know where the name
 * came from, and is the same shape {@link com.eventticket.event.domain.PublicEventView} uses
 * for the Organization and Venue names an Event is meaningless without.
 *
 * <p>The alternative - letting the controller fetch it - moves a join into the web layer and
 * puts it outside the use case's transaction, which is where lazy loading fails.
 */
public record VenueView(Venue venue, String cityName) {
}
