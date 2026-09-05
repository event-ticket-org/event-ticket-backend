package com.eventticket.event.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The fields a PATCH asked to change. Null means "not mentioned", so a request that sends
 * only a title leaves everything else alone.
 *
 * <p>{@code unsellableSeatIds} is the exception, and not by choice: the contract types it as
 * a plain array, so a client that omits it and a client that sends {@code []} arrive here
 * identically. An empty list is therefore read as "no change" rather than "put every seat back
 * on sale" - the safe half of an ambiguity the contract cannot currently express. Marking the
 * last held-back seat sellable again needs the contract to distinguish the two.
 */
public record EventChanges(String title, String description, String coverImageUrl,
                           Instant startsAt, Instant doorsOpenAt, Instant endsAt,
                           Boolean listed, List<UUID> unsellableSeatIds) {

    public boolean touchesSeats() {
        return unsellableSeatIds != null && !unsellableSeatIds.isEmpty();
    }
}
