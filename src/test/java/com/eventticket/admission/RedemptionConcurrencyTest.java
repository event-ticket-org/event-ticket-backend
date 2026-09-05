package com.eventticket.admission;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Event;
import com.eventticket.api.model.InviteMemberRequest;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * requirements/007 criterion 9 and KB invariant 13 - the third of the three things CLAUDE.md
 * says do not fail under mocked repositories.
 *
 * <p>Four devices, because nfr.md allows up to four scanners per Event, all reading the same
 * code at the same instant. That is not a contrived race: it is what happens when one phone is
 * handed down a queue that has split across two gates.
 *
 * <p>Real threads against the running server. A simulated race would pass against a read of the
 * Ticket's status followed by a write, which is exactly the implementation this rules out.
 */
class RedemptionConcurrencyTest extends ApiTest {

    private static final OffsetDateTime SOON = OffsetDateTime.now().plus(2, ChronoUnit.HOURS);
    private static final OffsetDateTime DOORS_OPEN = OffsetDateTime.now().minus(1, ChronoUnit.HOURS);
    private static final OffsetDateTime ENDS = OffsetDateTime.now().plus(6, ChronoUnit.HOURS);
    private static final int DEVICES = 4;

    private record Door(UUID eventId, TokenPair staff, String code) {}

    @Test
    @DisplayName("four devices, one code, one instant: exactly one person goes in")
    void exactlyOneScanRedeems() throws Exception {
        Door door = openDoor();

        List<ScanResult> results = scanAtOnce(door);

        assertThat(results).extracting(ScanResult::getOutcome)
                .filteredOn(ScanOutcome.ADMITTED::equals).hasSize(1);
        assertThat(results).extracting(ScanResult::getOutcome)
                .filteredOn(ScanOutcome.ALREADY_REDEEMED::equals).hasSize(DEVICES - 1);

        // The database agrees, and every attempt is on the record whatever it was.
        assertThat(jdbc.queryForObject(
                "select count(*) from ticket where code_lookup = ? and status = 'REDEEMED'",
                Long.class, door.code().split("-")[1])).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from scan", Long.class))
                .isEqualTo((long) DEVICES);
        assertThat(jdbc.queryForObject(
                "select count(*) from scan where outcome = 'ADMITTED'", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("the losers all name the same device as the one that let someone in")
    void losersAgreeOnWhoWentFirst() throws Exception {
        Door door = openDoor();

        List<ScanResult> refusals = scanAtOnce(door).stream()
                .filter(result -> result.getOutcome() == ScanOutcome.ALREADY_REDEEMED)
                .toList();

        // criterion 5. Three people asking "who used my ticket?" must not get three answers.
        assertThat(refusals).hasSize(DEVICES - 1);
        assertThat(refusals).extracting(ScanResult::getFirstRedeemedDeviceId)
                .doesNotContainNull()
                .containsOnly(refusals.get(0).getFirstRedeemedDeviceId());
        assertThat(refusals).allSatisfy(refusal ->
                assertThat(refusal.getFirstRedeemedAt()).isNotNull());
    }

    private List<ScanResult> scanAtOnce(Door door) throws Exception {
        var results = new ConcurrentLinkedQueue<ScanResult>();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(DEVICES)) {
            List<? extends Future<?>> scans = java.util.stream.IntStream.range(0, DEVICES)
                    .mapToObj(device -> pool.submit(() -> {
                        start.await();
                        results.add(scan(door.staff(), door.eventId(), door.code(), "gate-" + device));
                        return null;
                    })).toList();
            start.countDown();
            for (Future<?> attempt : scans) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        }
        return List.copyOf(results);
    }

    /** A published event with doors open, one sold ticket, and staff to scan it. */
    private Door openDoor() {
        TokenPair alice = signUp("alice@example.com");
        Organization organization = createOrganization(alice, "Acme Events");
        approve(organization);
        TokenPair manager = switchTo(alice, organization);

        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        putSeatMap(manager, venue.getId(), SeatMaps.block("Standard", 2, 5));
        Event event = createEvent(manager, venue.getId(), "Live in Saigon", SOON, DOORS_OPEN, ENDS);
        priceTier(manager, event.getId(), "Standard", 250_000);
        publish(manager, event.getId(), Event.class);

        exchange(HttpMethod.POST, "/organization/members", manager,
                new InviteMemberRequest("doorman@example.com", Role.GATE_STAFF), Membership.class);
        TokenPair staff = switchTo(signUp("doorman@example.com"), organization);

        TokenPair buyer = signUp("buyer@example.com");
        Order paid = buyAndPay(buyer, event.getId(), seatIdsOf(event.getId(), 1));

        return new Door(event.getId(), staff, ticketCodesOf(buyer, paid.getId()).get(0));
    }
}
