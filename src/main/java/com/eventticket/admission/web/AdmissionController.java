package com.eventticket.admission.web;

import com.eventticket.admission.domain.ScanResult;
import com.eventticket.admission.usecase.ScanTicket;
import com.eventticket.api.AdmissionApi;
import com.eventticket.api.model.ScanOutcome;
import com.eventticket.api.model.ScanRequest;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * A refusal is a successful request. Every outcome comes back 200 with a reason, because the
 * person holding the scanner needs to be told what happened, and an HTTP error is a thing a
 * client renders as "something went wrong" - which is the one message that helps nobody at a
 * door with a queue behind it.
 *
 * <p>The two genuine errors stay errors: 403 if the caller may not scan for this Organization,
 * 429 if the device is over its rate limit.
 */
@RestController
public class AdmissionController implements AdmissionApi {

    private final ScanTicket scanTicket;

    public AdmissionController(ScanTicket scanTicket) {
        this.scanTicket = scanTicket;
    }

    @Override
    public ResponseEntity<com.eventticket.api.model.ScanResult> eventsEventIdScansPost(
            UUID eventId, ScanRequest request) {
        return ResponseEntity.ok(toDto(
                scanTicket.scan(eventId, request.getTicketCode(), request.getDeviceId())));
    }

    private static com.eventticket.api.model.ScanResult toDto(ScanResult result) {
        var dto = new com.eventticket.api.model.ScanResult(
                ScanOutcome.fromValue(result.outcome().name()), result.message());
        dto.setSeatLabel(result.seatLabel());
        dto.setTierName(result.tierName());
        dto.setFirstRedeemedAt(result.firstRedeemedAt() == null
                ? null : result.firstRedeemedAt().atOffset(ZoneOffset.UTC));
        dto.setFirstRedeemedDeviceId(result.firstRedeemedDeviceId());
        return dto;
    }
}
