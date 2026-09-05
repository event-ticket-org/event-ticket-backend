package com.eventticket.venue;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.ErrorCode;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.InviteMemberRequest;
import com.eventticket.api.model.Membership;
import com.eventticket.api.model.Organization;
import com.eventticket.api.model.Role;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.SeatMapSeat;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.api.model.VenueInput;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

/** Knowledge base requirements/002. */
class VenueAndSeatMapTest extends ApiTest {

    @Test
    @DisplayName("a venue is created with an empty seat map, and city is a field of its own")
    void venueStartsWithAnEmptySeatMap() {
        TokenPair manager = managerOfNewOrganization();

        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");

        assertThat(venue.getCity()).isEqualTo("Ho Chi Minh City");
        assertThat(venue.getTimezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(venue.getSeatCount()).isZero();

        SeatMap map = exchange(HttpMethod.GET, "/venues/" + venue.getId() + "/seat-map",
                manager, null, SeatMap.class).getBody();
        assertThat(map.getSeats()).isEmpty();
        assertThat(map.getElements()).isEmpty();
    }

    @Test
    @DisplayName("a generated block of rows is stored and read back whole")
    void seatMapIsReplacedWhole() {
        TokenPair manager = managerOfNewOrganization();
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");

        SeatMap replaced = putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 3, 10)).getBody();

        assertThat(replaced.getSeats()).hasSize(30);
        assertThat(replaced.getSeats()).extracting(SeatMapSeat::getLabel).contains("A1", "C10");
        assertThat(replaced.getElements()).singleElement()
                .satisfies(element -> assertThat(element.getLabel()).isEqualTo("Stage"));

        assertThat(exchange(HttpMethod.GET, "/venues/" + venue.getId(), manager, null, Venue.class)
                .getBody().getSeatCount()).isEqualTo(30);
    }

    @Test
    @DisplayName("two seats may not share a label")
    void duplicateLabelsAreRefused() {
        TokenPair manager = managerOfNewOrganization();
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");

        SeatMap clashing = SeatMaps.of(
                SeatMaps.seat("A1", 1, 1, "Standard"),
                SeatMaps.seat("A1", 2, 1, "Standard"));

        ResponseEntity<Error> refused = exchange(HttpMethod.PUT,
                "/venues/" + venue.getId() + "/seat-map", manager, clashing, Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(422));
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.DUPLICATE_SEAT_LABEL);
        assertThat(refused.getBody().getMessage()).contains("A1");
    }

    @Test
    @DisplayName("two seats may not share a position")
    void seatsOnTopOfEachOtherAreRefused() {
        TokenPair manager = managerOfNewOrganization();
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");

        SeatMap overlapping = SeatMaps.of(
                SeatMaps.seat("A1", 1, 1, "Standard"),
                SeatMaps.seat("A2", 1, 1, "Standard"));

        ResponseEntity<Error> refused = exchange(HttpMethod.PUT,
                "/venues/" + venue.getId() + "/seat-map", manager, overlapping, Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(422));
        assertThat(refused.getBody().getMessage()).contains("A2");
    }

    @Test
    @DisplayName("gate staff cannot draw seat maps")
    void gateStaffCannotEditVenues() {
        TokenPair owner = managerOfNewOrganization();
        Organization organization = onlyOrganizationOf(owner);
        exchange(HttpMethod.POST, "/organization/members", owner,
                new InviteMemberRequest("doorman@example.com", Role.GATE_STAFF), Membership.class);

        TokenPair doorman = switchTo(signUp("doorman@example.com"), organization);

        ResponseEntity<Error> refused = exchange(HttpMethod.POST, "/venues", doorman,
                new VenueInput("Side Room", "Ho Chi Minh City", "Asia/Ho_Chi_Minh"), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.NOT_PERMITTED);
    }

    @Test
    @DisplayName("a venue with a published event cannot be deleted; an unused one can")
    void venueInUseCannotBeDeleted() {
        TokenPair manager = managerOfNewOrganization();
        approve(onlyOrganizationOf(manager));

        Venue unused = createVenue(manager, "Storage Room", "Ho Chi Minh City");
        assertThat(exchange(HttpMethod.DELETE, "/venues/" + unused.getId(), manager, null, Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Venue inUse = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, inUse.getId(), SeatMaps.block("Standard", 2, 2));
        Event event = createEvent(manager, inUse.getId(), "Live in Saigon",
                OffsetDateTime.now().plus(30, ChronoUnit.DAYS));
        priceTier(manager, event.getId(), "Standard", 250_000);
        assertThat(publish(manager, event.getId(), Event.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Error> refused = exchange(HttpMethod.DELETE, "/venues/" + inUse.getId(),
                manager, null, Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.VENUE_IN_USE);
    }

    private TokenPair managerOfNewOrganization() {
        TokenPair alice = signUp("alice@example.com");
        return switchTo(alice, createOrganization(alice, "Acme Events"));
    }
}
