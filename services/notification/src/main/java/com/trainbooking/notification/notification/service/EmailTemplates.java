package com.trainbooking.notification.notification.service;

import com.trainbooking.notification.notification.model.NotificationRequest;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/**
 * The words of the three emails (ticket, cancellation, refund), as plain text and as HTML. A pure class: the same request gives the
 * same email, so it is tested without a database or a mail server. Everything that came from a request is escaped in the HTML.
 */
public class EmailTemplates {

    /** A finished email. */
    public record Rendered(String subject, String text, String html) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private final ZoneId zone;

    public EmailTemplates(ZoneId zone) {
        this.zone = zone;
    }

    public Rendered render(NotificationRequest request) {
        String subject = switch (request.kind()) {
            case TICKET -> "Your train ticket " + request.reference();
            case CANCELLATION -> "Your booking " + request.reference() + " is cancelled";
            case REFUND -> "Your refund for booking " + request.reference();
        };
        StringBuilder text = new StringBuilder();
        StringBuilder html = new StringBuilder("<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222\">");

        String name = request.toName() == null || request.toName().isBlank() ? "" : " " + request.toName().trim();
        paragraph(text, html, "Hello" + name + ",");

        switch (request.kind()) {
            case TICKET -> paragraph(text, html, "Your booking " + request.reference() + " is confirmed. Your ticket is attached to this email as a PDF. "
                    + "Show its QR code at the gate.");
            case CANCELLATION -> {
                paragraph(text, html, "Your booking " + request.reference() + " has been cancelled.");
                if (request.amount() != null) {
                    switch (request.method()) {
                        case "COUNTER" -> paragraph(text, html, "You paid " + money(request) + " at the counter. The refund is handled at the support desk.");
                        case "COMPLIMENTARY" -> paragraph(text, html, "Nothing was charged for this booking, so there is no refund.");
                        default -> paragraph(text, html, "The whole amount, " + money(request) + ", is being refunded to your card. You will get another email when the refund is done.");
                    }
                }
            }
            case REFUND -> paragraph(text, html, "The refund of " + (request.amount() == null ? "your payment" : money(request))
                    + " for booking " + request.reference() + " is done. It can take a few days to show on your card statement.");
        }
        if (request.detail() != null && !request.detail().isBlank()) {
            paragraph(text, html, request.detail().trim());
        }

        NotificationRequest.Trip trip = request.trip();
        if (trip != null) {
            String when = DATE.format(trip.departure().atZoneSameInstant(zone));
            paragraph(text, html, "Journey: " + trip.fromStation() + " " + TIME.format(trip.departure().atZoneSameInstant(zone)) + " to "
                    + trip.toStation() + " " + TIME.format(trip.arrival().atZoneSameInstant(zone)) + ", " + when + ", train " + trip.train() + ".");
        }
        List<String> lines = request.lines() == null ? List.of() : request.lines();
        if (!lines.isEmpty()) {
            text.append("\n");
            html.append("<ul>");
            for (String line : lines) {
                text.append("- ").append(line).append("\n");
                html.append("<li>").append(HtmlUtils.htmlEscape(line)).append("</li>");
            }
            html.append("</ul>");
        }
        if (request.kind() == NotificationRequest.Kind.TICKET && request.amount() != null) {
            paragraph(text, html, switch (request.method()) {
                case "COUNTER" -> "Paid at counter: " + money(request);
                case "COMPLIMENTARY" -> "Complimentary (no charge). Fare value: " + money(request);
                default -> "Total paid: " + money(request);
            });
        }
        paragraph(text, html, "Booking reference: " + request.reference());
        html.append("</div>");
        return new Rendered(subject, text.toString().stripTrailing() + "\n", html.toString());
    }

    private static String money(NotificationRequest request) {
        return request.amount().toPlainString() + " " + (request.currency() == null ? "EGP" : request.currency());
    }

    private static void paragraph(StringBuilder text, StringBuilder html, String line) {
        text.append(line).append("\n\n");
        html.append("<p>").append(HtmlUtils.htmlEscape(line)).append("</p>");
    }
}
