package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.domain.OrderDetail;
import com.eventticket.checkout.domain.OrderSeat;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.event.domain.Event;
import com.eventticket.event.domain.EventSeat;
import com.eventticket.event.domain.PricingTier;
import com.eventticket.event.repository.EventRepository;
import com.eventticket.event.repository.EventSeatAvailability;
import com.eventticket.event.repository.EventSeatRepository;
import com.eventticket.event.repository.PricingTierRepository;
import com.eventticket.shared.UserDirectory;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.money.Money;
import com.eventticket.shared.tenancy.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/004. Selection is free and optimistic; this is where the commitment and the
 * clock start.
 *
 * <p>The race is not adjudicated here. {@code EventSeatAvailability.hold} locks and updates in
 * one statement and reports which seats it actually took; a shortfall means somebody else got
 * there first, and their identity, timing and intent are all irrelevant. That is what KB
 * invariant 5 asks for, and at 500 concurrent buyers a read followed by a write would lose.
 *
 * <p>A refusal names the seats (criterion 6) so the client can keep the rest of the
 * selection. Failing with "some seats are gone" would make a buyer start over for one row.
 */
@Component
public class BeginCheckout {

    private static final Logger log = LoggerFactory.getLogger(BeginCheckout.class);

    private final OrderRepository orders;
    private final EventRepository events;
    private final EventSeatRepository seats;
    private final PricingTierRepository tiers;
    private final EventSeatAvailability availability;
    private final UserDirectory users;
    private final Duration holdWindow;

    public BeginCheckout(OrderRepository orders, EventRepository events, EventSeatRepository seats, PricingTierRepository tiers,
                  EventSeatAvailability availability, UserDirectory users,
                  @Value("${app.checkout.hold-window:PT10M}") Duration holdWindow) {
        this.orders = orders;
        this.events = events;
        this.seats = seats;
        this.tiers = tiers;
        this.availability = availability;
        this.users = users;
        this.holdWindow = holdWindow;
    }

    @Transactional
    public OrderDetail begin(UUID eventId, List<UUID> seatIds) {
        UUID userId = TenantContext.requireUserId();
        requireVerifiedEmail(userId);

        Event event = events.findOrThrow(eventId);
        requireOnSale(event);

        List<EventSeat> chosen = seats.findByEventIdAndIdIn(eventId, seatIds);
        if (chosen.size() != seatIds.size()) {
            throw ApiException.notFound("Some of those seats");
        }

        Map<String, Money> prices = pricesFor(eventId);
        Instant expiresAt = Instant.now().plus(holdWindow);

        // The Order exists before the holds because a hold points at it. If the holds cannot
        // all be taken, this whole transaction rolls back and the Order goes with it - there
        // is no half-made order to clean up later.
        //
        // saveAndFlush, not save. The hold is taken by a SQL function through JdbcTemplate,
        // which shares the transaction but not the persistence context, so an INSERT that JPA
        // is still holding back is a row the database cannot see - and the hold's foreign key
        // to it fails.
        Order order = orders.saveAndFlush(new Order(event.organizationId(), eventId, userId,
                totalOf(chosen, prices), expiresAt));

        List<UUID> held = availability.hold(eventId, seatIds, order.id(), expiresAt);
        if (held.size() != seatIds.size()) {
            refuseNaming(seatIds, held, chosen, eventId);
        }

        order.holdSeats(chosen.stream()
                .map(seat -> new OrderSeat(seat.id(), seat.label(), seat.tierName(),
                        prices.get(seat.tierName())))
                .toList());
        orders.save(order);

        log.info("Began checkout orderId={} eventId={} seats={} total={}",
                order.id(), eventId, held.size(), order.total().amount());

        return new OrderDetail(order, event.title(), order.seats());
    }

    /**
     * criterion 4. Verification never happens inside a checkout - the hold's clock would race
     * the email round-trip - so a buyer who has not confirmed their address is turned away
     * before any seat is touched.
     */
    private void requireVerifiedEmail(UUID userId) {
        if (!users.isVerified(userId)) {
            log.warn("Checkout refused: email not verified userId={}", userId);
            throw new ApiException(ErrorCodes.EMAIL_NOT_VERIFIED,
                    "Confirm your email address before buying tickets. "
                            + "Tickets are delivered to it.");
        }
    }

    private static void requireOnSale(Event event) {
        if (event.status() != Event.Status.PUBLISHED) {
            throw new ApiException(ErrorCodes.SEATS_UNAVAILABLE,
                    "Tickets for this event are not on sale.");
        }
        if (!event.startsAt().isAfter(Instant.now())) {
            throw new ApiException(ErrorCodes.SEATS_UNAVAILABLE, "This event has already started.");
        }
    }

    private Map<String, Money> pricesFor(UUID eventId) {
        Map<String, Money> prices = new LinkedHashMap<>();
        for (PricingTier tier : tiers.findByEventId(eventId)) {
            if (tier.isPriced()) {
                prices.put(tier.name(), tier.price());
            }
        }
        return prices;
    }

    private static Money totalOf(List<EventSeat> chosen, Map<String, Money> prices) {
        long total = 0;
        for (EventSeat seat : chosen) {
            Money price = prices.get(seat.tierName());
            if (price == null) {
                // Only reachable if a tier lost its price after publish, which publishing
                // forbids. Refusing beats selling a seat for nothing.
                throw new ApiException(ErrorCodes.SEATS_UNAVAILABLE,
                        "Seat " + seat.label() + " has no price and cannot be sold.");
            }
            total += price.amount();
        }
        return Money.vnd(total);
    }

    /** criterion 6: the response names the seats that got away, and nothing else changes. */
    private static void refuseNaming(List<UUID> requested, List<UUID> held,
                                     List<EventSeat> chosen, UUID eventId) {
        List<UUID> taken = requested.stream().filter(id -> !held.contains(id)).toList();
        String labels = chosen.stream().filter(seat -> taken.contains(seat.id()))
                .map(EventSeat::label).collect(Collectors.joining(", "));

        log.warn("Checkout refused: eventId={} seats already taken {}", eventId, labels);
        throw new ApiException(ErrorCodes.SEATS_UNAVAILABLE,
                "These seats were taken while you were choosing: " + labels
                        + ". Your other seats are still available.",
                Map.of("seatIds", taken));
    }
}
