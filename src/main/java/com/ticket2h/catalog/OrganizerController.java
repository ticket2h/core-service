package com.ticket2h.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.catalog.OrganizerCatalogService.EventFields;
import com.ticket2h.catalog.OrganizerCatalogService.OrganizerEventDetail;
import com.ticket2h.catalog.OrganizerCatalogService.SeatRow;
import com.ticket2h.common.security.AuthUser;
import com.ticket2h.identity.UserDirectory.UserContact;

/** API của ban tổ chức. Chỉ vai trò ORGANIZER (chặn trong SecurityConfig theo /organizer/**). */
@RestController
@RequestMapping("/organizer")
class OrganizerController {

    private final OrganizerCatalogService catalog;

    OrganizerController(OrganizerCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/venues")
    @ResponseStatus(HttpStatus.CREATED)
    VenueResponse createVenue(AuthUser caller, @Valid @RequestBody VenueRequest request) {
        UUID organizerId = catalog.organizerOf(caller);
        return VenueResponse.of(catalog.createVenue(organizerId, request.name(), request.address(), request.city()));
    }

    @GetMapping("/venues")
    List<VenueResponse> venues(AuthUser caller) {
        return catalog.venues(catalog.organizerOf(caller)).stream().map(VenueResponse::of).toList();
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    EventResponse createEvent(AuthUser caller, @Valid @RequestBody EventRequest request) {
        return EventResponse.of(catalog.createEvent(catalog.organizerOf(caller), request.toFields()));
    }

    @GetMapping("/events")
    List<EventResponse> events(AuthUser caller) {
        return catalog.events(catalog.organizerOf(caller)).stream().map(EventResponse::of).toList();
    }

    @GetMapping("/events/{eventId}")
    EventDetailResponse event(AuthUser caller, @PathVariable UUID eventId) {
        return EventDetailResponse.of(catalog.eventDetail(catalog.organizerOf(caller), eventId));
    }

    @PutMapping("/events/{eventId}")
    EventResponse updateEvent(AuthUser caller, @PathVariable UUID eventId, @Valid @RequestBody EventRequest request) {
        return EventResponse.of(catalog.updateEvent(catalog.organizerOf(caller), eventId, request.toFields()));
    }

    @PostMapping("/events/{eventId}/publish")
    EventResponse publish(AuthUser caller, @PathVariable UUID eventId) {
        return EventResponse.of(catalog.publish(catalog.organizerOf(caller), eventId));
    }

    @PostMapping("/events/{eventId}/ticket-types")
    @ResponseStatus(HttpStatus.CREATED)
    TicketTypeResponse addTicketType(AuthUser caller, @PathVariable UUID eventId,
            @Valid @RequestBody TicketTypeRequest request) {
        return TicketTypeResponse.of(catalog.addTicketType(catalog.organizerOf(caller), eventId,
                request.name(), request.kind(), request.price(), request.quantity()), null);
    }

    @PostMapping("/events/{eventId}/seats")
    @ResponseStatus(HttpStatus.CREATED)
    SeatsCreatedResponse addSeats(AuthUser caller, @PathVariable UUID eventId,
            @Valid @RequestBody SeatSectionRequest request) {
        List<SeatRow> rows = request.rows().stream().map(r -> new SeatRow(r.label(), r.from(), r.to())).toList();
        int created = catalog.addSeats(catalog.organizerOf(caller), eventId, request.ticketTypeId(),
                request.section(), rows);
        return new SeatsCreatedResponse(created);
    }

    @PostMapping("/events/{eventId}/staff")
    @ResponseStatus(HttpStatus.CREATED)
    StaffResponse assignStaff(AuthUser caller, @PathVariable UUID eventId, @Valid @RequestBody StaffRequest request) {
        UserContact staff = catalog.assignStaff(catalog.organizerOf(caller), eventId, request.email());
        return new StaffResponse(staff.id(), staff.email(), staff.fullName());
    }

    @DeleteMapping("/events/{eventId}/staff/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeStaff(AuthUser caller, @PathVariable UUID eventId, @PathVariable UUID userId) {
        catalog.removeStaff(catalog.organizerOf(caller), eventId, userId);
    }

    record VenueRequest(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 500) String address,
            @NotBlank @Size(max = 100) String city) {}

    record VenueResponse(UUID id, String name, String address, String city) {
        static VenueResponse of(VenueRow v) {
            return new VenueResponse(v.id(), v.name(), v.address(), v.city());
        }
    }

    record EventRequest(
            @NotNull UUID venueId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 10_000) String description,
            @NotNull Instant startsAt,
            @NotNull Instant saleOpensAt,
            @NotNull Instant saleClosesAt,
            @Min(1) @Max(20) Integer maxTicketsPerUser) {

        EventFields toFields() {
            return new EventFields(venueId, title, description, startsAt, saleOpensAt, saleClosesAt, maxTicketsPerUser);
        }
    }

    record EventResponse(UUID id, UUID venueId, String title, String description, String timeZone,
            Instant startsAt, Instant saleOpensAt, Instant saleClosesAt, int maxTicketsPerUser, EventStatus status) {
        static EventResponse of(EventRow e) {
            return new EventResponse(e.id(), e.venueId(), e.title(), e.description(), e.timeZone().getId(),
                    e.startsAt(), e.saleOpensAt(), e.saleClosesAt(), e.maxTicketsPerUser(), e.status());
        }
    }

    record EventDetailResponse(EventResponse event, List<TicketTypeResponse> ticketTypes, List<UUID> staffUserIds) {
        static EventDetailResponse of(OrganizerEventDetail d) {
            Map<UUID, Integer> seats = d.seatCounts();
            return new EventDetailResponse(EventResponse.of(d.event()),
                    d.ticketTypes().stream().map(t -> TicketTypeResponse.of(t, seats.getOrDefault(t.id(), 0))).toList(),
                    d.staffUserIds());
        }
    }

    record TicketTypeRequest(@NotBlank @Size(max = 100) String name, @NotNull TicketKind kind,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal price,
            @Min(1) @Max(1_000_000) Integer quantity) {}

    record TicketTypeResponse(UUID id, String name, TicketKind kind, BigDecimal price, Integer totalQuantity,
            Integer availableQuantity, Integer seatCount) {
        static TicketTypeResponse of(TicketTypeRow t, Integer seatCount) {
            return new TicketTypeResponse(t.id(), t.name(), t.kind(), t.price(), t.totalQuantity(),
                    t.availableQuantity(), t.kind() == TicketKind.SEATED ? seatCount : null);
        }
    }

    record SeatSectionRequest(@NotNull UUID ticketTypeId, @NotBlank @Size(max = 50) String section,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull RowRequest> rows) {}

    record RowRequest(@NotBlank @Size(max = 10) String label, @Min(1) int from, @Min(1) int to) {}

    record SeatsCreatedResponse(int created) {}

    record StaffRequest(@NotBlank @Email String email) {}

    record StaffResponse(UUID userId, String email, String fullName) {}
}
