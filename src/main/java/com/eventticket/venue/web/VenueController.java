package com.eventticket.venue.web;

import com.eventticket.api.VenuesApi;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.Venue;
import com.eventticket.api.model.VenueInput;
import com.eventticket.venue.usecase.CreateVenue;
import com.eventticket.venue.usecase.DeleteVenue;
import com.eventticket.venue.usecase.GetSeatMap;
import com.eventticket.venue.usecase.GetVenue;
import com.eventticket.venue.usecase.ListVenues;
import com.eventticket.venue.usecase.ReplaceSeatMap;
import com.eventticket.venue.usecase.UpdateVenue;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generated contract types live here and go no further: the use cases take and return domain
 * types, so the published API never becomes the domain model.
 *
 * <p>{@code Venue} exists in both worlds under the same simple name. The generated one is
 * imported and the domain one is qualified; getting that backwards compiles and then maps the
 * wrong type.
 */
@RestController
public class VenueController implements VenuesApi {

    private final CreateVenue createVenue;
    private final ListVenues listVenues;
    private final GetVenue getVenue;
    private final UpdateVenue updateVenue;
    private final DeleteVenue deleteVenue;
    private final GetSeatMap getSeatMap;
    private final ReplaceSeatMap replaceSeatMap;

    public VenueController(CreateVenue createVenue, ListVenues listVenues, GetVenue getVenue,
                    UpdateVenue updateVenue, DeleteVenue deleteVenue, GetSeatMap getSeatMap,
                    ReplaceSeatMap replaceSeatMap) {
        this.createVenue = createVenue;
        this.listVenues = listVenues;
        this.getVenue = getVenue;
        this.updateVenue = updateVenue;
        this.deleteVenue = deleteVenue;
        this.getSeatMap = getSeatMap;
        this.replaceSeatMap = replaceSeatMap;
    }

    @Override
    public ResponseEntity<List<Venue>> venuesGet() {
        return ResponseEntity.ok(listVenues.list().stream().map(VenueController::toDto).toList());
    }

    @Override
    public ResponseEntity<Venue> venuesPost(VenueInput request) {
        var created = createVenue.create(request.getName(), request.getAddress(),
                request.getCity(), request.getTimezone());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(created));
    }

    @Override
    public ResponseEntity<Venue> venuesVenueIdGet(UUID venueId) {
        return ResponseEntity.ok(toDto(getVenue.get(venueId)));
    }

    @Override
    public ResponseEntity<Venue> venuesVenueIdPatch(UUID venueId, VenueInput request) {
        var updated = updateVenue.update(venueId, request.getName(), request.getAddress(),
                request.getCity(), request.getTimezone());
        return ResponseEntity.ok(toDto(updated));
    }

    @Override
    public ResponseEntity<Void> venuesVenueIdDelete(UUID venueId) {
        deleteVenue.delete(venueId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<SeatMap> venuesVenueIdSeatMapGet(UUID venueId) {
        return ResponseEntity.ok(SeatMapMapper.toDto(getSeatMap.get(venueId)));
    }

    @Override
    public ResponseEntity<SeatMap> venuesVenueIdSeatMapPut(UUID venueId, SeatMap request) {
        var replaced = replaceSeatMap.replace(venueId, SeatMapMapper.toDocument(request));
        return ResponseEntity.ok(SeatMapMapper.toDto(replaced));
    }

    private static Venue toDto(com.eventticket.venue.domain.Venue venue) {
        var dto = new Venue(venue.name(), venue.city(), venue.timezone(), venue.id());
        dto.setAddress(venue.address());
        dto.setSeatCount(venue.seatCount());
        return dto;
    }
}
