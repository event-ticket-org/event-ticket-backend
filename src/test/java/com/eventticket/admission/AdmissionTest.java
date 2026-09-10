package com.eventticket.admission;

import java.time.Instant;
import org.springframework.data.mongodb.core.query.Criteria;
import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
import com.eventticket.api.model.ErrorCode;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.InviteMemberRequest;
import com.eventticket.api.model.Me;
import com.eventticket.api.model.Membership;
import com.eventticket.api.model.Order;
import com.eventticket.api.model.Organization;
import com.eventticket.api.model.Role;
import com.eventticket.api.model.ScanOutcome;
import com.eventticket.api.model.ScanResult;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Knowledge base requirements/007.
 *
 * <p>Every refusal gets its own test rather than one "is refused" test, for the same reason the
 * refusals are distinct in the first place: they call for different actions from the person
 * holding the scanner, and a suite that treats them as interchangeable would not notice them
 * becoming interchangeable in the code.
 */
class AdmissionTest extends ApiTest {

    /** Starting shortly, doors already open - which is when anybody is standing at one. */
    private static final OffsetDateTime SOON = OffsetDateTime.now().plus(2, ChronoUnit.HOURS);
    private static final OffsetDateTime DOORS_OPEN = OffsetDateTime.now().minus(1, ChronoUnit.HOURS);
    private static final OffsetDateTime ENDS = OffsetDateTime.now().plus(6, ChronoUnit.HOURS);

    @Test
    @DisplayName("a valid ticket is admitted, and says which seat to point at")
    void validTicketIsAdmitted() {
        Door door = openDoor();
        String code = door.codes().get(0);

        ScanResult result = scan(door.staff(), door.eventId(), code, "gate-1");

        assertThat(result.getOutcome()).isEqualTo(ScanOutcome.ADMITTED);
        assertThat(result.getSeatLabel()).isEqualTo("A1");
        assertThat(result.getTierName()).isEqualTo("Standard");
        assertThat(result.getFirstRedeemedAt()).isNull();
    }

    @Test
    @DisplayName("a second scan says when and where the ticket was first used")
    void alreadyRedeemedNamesTheFirstDevice() {
        Door door = openDoor();
        String code = door.codes().get(0);

        scan(door.staff(), door.eventId(), code, "gate-1");
        ScanResult again = scan(door.staff(), door.eventId(), code, "gate-2");

        assertThat(again.getOutcome()).isEqualTo(ScanOutcome.ALREADY_REDEEMED);
        // criterion 5: this pair is how staff tell "you already went in" from "someone else
        // used your ticket". Without the device it is an argument rather than a fact.
        assertThat(again.getFirstRedeemedAt()).isNotNull();
        assertThat(again.getFirstRedeemedDeviceId()).isEqualTo("gate-1");
        assertThat(again.getSeatLabel()).isEqualTo("A1");
    }

    @Test
    @DisplayName("a ticket for the hall next door is refused as wrong event, not as unknown")
    void wrongEventIsItsOwnAnswer() {
        Door door = openDoor();
        UUID otherHall = secondEventAt(door);

        ScanResult result = scan(door.staff(), otherHall, door.codes().get(0), "gate-1");

        assertThat(result.getOutcome()).isEqualTo(ScanOutcome.WRONG_EVENT);
        assertThat(result.getSeatLabel()).isEqualTo("A1");
    }

    @Test
    @DisplayName("another organization's ticket is unknown here, and stays unknown")
    void otherOrganizationsTicketsAreNotAcknowledged() {
        Door door = openDoor();
        Door rival = openDoor("bob@example.com", "Rival Promotions", "Rival Night");

        ScanResult result = scan(rival.staff(), rival.eventId(), door.codes().get(0), "gate-1");

        // Not WRONG_EVENT, and that is the right answer rather than a limitation. Row-level
        // security hides another Organization's Tickets, so this door genuinely cannot see one
        // - and should not: telling Rival's staff a code is "for a different event" confirms
        // that Acme sold it. The case it costs us, a ticket carried between organizations, is
        // not one a door has any business resolving.
        assertThat(result.getOutcome()).isEqualTo(ScanOutcome.UNKNOWN_CODE);
        assertThat(result.getSeatLabel()).isNull();
    }

    @Test
    @DisplayName("a code we never issued is refused without touching the ticket table")
    void unknownCodeIsRefused() {
        Door door = openDoor();

        ScanResult forged = scan(door.staff(), door.eventId(), "ET1-" + "AB".repeat(16) + "-" + "CD".repeat(8), "gate-1");
        ScanResult nonsense = scan(door.staff(), door.eventId(), "not-a-ticket", "gate-1");

        assertThat(forged.getOutcome()).isEqualTo(ScanOutcome.UNKNOWN_CODE);
        assertThat(nonsense.getOutcome()).isEqualTo(ScanOutcome.UNKNOWN_CODE);
        // Recorded anyway - a refused scan is the only trace somebody tried.
        assertThat(scansFor(door.eventId())).isEqualTo(2L);
    }

