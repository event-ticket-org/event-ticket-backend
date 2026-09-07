package com.eventticket.shared;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The few facts about a User that other features need, without depending on the identity
 * package's entities. Implemented there; used by organization and platform.
 *
 * <p>Kept deliberately small. Anything richer than this belongs in the identity package,
 * behind a use case of its own.
 */
public interface UserDirectory {

    boolean isVerified(UUID userId);

    boolean isPlatformAdmin(UUID userId);

    /**
     * The User with this address, creating an unregistered placeholder if there is none, so
     * that an Owner can invite someone who has not signed up yet (requirements/001
     * criterion 8).
     */
    UUID idForInvite(String emailAddress);

    String emailOf(UUID userId);

    /**
     * The addresses for many Users at once, keyed by id and missing whoever is not found.
     *
     * <p>Here because a list of Orders shows who bought each one, and asking per row is a query
     * per row - the one shape of slowness that does not show up until somebody has real data.
     */
    Map<UUID, String> emailsOf(Collection<UUID> userIds);

    /**
     * Name, address and whether the address is proven, for many Users at once and keyed by id.
     *
     * <p>The batched sibling of the three single lookups here, for the one caller that needs
     * all three about a page of people: the approval queue shows who is accountable for each
     * Organization on it (requirements/001 criterion 14), and asking per row per fact is six
     * queries for two organizations.
     */
    Map<UUID, DirectoryUser> usersOf(Collection<UUID> userIds);

    String displayNameOf(UUID userId);
}
