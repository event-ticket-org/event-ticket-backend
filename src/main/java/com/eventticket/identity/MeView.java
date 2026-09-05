package com.eventticket.identity;

import com.eventticket.organization.Membership;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The signed-in User and the Organizations they belong to, with names resolved for display. */
public record MeView(AppUser user, List<Membership> memberships, Map<UUID, String> organizationNames) {}