    @Test
    @DisplayName("before doors open, nobody gets in")
    void doorsNotOpenYet() {
        Door door = openDoor();
        reschedule(door.eventId(), "+4 hours", "+2 hours", "+8 hours");

        ScanResult result = scan(door.staff(), door.eventId(), door.codes().get(0), "gate-1");

        assertThat(result.getOutcome()).isEqualTo(ScanOutcome.EVENT_NOT_OPEN);
        assertThat(ticketStatus(door.codes().get(0))).isEqualTo("VALID");
    }

    @Test
    @DisplayName("after the event ends, nobody gets in either")
    void eventHasEnded() {
        Door door = openDoor();
        reschedule(door.eventId(), "-4 hours", "-6 hours", "-2 hours");

        ScanResult result = scan(door.staff(), door.eventId(), door.codes().get(0), "gate-1");

        assertThat(result.getOutcome()).isEqualTo(ScanOutcome.EVENT_ENDED);
    }

    @Test
    @DisplayName("a void ticket is refused, and there is no way to force it through")
    void voidTicketsCannotBeAdmitted() {
        Door door = openDoor();
        String code = door.codes().get(0);
        setField("ticket", Criteria.where("codeLookup").is(lookupOf(code)), "status", "VOID");

        ScanResult result = scan(door.staff(), door.eventId(), code, "gate-1");

        assertThat(result.getOutcome()).isEqualTo(ScanOutcome.TICKET_VOID);
        // criterion 7: no override. Scanning again, from another device, changes nothing -
        // there is no request shape that admits a refused ticket.
        assertThat(scan(door.staff(), door.eventId(), code, "gate-2").getOutcome())
                .isEqualTo(ScanOutcome.TICKET_VOID);
        assertThat(ticketStatus(code)).isEqualTo("VOID");
    }

    @Test
    @DisplayName("sales being closed does not stop anyone getting in")
    void closedSalesStillAdmit() {
        Door door = openDoor();
        exchange(HttpMethod.POST, "/events/" + door.eventId() + "/close-sales",
                door.manager(), null, Event.class);

        assertThat(scan(door.staff(), door.eventId(), door.codes().get(0), "gate-1").getOutcome())
                .isEqualTo(ScanOutcome.ADMITTED);
    }

    @Test
    @DisplayName("every scan is recorded, whatever it was")
    void everyScanIsRecorded() {
        Door door = openDoor();

        scan(door.staff(), door.eventId(), door.codes().get(0), "gate-1");
        scan(door.staff(), door.eventId(), door.codes().get(0), "gate-1");
        scan(door.staff(), door.eventId(), "not-a-ticket", "gate-2");

        assertThat(scansFor(door.eventId())).isEqualTo(3L);
        assertThat(readFields("scan", new Criteria(), "outcome", String.class, "occurredAt"))
                .containsExactly("ADMITTED", "ALREADY_REDEEMED", "UNKNOWN_CODE");
        assertThat(countIn("scan", Criteria.where("deviceId").is("gate-1"))).isEqualTo(2L);
    }

    @Test
    @DisplayName("a removed gate staff member stops scanning on the very next request")
    void authorisationIsCheckedAgainstLiveMembership() {
        Door door = openDoor();
        assertThat(scan(door.staff(), door.eventId(), door.codes().get(0), "gate-1").getOutcome())
                .isEqualTo(ScanOutcome.ADMITTED);

        UUID staffId = exchange(HttpMethod.GET, "/me", door.staff(), null, Me.class).getBody().getId();
        exchange(HttpMethod.DELETE, "/organization/members/" + staffId, door.manager(), null, Void.class);

        // Their access token is still cryptographically valid - ADR-0005's fifteen-minute
        // window - and it buys them nothing here, because the door asks the database.
        var refused = scanResponse(door.staff(), door.eventId(), door.codes().get(1), "gate-1",
                Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().getCode()).isEqualTo(ErrorCode.NOT_PERMITTED);
    }

    @Test
    @DisplayName("a device scanning too fast is rate limited")
    void devicesAreRateLimited() {
        Door door = openDoor();

        // The bucket allows a burst; past it the door says wait rather than falling over.
        int refusals = 0;
        for (int i = 0; i < 60; i++) {
            var response = scanResponse(door.staff(), door.eventId(), "not-a-ticket", "flooding-device",
                    Object.class);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                refusals++;
            }
        }

