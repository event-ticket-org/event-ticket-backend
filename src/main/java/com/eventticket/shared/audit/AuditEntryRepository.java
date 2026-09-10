package com.eventticket.shared.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditEntryRepository extends MongoRepository<AuditEntry, UUID> {}
