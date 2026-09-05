package com.eventticket.identity.domain;

import java.util.UUID;

/** A freshly issued access and refresh token pair. Mapped to the contract's TokenPair by the controller. */
public record Session(String accessToken, String refreshToken, long expiresInSeconds, UUID activeOrganizationId) {}
