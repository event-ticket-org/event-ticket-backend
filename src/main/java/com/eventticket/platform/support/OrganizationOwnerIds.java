package com.eventticket.platform.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import com.eventticket.organization.domain.Membership;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Who owns each of a set of Organizations, for a caller who is a member of none of them.
 *
 * <p>The one read in this system that the membership policy was right to refuse and a platform
 * administrator still needs (requirements/001 criterion 14). It went through a
 * {@code SECURITY DEFINER} function rather than a wider policy, for the reason V5 gives about
 * a buyer holding a seat: widening the policy would have granted every Membership in the system
 * for every purpose in order to serve one screen.
 *
 * <p>It is now an ordinary query over {@code membership}, and so is every other read of that
 * collection. <strong>The narrow exception became the general case.</strong> Nothing here is
 * privileged because nothing anywhere is restricted - and the class stays, unchanged in
 * purpose, as the place that documents what the restriction used to be.
 *
 * <p>Deliberately narrow. It answers with ids and nothing else - names and addresses come from
 * the {@code UserDirectory} afterwards, so the privileged query returns the smallest thing that
 * answers the question.
 */
@Component
public class OrganizationOwnerIds {

    private final MongoTemplate mongo;

    public OrganizationOwnerIds(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** Owner user ids by Organization, for the whole page in one query. */
    public Map<UUID, List<UUID>> forOrganizations(List<UUID> organizationIds) {
        if (organizationIds.isEmpty()) {
            return Map.of();
        }
        return mongo.find(new Query(Criteria.where("organizationId").in(organizationIds)
                        .and("role").is(Membership.Role.OWNER.name())), Membership.class)
                .stream()
                .collect(Collectors.groupingBy(Membership::organizationId,
                        Collectors.mapping(Membership::userId, Collectors.toList())));
    }
}
