package com.eventticket.shared;

/**
 * The facts about a person that another feature is allowed to know.
 *
 * <p>Three of them, and they travel together because the one caller that needs any of them -
 * an administrator deciding whether to endorse an Organization (requirements/001 criterion
 * 14) - needs all three at once. Fetching them one at a time is a query per person per fact,
 * which is the shape of slowness that only appears once somebody has real data.
 */
public record DirectoryUser(String displayName, String email, boolean emailVerified) {
}
