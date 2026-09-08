package com.eventticket.identity.domain;

import com.eventticket.organization.domain.Membership;
import com.eventticket.organization.domain.Organization;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The signed-in User and the Organizations they belong to.
 *
 * <p>The Organizations themselves rather than their names, because a Membership has to say
 * where its Organization stands (requirements/001 criterion 16) and not only what it is
 * called. `GetMe` was already loading them and keeping one field; a person who cannot see they
 * are waiting for approval reads the refusal at publish time as the product being broken.
 */
public record MeView(AppUser user, List<Membership> memberships,
                     Map<UUID, Organization> organizations) {}
