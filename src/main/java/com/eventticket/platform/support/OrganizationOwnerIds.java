package com.eventticket.platform.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Who owns each of a set of Organizations, for a caller who is a member of none of them.
 *
 * <p>The one read in this system that the membership policy is right to refuse and a platform
 * administrator still needs (requirements/001 criterion 14). It goes through a
 * {@code SECURITY DEFINER} function rather than a wider policy, for the reason V5 gives about
 * a buyer holding a seat: widening the policy would grant every Membership in the system for
 * every purpose in order to serve one screen.
 *
 * <p>Deliberately narrow. It answers with ids and nothing else - names and addresses come from
 * the {@code UserDirectory} afterwards, so the privileged query returns the smallest thing that
 * answers the question.
 */
@Component
public class OrganizationOwnerIds {

    private final JdbcTemplate jdbc;

    public OrganizationOwnerIds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Owner user ids by Organization, for the whole page in one query. */
    public Map<UUID, List<UUID>> forOrganizations(List<UUID> organizationIds) {
        if (organizationIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query(
                        "select organization_id, user_id from organization_owner_ids(?)",
                        statement -> statement.setArray(1, statement.getConnection()
                                .createArrayOf("uuid", organizationIds.toArray())),
                        (row, index) -> Map.entry(
                                row.getObject("organization_id", UUID.class),
                                row.getObject("user_id", UUID.class)))
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }
}