        assertThat(refusals).isPositive();
        // Another device is unaffected: the limit is per device, not per door.
        assertThat(scan(door.staff(), door.eventId(), door.codes().get(0), "calm-device").getOutcome())
                .isEqualTo(ScanOutcome.ADMITTED);
    }

    @Test
    @DisplayName("gate staff cannot read sales figures")
    void gateStaffSeeNoSalesFigures() {
        Door door = openDoor();

        // KB invariant 3. An Event carries its pricing tiers and a sold count.
        assertThat(exchange(HttpMethod.GET, "/events/" + door.eventId(), door.staff(), null,
                Error.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(HttpMethod.GET, "/events", door.staff(), null, Error.class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // ...and can still do the one thing they are there for.
        assertThat(scan(door.staff(), door.eventId(), door.codes().get(0), "gate-1").getOutcome())
                .isEqualTo(ScanOutcome.ADMITTED);
    }

    // --- fixtures -------------------------------------------------------------------------

    /** An open door: a published event happening now, two sold tickets, and staff to scan them. */
    private record Door(UUID eventId, TokenPair manager, TokenPair staff, List<String> codes) {}

    private Door openDoor() {
        return openDoor("alice@example.com", "Acme Events", "Live in Saigon");
    }

    private Door openDoor(String ownerEmail, String organizationName, String title) {
        TokenPair owner = signUp(ownerEmail);
        Organization organization = createOrganization(owner, organizationName);
        approve(organization);
        TokenPair manager = switchTo(owner, organization);

        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 5));
        Event event = createEvent(manager, venue.getId(), title, SOON, DOORS_OPEN, ENDS);
        priceTier(manager, event.getId(), "Standard", 250_000);
        publish(manager, event.getId(), Event.class);

        String doorman = "doorman-" + organizationName.toLowerCase().replace(' ', '-') + "@example.com";
        exchange(HttpMethod.POST, "/organization/members", manager,
                new InviteMemberRequest(doorman, Role.GATE_STAFF), Membership.class);
        TokenPair staff = switchTo(signUp(doorman), organization);

        TokenPair buyer = signUp("buyer-" + organizationName.hashCode() + "@example.com");
        Order paid = buyAndPay(buyer, event.getId(), seatIdsOf(event.getId(), 2));

        return new Door(event.getId(), manager, staff, ticketCodesOf(buyer, paid.getId()));
    }

    /**
     * Moves the event rather than the clock, which is the only one of the two we control. All
     * three instants move together, because the schema requires an ordered window and is right
     * to - the first version of this helper moved two of them and the constraint caught it.
     */
    private void reschedule(UUID eventId, String startsOffset, String doorsOffset, String endsOffset) {
        // Postgres parsed "2 hours" itself. There is no interval type here and no server-side
        // now(), so the arithmetic moves into the JVM - which also moves the clock the window is
        // measured against from the database to the application.
        Criteria event = Criteria.where("_id").is(eventId);
        setField("event", event, "startsAt", Instant.now().plus(parse(startsOffset)));
        setField("event", event, "doorsOpenAt", Instant.now().plus(parse(doorsOffset)));
        setField("event", event, "endsAt", Instant.now().plus(parse(endsOffset)));
    }

    /** A second event of the same Organization: the hall next door, sharing its staff. */
    private UUID secondEventAt(Door door) {
        UUID venueId = readField("event", door.eventId(), "venueId", UUID.class);
        Event second = createEvent(door.manager(), venueId, "The Other Hall", SOON, DOORS_OPEN, ENDS);
        priceTier(door.manager(), second.getId(), "Standard", 250_000);
        publish(door.manager(), second.getId(), Event.class);
        return second.getId();
    }

    private long scansFor(UUID eventId) {
        return countIn("scan", Criteria.where("eventId").is(eventId));
    }

    private String ticketStatus(String code) {
        return readFieldWhere("ticket", Criteria.where("codeLookup").is(lookupOf(code)),
                "status", String.class);
    }

    private static String lookupOf(String code) {
        return code.split("-")[1];
    }

    /** {@code interval '-2 hours'} and friends, which SQL understood and Java has to be told. */
    private static java.time.Duration parse(String interval) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(-?\\d+)\\s*(\\w+)").matcher(interval.trim());
        if (!m.find()) {
            throw new IllegalArgumentException("not an interval: " + interval);
        }
        long amount = Long.parseLong(m.group(1));
        String unit = m.group(2).toLowerCase(java.util.Locale.ROOT);
        if (unit.startsWith("minute")) {
            return java.time.Duration.ofMinutes(amount);
        }
        if (unit.startsWith("hour")) {
            return java.time.Duration.ofHours(amount);
        }
        if (unit.startsWith("day")) {
            return java.time.Duration.ofDays(amount);
        }
        throw new IllegalArgumentException("unsupported interval unit: " + unit);
    }
}
