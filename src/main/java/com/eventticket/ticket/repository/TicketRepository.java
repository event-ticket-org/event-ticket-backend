package com.eventticket.ticket.repository;

import com.eventticket.ticket.domain.Ticket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    public List<Ticket> findByOrderIdOrderBySeatLabelAsc(UUID orderId);

    public Optional<Ticket> findByCodeLookup(String codeLookup);

    public long countByOrderId(UUID orderId);
}
