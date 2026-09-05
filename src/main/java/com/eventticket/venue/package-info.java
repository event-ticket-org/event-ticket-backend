/**
 * Venues and their reusable Seat Maps.
 *
 * <p>Knows nothing about Events, deliberately. A Seat Map is drawn for a room, not for an
 * occasion, and keeping the arrow pointing only one way - {@code event} reads a Venue's map
 * at publish; a Venue never reads an Event - is what keeps the two modules from becoming
 * mutually dependent. The one question a Venue does need answered about Events, whether one
 * has published against it, is answered by the database in {@code V4__venues_and_events.sql}.
 *
 * <p>One class per use case, named after what the user does. No VenueService.
 * See {@code docs/adr/0001-use-case-classes-not-services.md}.
 */
@org.springframework.modulith.ApplicationModule(
        type = org.springframework.modulith.ApplicationModule.Type.OPEN,
        allowedDependencies = {"shared", "organization"})
package com.eventticket.venue;
