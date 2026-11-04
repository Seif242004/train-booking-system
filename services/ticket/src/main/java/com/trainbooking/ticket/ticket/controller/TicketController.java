package com.trainbooking.ticket.ticket.controller;

import com.trainbooking.ticket.ticket.model.Ticket;
import com.trainbooking.ticket.ticket.model.TicketRequest;
import com.trainbooking.ticket.ticket.service.TicketService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service endpoints used by Booking and Seat (shared API key). Users never call Ticket. */
@RestController
public class TicketController {

    private final TicketService service;

    public TicketController(TicketService service) {
        this.service = service;
    }

    /** Makes the ticket of a booking (201), or returns the existing one (200). */
    @PutMapping("/internal/tickets/{bookingId}")
    ResponseEntity<Map<String, Object>> issue(@PathVariable long bookingId, @Valid @RequestBody TicketRequest request) {
        TicketService.Issued issued = service.issue(bookingId, request);
        return ResponseEntity.status(issued.created() ? 201 : 200).body(view(issued.ticket()));
    }

    @GetMapping("/internal/tickets/{bookingId}")
    Map<String, Object> get(@PathVariable long bookingId) {
        return view(service.get(bookingId));
    }

    @GetMapping("/internal/tickets/{bookingId}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable long bookingId) {
        byte[] pdf = service.pdf(bookingId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ticket-" + bookingId + ".pdf\"").body(pdf);
    }

    /** The QR code of the ticket as a PNG, for the ticket page. The Gateway decides who may see it. */
    @GetMapping("/internal/tickets/{bookingId}/qr")
    ResponseEntity<byte[]> qr(@PathVariable long bookingId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(service.qrPng(bookingId));
    }

    /** The booking was cancelled: the QR token is no longer valid. */
    @PostMapping("/internal/tickets/{bookingId}/invalidate")
    Map<String, Object> invalidate(@PathVariable long bookingId) {
        return view(service.invalidate(bookingId));
    }

    private static Map<String, Object> view(Ticket ticket) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("ticketId", ticket.id());
        view.put("bookingId", ticket.bookingId());
        view.put("reference", ticket.reference());
        view.put("status", ticket.status().name());
        view.put("createdAt", ticket.createdAt());
        view.put("invalidatedAt", ticket.invalidatedAt());
        return view;
    }
}
