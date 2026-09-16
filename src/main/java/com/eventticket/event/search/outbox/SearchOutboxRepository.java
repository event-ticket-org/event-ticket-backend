package com.eventticket.event.search.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SearchOutboxRepository extends JpaRepository<SearchOutboxEntry, Long> {

    /**
     * The distinct Events waiting, oldest first, with the highest row id seen for each.
     *
     * <p>Distinct is the batching: an Event edited ten times is ten rows and one read.
     *
     * <p>The maximum id travels with it so the drain deletes exactly what it accounted for. A
     * row written while the drain was working keeps its place in the queue instead of being
     * swept away unread - which is the whole difference between at-least-once and at-most-once,
     * and the failure it prevents is an edit that silently never reaches the index.
     */
    @Query("""
           select e.eventId as eventId, max(e.id) as throughId
             from SearchOutboxEntry e
            group by e.eventId
            order by max(e.id) asc
           """)
    public List<Pending> findPending(Pageable page);

    /**
     * {@code flushAutomatically} paired with {@code clearAutomatically}, always. Clearing the
     * persistence context without flushing discards pending changes rather than writing them,
     * and the default for the flush is false - the trap {@code ResetPassword} documents.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SearchOutboxEntry e where e.eventId = :eventId and e.id <= :throughId")
    public void deleteHandled(@Param("eventId") UUID eventId, @Param("throughId") Long throughId);

    public interface Pending {
        UUID getEventId();

        Long getThroughId();
    }
}
