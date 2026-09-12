package com.eventticket.ticket.repository;

import com.eventticket.ticket.domain.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface TicketRepository extends MongoRepository<Ticket, UUID> {

    /**
     * KB invariant 13: one Ticket, one admission, one Scan. Two devices reading the same code
     * at the same instant is the normal case at a door with four scanners, so this is a single
     * conditional statement and the row count is the answer - 1 admits, 0 means somebody else
     * got there first.
     *
     * <p>Reading first and then updating would let both through, and no amount of care in the
     * application closes that. The condition has to be inside the write.
     *
     * <p><strong>This is the one place the migration is an improvement in expression rather
     * than a compromise.</strong> "A conditional update whose modified count is the answer" is
     * an idiom SQL has to be persuaded into and MongoDB is built around: the filter is the
     * condition, and a write to a single document is atomic without a transaction, a lock hint
     * or a replica set. The mechanism differs - Postgres blocks the loser on a row lock until
     * the winner commits, where here the loser's filter simply stops matching - but the
     * guarantee and the answer are identical.
     *
     * <p>Note {@code status} appears in both the filter and the update. That is what makes it a
     * compare-and-set rather than a write.
     */
    @Query("{ '_id': ?0, 'status': ?2 }")
    @Update("""
            { '$set': { 'status': ?1,
                        'redeemedAt': ?3,
                        'redeemedByUserId': ?4,
                        'redeemedDeviceId': ?5 } }
            """)
    public int redeem(UUID id,
                      Ticket.Status redeemed,
                      Ticket.Status valid,
                      Instant at,
                      UUID userId,
                      String deviceId);

    public List<Ticket> findByOrderIdOrderBySeatLabelAsc(UUID orderId);

    /** requirements/008 criterion 6: cancelling voids every Ticket, not only the paid ones. */
    public List<Ticket> findByEventId(UUID eventId);

    /**
     * The door's lookup, narrowed to the Organization doing the scanning.
     *
     * <p><strong>The organizationId is not redundant and its absence was a disclosure.</strong>
     * Under Postgres the {@code ticket_access} policy applied the tenant to this read, so a code
     * sold by another Organization simply was not found and the door answered
     * {@code UNKNOWN_CODE}. That is deliberate: CLAUDE.md's rule is that tenant isolation
     * outranks a helpful error message, because {@code WRONG_EVENT} tells the staff holding the
     * scanner that this code is a real ticket somebody else sold.
     *
     * <p>Without the policy the lookup found the other Organization's Ticket, the next line
     * compared event ids, and the door started answering {@code WRONG_EVENT} - confirming to one
     * organizer that a code belongs to a rival. Nothing failed; a test that had been asserting
     * this since before the migration is the only reason it was noticed.
     */
    public Optional<Ticket> findByCodeLookupAndOrganizationId(String codeLookup, UUID organizationId);

    public long countByOrderId(UUID orderId);

    /**
     * requirements/008 criterion 3, asked before anything is refunded: has anyone been let in
     * on this Order? A count rather than a load, because the answer is a yes or no and the
     * Tickets themselves are not wanted.
     */
    public long countByOrderIdAndStatus(UUID orderId, Ticket.Status status);
}
