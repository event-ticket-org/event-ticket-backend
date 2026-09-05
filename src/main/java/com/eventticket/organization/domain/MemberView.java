package com.eventticket.organization.domain;

/** A Membership with the person's details resolved for display. */
public record MemberView(Membership membership, String email, String displayName) {}
