package com.eventticket.ticket.repository;

import com.eventticket.ticket.domain.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends MongoRepository<Ticket, UUID> {

    /**
     * KB invariant 13: one Ticket, one admission, one Scan. Two devices reading the same code
     * at the same instant is the normal case at a door with four scanners, so this is a single
     * conditional statement and the row count is the answer - 1 admits, 0 means somebody else
     * got there first.
     *
     * <p>The loser does not race: it blocks on the row lock, re-evaluates the predicate once
     * the winner commits, and finds a status that is no longer VALID. Reading first and then
     * updating would let both through, and no amount of care in the application closes that.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           update Ticket t
              set t.status = :redeemed,
                  t.redeemedAt = :at,
                  t.redeemedByUserId = :userId,
                  t.redeemedDeviceId = :deviceId
            where t.id = :id
              and t.status = :valid
           """)
    public int redeem(@Param("id") UUID id,
                      @Param("redeemed") Ticket.Status redeemed,
                      @Param("valid") Ticket.Status valid,
                      @Param("at") Instant at,
                      @Param("userId") UUID userId,
                      @Param("deviceId") String deviceId);

    public List<Ticket> findByOrderIdOrderBySeatLabelAsc(UUID orderId);

    /** requirements/008 criterion 6: cancelling voids every Ticket, not only the paid ones. */
    public List<Ticket> findByEventId(UUID eventId);

    public Optional<Ticket> findByCodeLookup(String codeLookup);

    public long countByOrderId(UUID orderId);

    /**
     * requirements/008 criterion 3, asked before anything is refunded: has anyone been let in
     * on this Order? A count rather than a load, because the answer is a yes or no and the
     * Tickets themselves are not wanted.
     */
    public long countByOrderIdAndStatus(UUID orderId, Ticket.Status status);
}
