package com.eventticket.shared.audit;

import java.util.UUID;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditEntryRepository extends MongoRepository<AuditEntry, UUID> {}
