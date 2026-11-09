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

// Controller rules of this project: no business logic here. A controller reads the request, calls the service, and shapes the answer.
// Who may call: only a caller with the shared key (InternalApiKeyFilter); the Gateway does not route to this service. The error shape of
// every failure is written by GlobalExceptionHandler.
/** Service-to-service endpoints used by Booking and Seat (shared API key). Users never call Ticket. */
// {@code @RestController} = {@code @Controller} + {@code @ResponseBody}: the return values are written as the HTTP body (JSON, bytes),
// not looked up as page names. Paths are written in full on each method, because every route lives under /internal.
@RestController
public class TicketController {

    private final TicketService service;

    // Constructor injection of the service (no field injection, so the class is easy to test).
    public TicketController(TicketService service) {
        this.service = service;
    }

    /** Makes the ticket of a booking (201), or returns the existing one (200). */
    // {@code @PutMapping} routes HTTP PUT on that path here. PUT fits because the call is idempotent: the same booking id always ends with
    // the same ticket. {@code @PathVariable} binds {bookingId} from the URL; {@code @RequestBody} reads the JSON body into a TicketRequest;
    // {@code @Valid} runs the Bean Validation rules of that record first (a failure is a 400 before this method runs).
    // Output: 201 when this call made the ticket, 200 when it existed; the body never contains the QR token or the PDF.
    @PutMapping("/internal/tickets/{bookingId}")
    ResponseEntity<Map<String, Object>> issue(@PathVariable long bookingId, @Valid @RequestBody TicketRequest request) {
        TicketService.Issued issued = service.issue(bookingId, request);
        return ResponseEntity.status(issued.created() ? 201 : 200).body(view(issued.ticket()));
    }

    // {@code @GetMapping}: HTTP GET. Returns the ticket data of the booking (200) or 404 ticket_not_found.
    @GetMapping("/internal/tickets/{bookingId}")
    Map<String, Object> get(@PathVariable long bookingId) {
        return view(service.get(bookingId));
    }

    // The stored PDF bytes as application/pdf. "inline" lets a browser show it instead of downloading it. 404 ticket_not_found.
    @GetMapping("/internal/tickets/{bookingId}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable long bookingId) {
        byte[] pdf = service.pdf(bookingId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"ticket-" + bookingId + ".pdf\"").body(pdf);
    }

    /** The QR code of the ticket as a PNG, for the ticket page. The Gateway decides who may see it. */
    // Cache-Control private, no-store: the picture holds the secret token, so no proxy or browser may keep a copy.
    @GetMapping("/internal/tickets/{bookingId}/qr")
    ResponseEntity<byte[]> qr(@PathVariable long bookingId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(service.qrPng(bookingId));
    }

    /** The booking was cancelled: the QR token is no longer valid. */
    // {@code @PostMapping}: HTTP POST. A state change, safe to repeat. Returns the ticket with status INVALIDATED (404 if there is none).
    @PostMapping("/internal/tickets/{bookingId}/invalidate")
    Map<String, Object> invalidate(@PathVariable long bookingId) {
        return view(service.invalidate(bookingId));
    }

    // The JSON answer of the ticket calls. A plain ordered map keeps the exact field list in one place and deliberately leaves out the
    // qr_token (a secret: whoever has it has the QR code).
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
